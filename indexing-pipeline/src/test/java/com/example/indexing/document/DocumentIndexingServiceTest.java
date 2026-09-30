package com.example.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.indexing.chunking.DocumentChunker;
import com.example.indexing.config.RagProperties;
import com.example.indexing.document.persistence.DocumentEntity;
import com.example.indexing.document.persistence.DocumentVersionEntity;
import com.example.indexing.document.persistence.PipelineStepEntity;
import com.example.indexing.embedding.EmbeddingService;
import com.example.indexing.graph.GraphExtractor;
import com.example.indexing.graph.GraphRepository;
import com.example.indexing.index.ChunkIndexRepository;
import com.example.indexing.parsing.DocumentParserPort;
import com.example.indexing.visualization.GraphViewBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class DocumentIndexingServiceTest {
    @TempDir Path directory;
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentParserPort parser = mock(DocumentParserPort.class);
    private final DocumentChunker chunker = mock(DocumentChunker.class);
    private final EmbeddingService embeddings = mock(EmbeddingService.class);
    private final ChunkIndexRepository index = mock(ChunkIndexRepository.class);
    private final GraphExtractor extractor = mock(GraphExtractor.class);
    private final GraphRepository graph = mock(GraphRepository.class);
    private final GraphViewBuilder graphView = mock(GraphViewBuilder.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final Map<String, DocumentEntity> documentRows = new HashMap<>();
    private final Map<String, DocumentVersionEntity> versionRows = new HashMap<>();
    private final Map<String, PipelineStepEntity> stepRows = new HashMap<>();
    private DocumentIndexingService service;

    @BeforeEach
    void setUp() {
        RagProperties properties = properties();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        PipelineArtifactStore artifacts = new PipelineArtifactStore(properties, mapper);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transaction).executeWithoutResult(any());
        when(documents.findByHash(any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            DocumentEntity value = invocation.getArgument(0); documentRows.put(value.id, value); return null;
        }).when(documents).insert(any());
        doAnswer(invocation -> {
            DocumentVersionEntity value = invocation.getArgument(0); versionRows.put(value.id, value); return null;
        }).when(documents).insertVersion(any());
        doAnswer(invocation -> {
            PipelineStepEntity value = invocation.getArgument(0);
            stepRows.put(value.versionId + ":" + value.stepCode, value); return null;
        }).when(documents).insertStep(any());
        when(documents.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(documentRows.get(invocation.getArgument(0))));
        when(documents.findVersion(any())).thenAnswer(invocation ->
                Optional.ofNullable(versionRows.get(invocation.getArgument(0))));
        when(documents.findStep(any(), any())).thenAnswer(invocation -> Optional.ofNullable(
                stepRows.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))));
        when(documents.findSteps(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            List<PipelineStepEntity> result = new ArrayList<>();
            for (PipelineStep step : PipelineStep.values()) result.add(stepRows.get(id + ":" + step.code()));
            return result;
        });
        service = new DocumentIndexingService(documents, parser, chunker, embeddings, index,
                extractor, graph, graphView, artifacts, transaction, properties);
    }

    @Test
    void uploadOnlyPersistsSourceAndCreatesSevenStepStates() {
        UploadResult result = service.upload(new MockMultipartFile(
                "file", "guide.pdf", "application/pdf", "pdf".getBytes()));

        assertThat(result.existing()).isFalse();
        assertThat(result.document().workingVersion().steps()).hasSize(7);
        assertThat(result.document().workingVersion().steps().getFirst().status()).isEqualTo("SUCCEEDED");
        assertThat(result.document().workingVersion().steps().get(1).status()).isEqualTo("NOT_RUN");
        verify(parser, never()).parse(any(), any(), any());
        verify(embeddings, never()).embedAll(any());
    }

    @Test
    void cannotSkipChunkingAndRunEmbedding() {
        UploadResult result = service.upload(new MockMultipartFile(
                "file", "guide.pdf", "application/pdf", "pdf".getBytes()));
        String id = result.document().document().id();

        assertThatThrownBy(() -> service.execute(id, "embedding"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunking");
        verify(embeddings, never()).embedAll(any());
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
