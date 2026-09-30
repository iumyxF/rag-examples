package com.example.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.indexing.chunking.DocumentChunk;
import com.example.indexing.config.RagProperties;
import com.example.indexing.parsing.NormalizedDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineArtifactStoreTest {
    @TempDir Path directory;

    @Test
    void persistsArtifactsAndRebindsChunkVersion() {
        PipelineArtifactStore store = new PipelineArtifactStore(properties(), mapper());
        var normalized = new NormalizedDocument(
                "guide.pdf", "application/pdf",
                List.of(new NormalizedDocument.Block("b1", NormalizedDocument.BlockType.PARAGRAPH,
                        "hello", 1, null, "intro", Map.of())), Map.of("language", "zh"));
        store.writeNormalized("doc", "old", normalized);
        store.writeChunks("doc", "old", List.of(chunk("old")));
        store.writeEmbeddings("doc", "old", "test-model", List.of(new float[] {1, 2, 3}));

        store.inheritBefore("doc", "old", "new", PipelineStep.ELASTICSEARCH);

        assertThat(store.readNormalized("doc", "new").blocks()).hasSize(1);
        assertThat(store.readChunks("doc", "new").getFirst().id()).isEqualTo("new-0");
        assertThat(store.readChunks("doc", "new").getFirst().versionId()).isEqualTo("new");
        assertThat(store.readEmbeddings("doc", "new").vectors().getFirst())
                .containsExactly(1, 2, 3);
    }

    private DocumentChunk chunk(String version) {
        return new DocumentChunk(version + "-0", "doc", version, 0, "guide.pdf", "hello",
                "intro", "intro", 1, null, List.of("PARAGRAPH"), 2, Map.of());
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private RagProperties properties() {
        return new RagProperties(
                new RagProperties.Storage(directory.toString()),
                new RagProperties.Docling("", "", "", Duration.ofSeconds(1), ""),
                new RagProperties.Model("", "", "", Duration.ofSeconds(1), 0, false, 4096),
                new RagProperties.Embedding("", "", "test-model", 3),
                new RagProperties.Jina("", "", Duration.ofSeconds(1)),
                new RagProperties.Chunking(10, 1),
                new RagProperties.Retrieval(1, 1, 1, 1, 1, 1),
                new RagProperties.Graph(1, 10, 10, 0.1),
                new RagProperties.Context(100));
    }
}
