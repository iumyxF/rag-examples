package com.example.indexing.document;

import com.example.indexing.chunking.DocumentChunk;
import com.example.indexing.config.RagProperties;
import com.example.indexing.parsing.NormalizedDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PipelineArtifactStore {
    private static final String NORMALIZED = "normalized-document.json";
    private static final String CHUNKS = "chunks.jsonl";
    private static final String VECTORS = "embeddings.f32";
    private static final String VECTOR_META = "embeddings-meta.json";

    private final Path root;
    private final ObjectMapper mapper;

    public PipelineArtifactStore(RagProperties properties, ObjectMapper mapper) {
        this.root = Path.of(properties.storage().uploadDirectory()).toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    public void writeNormalized(String documentId, String versionId, NormalizedDocument value) {
        writeJson(path(documentId, versionId, NORMALIZED), value);
    }

    public NormalizedDocument readNormalized(String documentId, String versionId) {
        return readJson(path(documentId, versionId, NORMALIZED), NormalizedDocument.class);
    }

    public void writeChunks(String documentId, String versionId, List<DocumentChunk> chunks) {
        Path target = path(documentId, versionId, CHUNKS);
        try {
            Files.createDirectories(target.getParent());
            try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                for (DocumentChunk chunk : chunks) {
                    writer.write(mapper.writeValueAsString(chunk));
                    writer.newLine();
                }
            }
        } catch (Exception e) {
            throw artifactError("保存 Chunk 失败", e);
        }
    }

    public List<DocumentChunk> readChunks(String documentId, String versionId) {
        Path source = path(documentId, versionId, CHUNKS);
        try (var lines = Files.lines(source, StandardCharsets.UTF_8)) {
            List<DocumentChunk> chunks = new ArrayList<>();
            for (String line : lines.filter(value -> !value.isBlank()).toList()) {
                DocumentChunk saved = mapper.readValue(line, DocumentChunk.class);
                chunks.add(rebind(saved, documentId, versionId));
            }
            return chunks;
        } catch (Exception e) {
            throw artifactError("读取 Chunk 失败", e);
        }
    }

    public EmbeddingArtifact writeEmbeddings(
            String documentId, String versionId, String model, List<float[]> vectors) {
        int dimension = vectors.isEmpty() ? 0 : vectors.getFirst().length;
        if (vectors.stream().anyMatch(vector -> vector.length != dimension)) {
            throw new IllegalArgumentException("Embedding 向量维度不一致");
        }
        Path target = path(documentId, versionId, VECTORS);
        try {
            Files.createDirectories(target.getParent());
            try (var out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(target)))) {
                out.writeInt(vectors.size());
                out.writeInt(dimension);
                for (float[] vector : vectors) {
                    for (float value : vector) {
                        out.writeFloat(value);
                    }
                }
            }
            EmbeddingMetadata metadata =
                    new EmbeddingMetadata(model, vectors.size(), dimension, LocalDateTime.now());
            writeJson(path(documentId, versionId, VECTOR_META), metadata);
            return new EmbeddingArtifact(metadata, vectors);
        } catch (Exception e) {
            throw artifactError("保存 Embedding 失败", e);
        }
    }

    public EmbeddingArtifact readEmbeddings(String documentId, String versionId) {
        EmbeddingMetadata metadata =
                readJson(path(documentId, versionId, VECTOR_META), EmbeddingMetadata.class);
        try (var in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path(documentId, versionId, VECTORS))))) {
            int count = in.readInt();
            int dimension = in.readInt();
            if (count != metadata.count() || dimension != metadata.dimension()) {
                throw new IllegalStateException("Embedding 元数据与二进制文件不一致");
            }
            List<float[]> vectors = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                float[] vector = new float[dimension];
                for (int j = 0; j < dimension; j++) {
                    vector[j] = in.readFloat();
                }
                vectors.add(vector);
            }
            return new EmbeddingArtifact(metadata, vectors);
        } catch (Exception e) {
            throw artifactError("读取 Embedding 失败", e);
        }
    }

    public void inheritBefore(
            String documentId, String sourceVersion, String targetVersion, PipelineStep targetStep) {
        if (targetStep.ordinal() > PipelineStep.DOCLING.ordinal()) {
            copy(documentId, sourceVersion, targetVersion, NORMALIZED);
        }
        if (targetStep.ordinal() > PipelineStep.CHUNKING.ordinal()) {
            copy(documentId, sourceVersion, targetVersion, CHUNKS);
        }
        if (targetStep.ordinal() > PipelineStep.EMBEDDING.ordinal()) {
            copy(documentId, sourceVersion, targetVersion, VECTORS);
            copy(documentId, sourceVersion, targetVersion, VECTOR_META);
        }
    }

    public void deleteFrom(String documentId, String versionId, PipelineStep step) {
        try {
            if (step.ordinal() <= PipelineStep.DOCLING.ordinal()) {
                Files.deleteIfExists(path(documentId, versionId, NORMALIZED));
            }
            if (step.ordinal() <= PipelineStep.CHUNKING.ordinal()) {
                Files.deleteIfExists(path(documentId, versionId, CHUNKS));
            }
            if (step.ordinal() <= PipelineStep.EMBEDDING.ordinal()) {
                Files.deleteIfExists(path(documentId, versionId, VECTORS));
                Files.deleteIfExists(path(documentId, versionId, VECTOR_META));
            }
        } catch (Exception e) {
            throw artifactError("清理中间产物失败", e);
        }
    }

    public void deleteVersion(String documentId, String versionId) {
        Path directory = versionDirectory(documentId, versionId);
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (Exception e) {
            throw artifactError("清理版本目录失败", e);
        }
    }

    private DocumentChunk rebind(DocumentChunk chunk, String documentId, String versionId) {
        return new DocumentChunk(
                versionId + "-" + chunk.ordinal(), documentId, versionId, chunk.ordinal(),
                chunk.fileName(), chunk.content(), chunk.title(), chunk.sectionPath(),
                chunk.pageNumber(), chunk.pageStart(), chunk.pageEnd(), chunk.sourceBlockIds(),
                chunk.sheetName(), chunk.blockTypes(), chunk.estimatedTokens(),
                chunk.metadata());
    }

    private void copy(String documentId, String sourceVersion, String targetVersion, String name) {
        try {
            Path target = path(documentId, targetVersion, name);
            Files.createDirectories(target.getParent());
            Files.copy(path(documentId, sourceVersion, name), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw artifactError("继承中间产物失败: " + name, e);
        }
    }

    private Path path(String documentId, String versionId, String name) {
        return versionDirectory(documentId, versionId).resolve(name).normalize();
    }

    private Path versionDirectory(String documentId, String versionId) {
        Path directory = root.resolve(documentId).resolve("versions").resolve(versionId).normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("非法产物路径");
        }
        return directory;
    }

    private void writeJson(Path target, Object value) {
        try {
            Files.createDirectories(target.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), value);
        } catch (Exception e) {
            throw artifactError("保存 JSON 产物失败", e);
        }
    }

    private <T> T readJson(Path source, Class<T> type) {
        try {
            return mapper.readValue(source.toFile(), type);
        } catch (Exception e) {
            throw artifactError("读取 JSON 产物失败", e);
        }
    }

    private void deleteQuietly(Path value) {
        try {
            Files.deleteIfExists(value);
        } catch (Exception ignored) {
            // Best effort during cleanup.
        }
    }

    private IllegalStateException artifactError(String message, Exception cause) {
        return new IllegalStateException(message + ": " + cause.getMessage(), cause);
    }

    public record EmbeddingMetadata(
            String model, int count, int dimension, LocalDateTime createdAt) {
    }

    public record EmbeddingArtifact(EmbeddingMetadata metadata, List<float[]> vectors) {
    }
}
