package com.example.indexing.document;

import com.example.indexing.chunking.DocumentChunk;
import com.example.indexing.chunking.DocumentChunker;
import com.example.indexing.common.PipelineBusyException;
import com.example.indexing.common.PipelineException;
import com.example.indexing.config.RagProperties;
import com.example.indexing.document.persistence.DocumentEntity;
import com.example.indexing.document.persistence.DocumentVersionEntity;
import com.example.indexing.document.persistence.PipelineStepEntity;
import com.example.indexing.embedding.EmbeddingService;
import com.example.indexing.graph.GraphExtractor;
import com.example.indexing.graph.GraphRepository;
import com.example.indexing.index.ChunkIndexRepository;
import com.example.indexing.parsing.DocumentParserPort;
import com.example.indexing.parsing.NormalizedDocument;
import com.example.indexing.visualization.GraphViewBuilder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentIndexingService {
    private static final Logger log = LoggerFactory.getLogger(DocumentIndexingService.class);
    private static final Set<String> EXTENSIONS = Set.of("pdf", "docx", "xlsx", "md", "markdown");
    private final DocumentRepository documents;
    private final DocumentParserPort parser;
    private final DocumentChunker chunker;
    private final EmbeddingService embeddings;
    private final ChunkIndexRepository index;
    private final GraphExtractor graphExtractor;
    private final GraphRepository graph;
    private final GraphViewBuilder graphView;
    private final PipelineArtifactStore artifacts;
    private final TransactionTemplate transaction;
    private final RagProperties properties;
    private final Path uploadRoot;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public DocumentIndexingService(
            DocumentRepository documents, DocumentParserPort parser, DocumentChunker chunker,
            EmbeddingService embeddings, ChunkIndexRepository index, GraphExtractor graphExtractor,
            GraphRepository graph, GraphViewBuilder graphView, PipelineArtifactStore artifacts,
            TransactionTemplate transaction,
            RagProperties properties) {
        this.documents = documents;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.index = index;
        this.graphExtractor = graphExtractor;
        this.graph = graph;
        this.graphView = graphView;
        this.artifacts = artifacts;
        this.transaction = transaction;
        this.properties = properties;
        this.uploadRoot = Path.of(properties.storage().uploadDirectory()).toAbsolutePath().normalize();
    }

    public UploadResult upload(MultipartFile file) {
        if (file.isEmpty()) throw new IllegalArgumentException("上传文件不能为空");
        String fileName = Path.of(file.getOriginalFilename() == null ? "document" : file.getOriginalFilename())
                .getFileName().toString();
        validateExtension(fileName);
        try {
            byte[] bytes = file.getBytes();
            String hash = hash(bytes);
            var duplicate = documents.findByHash(hash);
            if (duplicate.isPresent()) return new UploadResult(detail(duplicate.get()), true);
            DocumentEntity document = newDocument(fileName, hash, file.getContentType());
            document.id = UUID.randomUUID().toString();
            String versionId = UUID.randomUUID().toString();
            document.storagePath = storeSource(document.id, fileName, bytes).toString();
            document.status = "WORKING";
            document.workingVersionId = versionId;
            DocumentVersionEntity version = new DocumentVersionEntity();
            version.id = versionId;
            version.documentId = document.id;
            version.status = "WORKING";
            transaction.executeWithoutResult(status -> {
                documents.insert(document);
                documents.insertVersion(version);
                createSteps(versionId);
            });
            return new UploadResult(detail(document), false);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new PipelineException("upload", "文档上传失败: " + e.getMessage(), e);
        }
    }

    public DocumentDetailView execute(String documentId, String stepName) {
        PipelineStep target = PipelineStep.executable(stepName);
        ReentrantLock lock = acquire(documentId);
        try {
            DocumentEntity document = getEntity(documentId);
            DocumentVersionEntity version = resolveWorkingVersion(document, target);
            requirePredecessor(version.id, target);
            invalidate(document, version, target);
            PipelineStepEntity state = step(version.id, target);
            start(state);
            long started = System.nanoTime();
            try {
                StepResult result = run(document, version, target);
                succeed(state, result, started);
                return detail(getEntity(documentId));
            } catch (Exception failure) {
                fail(state, failure, started);
                if (failure instanceof PipelineException pipeline) {
                    throw pipeline;
                }
                throw new PipelineException(target.code(), label(target) + "失败: " + failure.getMessage(), failure);
            }
        } finally {
            release(documentId, lock);
        }
    }

    public Object preview(String documentId, String stepName, int offset, int limit) {
        PipelineStep target = PipelineStep.executable(stepName);
        DocumentEntity document = getEntity(documentId);
        String versionId = document.workingVersionId != null ? document.workingVersionId : document.activeVersionId;
        if (versionId == null) throw new IllegalArgumentException("文档尚无可查看版本");
        requireSucceeded(versionId, target);
        int from = Math.max(0, offset);
        int size = Math.max(1, Math.min(limit, 100));
        return switch (target) {
            case DOCLING -> previewNormalized(document.id, versionId, from, size);
            case CHUNKING -> page(artifacts.readChunks(document.id, versionId), from, size);
            case EMBEDDING -> previewEmbeddings(document.id, versionId, from, size);
            case ELASTICSEARCH -> Map.of("total", index.countVersion(versionId), "offset", from,
                    "limit", size, "items", index.findByVersion(versionId, from, size));
            case GRAPH -> {
                var snapshot = graph.documentGraph(document.id, versionId,
                        properties.graph().maxNodes(), properties.graph().maxEdges());
                yield Map.of("graph", snapshot, "mermaid", graphView.toMermaid(snapshot));
            }
            case ACTIVATE -> Map.of("activeVersionId", document.activeVersionId);
            case UPLOAD -> throw new IllegalArgumentException("上传产物在文档详情中查看");
        };
    }

    public void deleteWorkingVersion(String documentId) {
        ReentrantLock lock = acquire(documentId);
        try {
            DocumentEntity document = getEntity(documentId);
            if (document.workingVersionId == null) throw new IllegalArgumentException("文档没有工作版本");
            if (document.activeVersionId == null)
                throw new IllegalArgumentException("首次版本不能单独删除，请删除整个文档");
            String versionId = document.workingVersionId;
            cleanupExternalVersion(document.id, versionId);
            documents.deleteVersion(versionId);
            document.workingVersionId = null;
            document.status = document.activeVersionId == null ? "UPLOADED" : "ACTIVE";
            documents.update(document);
        } finally {
            release(documentId, lock);
        }
    }

    public List<DocumentDetailView> list() {
        return documents.findAll().stream().map(this::detail).toList();
    }

    public DocumentDetailView get(String id) {
        return detail(getEntity(id));
    }

    public Map<String, String> activeVersions(List<String> ids) {
        return documents.activeVersions(ids);
    }

    public Map<String, String> contentHashes(List<String> ids) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String id : ids) {
            documents.findById(id).ifPresent(document -> result.put(id, document.contentHash));
        }
        return result;
    }

    public void delete(String id) {
        ReentrantLock lock = acquire(id);
        try {
            getEntity(id);
            documents.findVersions(id).forEach(version -> cleanupExternalVersion(id, version.id));
            documents.delete(id);
            graph.deleteOrphanEntities();
            deleteDocumentDirectory(id);
        } finally {
            release(id, lock);
        }
    }

    private StepResult run(DocumentEntity document, DocumentVersionEntity version, PipelineStep target)
            throws Exception {
        return switch (target) {
            case DOCLING -> runDocling(document, version);
            case CHUNKING -> runChunking(document, version);
            case EMBEDDING -> runEmbedding(document, version);
            case ELASTICSEARCH -> runElasticsearch(document, version);
            case GRAPH -> runGraph(document, version);
            case ACTIVATE -> runActivate(document, version);
            case UPLOAD -> throw new IllegalArgumentException("上传步骤不能重复执行");
        };
    }

    private StepResult runDocling(DocumentEntity document, DocumentVersionEntity version) throws Exception {
        try (InputStream input = Files.newInputStream(Path.of(document.storagePath))) {
            NormalizedDocument value = parser.parse(input, document.fileName, document.mediaType);
            artifacts.writeNormalized(document.id, version.id, value);
            return new StepResult(value.blocks().size(), "Docling 返回 %d 个 blocks".formatted(value.blocks().size()));
        }
    }

    private StepResult runChunking(DocumentEntity document, DocumentVersionEntity version) {
        List<DocumentChunk> chunks = chunker.chunk(document.id, version.id,
                artifacts.readNormalized(document.id, version.id));
        if (chunks.isEmpty()) throw new IllegalStateException("Docling 未返回可索引内容");
        artifacts.writeChunks(document.id, version.id, chunks);
        version.chunkCount = chunks.size();
        documents.updateVersion(version);
        return new StepResult(chunks.size(), "生成 %d 个 chunks".formatted(chunks.size()));
    }

    private StepResult runEmbedding(DocumentEntity document, DocumentVersionEntity version) {
        List<DocumentChunk> chunks = artifacts.readChunks(document.id, version.id);
        List<float[]> vectors = embeddings.embedAll(chunks.stream().map(DocumentChunk::content).toList());
        validateVectors(chunks, vectors);
        artifacts.writeEmbeddings(document.id, version.id, properties.embedding().modelName(), vectors);
        return new StepResult(vectors.size(), "%d 个 %d 维向量 · %s".formatted(vectors.size(),
                properties.embedding().dimension(), properties.embedding().modelName()));
    }

    private StepResult runElasticsearch(DocumentEntity document, DocumentVersionEntity version) {
        List<DocumentChunk> chunks = artifacts.readChunks(document.id, version.id);
        List<float[]> vectors = artifacts.readEmbeddings(document.id, version.id).vectors();
        validateVectors(chunks, vectors);
        index.deleteVersion(version.id);
        index.index(chunks, vectors);
        index.refresh();
        long count = index.countVersion(version.id);
        if (count != chunks.size()) throw new IllegalStateException("Elasticsearch 写入数量不一致");
        return new StepResult(Math.toIntExact(count), "Elasticsearch 已写入 %d 条".formatted(count));
    }

    private StepResult runGraph(DocumentEntity document, DocumentVersionEntity version) {
        graph.deleteVersion(version.id);
        int entities = 0, relations = 0;
        List<DocumentChunk> chunks = artifacts.readChunks(document.id, version.id);
        log.info("开始图谱抽取: documentId={}, versionId={}, chunks={}", document.id, version.id, chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            log.info("正在抽取图谱 chunk {}/{}: chunkId={}", i + 1, chunks.size(), chunk.id());
            var stats = graph.save(document.id, version.id, chunk, graphExtractor.extract(chunk));
            entities += stats.entities();
            relations += stats.relations();
            log.info(
                    "图谱 chunk {}/{} 完成: chunkId={}, entities={}, relations={}",
                    i + 1, chunks.size(), chunk.id(), stats.entities(), stats.relations());
        }
        version.entityCount = entities;
        version.relationCount = relations;
        documents.updateVersion(version);
        return new StepResult(entities + relations, "%d 个实体 · %d 条关系".formatted(entities, relations));
    }

    private StepResult runActivate(DocumentEntity document, DocumentVersionEntity version) {
        validateActivation(document, version);
        String previous = document.activeVersionId;
        transaction.executeWithoutResult(status -> {
            if (previous != null) documents.findVersion(previous).ifPresent(old -> {
                old.status = "SUPERSEDED";
                documents.updateVersion(old);
            });
            version.status = "ACTIVE";
            version.activatedAt = LocalDateTime.now();
            documents.updateVersion(version);
            document.activeVersionId = version.id;
            document.workingVersionId = null;
            document.status = "ACTIVE";
            documents.update(document);
        });
        if (previous != null && !previous.equals(version.id)) {
            try {
                cleanupExternalVersion(document.id, previous);
            } catch (Exception ignored) {
            }
        }
        return new StepResult(1, "版本已激活");
    }

    private DocumentVersionEntity resolveWorkingVersion(DocumentEntity document, PipelineStep target) {
        if (document.workingVersionId != null) return version(document.workingVersionId);
        if (target == PipelineStep.ACTIVATE) throw new IllegalArgumentException("没有可激活的工作版本");
        if (document.activeVersionId == null) throw new IllegalArgumentException("文档没有工作版本或激活版本");
        DocumentVersionEntity source = version(document.activeVersionId);
        DocumentVersionEntity derived = new DocumentVersionEntity();
        derived.id = UUID.randomUUID().toString();
        derived.documentId = document.id;
        derived.status = "WORKING";
        derived.sourceVersionId = source.id;
        derived.chunkCount = source.chunkCount;
        derived.entityCount = source.entityCount;
        derived.relationCount = source.relationCount;
        documents.insertVersion(derived);
        createSteps(derived.id);
        document.workingVersionId = derived.id;
        document.status = "ACTIVE_WITH_WORKING";
        documents.update(document);
        artifacts.inheritBefore(document.id, source.id, derived.id, target);
        inheritStepStates(source.id, derived.id, target);
        if (target == PipelineStep.GRAPH) {
            StepResult result = runElasticsearch(document, derived);
            PipelineStepEntity es = step(derived.id, PipelineStep.ELASTICSEARCH);
            es.status = "SUCCEEDED";
            es.itemCount = result.count();
            es.outputSummary = result.summary() + "（继承重建）";
            es.finishedAt = LocalDateTime.now();
            documents.updateStep(es);
        }
        return derived;
    }

    private void inheritStepStates(String sourceId, String targetId, PipelineStep target) {
        for (PipelineStep candidate : PipelineStep.values()) {
            if (candidate.ordinal() >= target.ordinal() || candidate == PipelineStep.ELASTICSEARCH) continue;
            PipelineStepEntity source = step(sourceId, candidate);
            if (!"SUCCEEDED".equals(source.status))
                throw new IllegalStateException("激活版本缺少成功的上游步骤: " + candidate.code());
            PipelineStepEntity inherited = step(targetId, candidate);
            inherited.status = "SUCCEEDED";
            inherited.itemCount = source.itemCount;
            inherited.outputSummary = (source.outputSummary == null ? "" : source.outputSummary) + "（继承）";
            inherited.finishedAt = LocalDateTime.now();
            documents.updateStep(inherited);
        }
    }

    private void invalidate(DocumentEntity document, DocumentVersionEntity version, PipelineStep target) {
        if (target == PipelineStep.ACTIVATE) {
            reset(step(version.id, target), "NOT_RUN");
            return;
        }
        artifacts.deleteFrom(document.id, version.id, target);
        if (target.ordinal() <= PipelineStep.ELASTICSEARCH.ordinal()) index.deleteVersion(version.id);
        if (target.ordinal() <= PipelineStep.GRAPH.ordinal()) {
            graph.deleteVersion(version.id);
            graph.deleteOrphanEntities();
        }
        for (PipelineStep candidate : PipelineStep.values()) {
            if (candidate.ordinal() >= target.ordinal())
                reset(step(version.id, candidate), candidate == target ? "NOT_RUN" : "INVALIDATED");
        }
        if (target.ordinal() <= PipelineStep.CHUNKING.ordinal()) version.chunkCount = 0;
        if (target.ordinal() <= PipelineStep.GRAPH.ordinal()) {
            version.entityCount = 0;
            version.relationCount = 0;
        }
        documents.updateVersion(version);
    }

    private void reset(PipelineStepEntity state, String status) {
        state.status = status;
        state.startedAt = null;
        state.finishedAt = null;
        state.durationMs = null;
        state.itemCount = 0;
        state.outputSummary = null;
        state.errorMessage = null;
        documents.updateStep(state);
    }

    private void validateActivation(DocumentEntity document, DocumentVersionEntity version) {
        for (PipelineStep required : List.of(PipelineStep.UPLOAD, PipelineStep.DOCLING,
                PipelineStep.CHUNKING, PipelineStep.EMBEDDING, PipelineStep.ELASTICSEARCH,
                PipelineStep.GRAPH))
            requireSucceeded(version.id, required);
        List<DocumentChunk> chunks = artifacts.readChunks(document.id, version.id);
        if (chunks.isEmpty() || chunks.size() != version.chunkCount)
            throw new IllegalStateException("Chunk 产物为空或数量不一致");
        validateVectors(chunks, artifacts.readEmbeddings(document.id, version.id).vectors());
        if (index.countVersion(version.id) != chunks.size())
            throw new IllegalStateException("Elasticsearch 文档数量与 Chunk 不一致");
    }

    private void validateVectors(List<DocumentChunk> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) throw new IllegalStateException("Embedding 数量与 Chunk 不一致");
        int dimension = properties.embedding().dimension();
        if (vectors.stream().anyMatch(vector -> vector.length != dimension))
            throw new IllegalStateException("Embedding 维度与配置不一致，应为 " + dimension);
    }

    private void requirePredecessor(String versionId, PipelineStep target) {
        requireSucceeded(versionId, PipelineStep.values()[target.ordinal() - 1]);
    }

    private void requireSucceeded(String versionId, PipelineStep required) {
        if (!"SUCCEEDED".equals(step(versionId, required).status))
            throw new IllegalArgumentException("前置步骤尚未成功: " + required.code());
    }

    private void createSteps(String versionId) {
        for (PipelineStep pipelineStep : PipelineStep.values()) {
            PipelineStepEntity state = new PipelineStepEntity();
            state.versionId = versionId;
            state.stepCode = pipelineStep.code();
            state.status = pipelineStep == PipelineStep.UPLOAD ? "SUCCEEDED" : "NOT_RUN";
            if (pipelineStep == PipelineStep.UPLOAD) {
                state.finishedAt = LocalDateTime.now();
                state.itemCount = 1;
                state.outputSummary = "原文件已保存";
            }
            documents.insertStep(state);
        }
    }

    private void start(PipelineStepEntity state) {
        state.status = "RUNNING";
        state.startedAt = LocalDateTime.now();
        state.finishedAt = null;
        state.errorMessage = null;
        documents.updateStep(state);
    }

    private void succeed(PipelineStepEntity state, StepResult result, long started) {
        state.status = "SUCCEEDED";
        state.finishedAt = LocalDateTime.now();
        state.durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        state.itemCount = result.count();
        state.outputSummary = result.summary();
        state.errorMessage = null;
        documents.updateStep(state);
    }

    private void fail(PipelineStepEntity state, Exception failure, long started) {
        state.status = "FAILED";
        state.finishedAt = LocalDateTime.now();
        state.durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        state.errorMessage = message.length() > 8000 ? message.substring(0, 8000) : message;
        documents.updateStep(state);
    }

    private Object previewNormalized(String documentId, String versionId, int offset, int limit) {
        NormalizedDocument value = artifacts.readNormalized(documentId, versionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileName", value.fileName());
        result.put("mediaType", value.mediaType());
        result.put("metadata", value.metadata());
        result.putAll(page(value.blocks(), offset, limit));
        return result;
    }

    private Object previewEmbeddings(String documentId, String versionId, int offset, int limit) {
        var value = artifacts.readEmbeddings(documentId, versionId);
        List<Map<String, Object>> samples = new ArrayList<>();
        int end = Math.min(value.vectors().size(), offset + limit);
        for (int i = Math.min(offset, end); i < end; i++) {
            float[] vector = value.vectors().get(i);
            samples.add(Map.of("ordinal", i, "dimension", vector.length,
                    "preview", Arrays.copyOf(vector, Math.min(12, vector.length))));
        }
        return Map.of("metadata", value.metadata(), "offset", offset, "limit", limit, "items", samples);
    }

    private Map<String, Object> page(List<?> values, int offset, int limit) {
        int from = Math.min(offset, values.size());
        int to = Math.min(values.size(), from + limit);
        return Map.of("total", values.size(), "offset", offset, "limit", limit,
                "items", values.subList(from, to));
    }

    private DocumentDetailView detail(DocumentEntity document) {
        return new DocumentDetailView(DocumentView.from(document), versionView(document.activeVersionId),
                versionView(document.workingVersionId));
    }

    private VersionView versionView(String versionId) {
        if (versionId == null) return null;
        DocumentVersionEntity value = version(versionId);
        return VersionView.from(value,
                documents.findSteps(versionId).stream().map(PipelineStepView::from).toList());
    }

    private PipelineStepEntity step(String versionId, PipelineStep target) {
        return documents.findStep(versionId, target.code())
                .orElseThrow(() -> new IllegalStateException("步骤状态不存在: " + target.code()));
    }

    private DocumentVersionEntity version(String id) {
        return documents.findVersion(id)
                .orElseThrow(() -> new IllegalArgumentException("文档版本不存在: " + id));
    }

    private DocumentEntity getEntity(String id) {
        return documents.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + id));
    }

    private DocumentEntity newDocument(String name, String hash, String type) {
        DocumentEntity value = new DocumentEntity();
        value.fileName = name;
        value.contentHash = hash;
        value.mediaType = type;
        return value;
    }

    private Path storeSource(String documentId, String name, byte[] bytes) throws Exception {
        Path directory = uploadRoot.resolve(documentId).resolve("source").normalize();
        if (!directory.startsWith(uploadRoot)) throw new IllegalArgumentException("非法存储路径");
        Files.createDirectories(directory);
        Path target = directory.resolve(name).normalize();
        Files.write(target, bytes);
        return target;
    }

    private void cleanupExternalVersion(String documentId, String versionId) {
        try {
            index.deleteVersion(versionId);
        } finally {
            try {
                graph.deleteVersion(versionId);
                graph.deleteOrphanEntities();
            } finally {
                artifacts.deleteVersion(documentId, versionId);
            }
        }
    }

    private void deleteDocumentDirectory(String documentId) {
        Path directory = uploadRoot.resolve(documentId).normalize();
        if (!directory.startsWith(uploadRoot) || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private ReentrantLock acquire(String documentId) {
        ReentrantLock lock = locks.computeIfAbsent(documentId, ignored -> new ReentrantLock());
        if (!lock.tryLock()) throw new PipelineBusyException("该文档已有步骤正在执行");
        return lock;
    }

    private void release(String documentId, ReentrantLock lock) {
        lock.unlock();
    }

    private void validateExtension(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) throw new IllegalArgumentException("仅支持 PDF、DOCX、XLSX、Markdown");
    }

    private String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String label(PipelineStep step) {
        return switch (step) {
            case DOCLING -> "Docling 解析";
            case CHUNKING -> "Chunk 切分";
            case EMBEDDING -> "Embedding 计算";
            case ELASTICSEARCH -> "Elasticsearch 写入";
            case GRAPH -> "图谱抽取";
            case ACTIVATE -> "版本激活";
            case UPLOAD -> "文件上传";
        };
    }

    private record StepResult(int count, String summary) {
    }
}
