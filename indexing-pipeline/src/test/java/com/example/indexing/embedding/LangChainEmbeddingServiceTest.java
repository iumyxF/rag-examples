package com.example.indexing.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.indexing.common.PipelineException;
import com.example.indexing.config.RagProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LangChainEmbeddingServiceTest {

    @Test
    void limitsEachTextEmbeddingBatchToTenItems() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embedAll(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<TextSegment> segments = invocation.getArgument(0);
                            return Response.from(
                                    segments.stream()
                                            .map(segment -> Embedding.from(new float[] {1, 2, 3}))
                                            .toList());
                        });
        LangChainEmbeddingService service = service(model, "text-embedding-v4");

        List<float[]> result =
                service.embedAll(
                        java.util.stream.IntStream.range(0, 21)
                                .mapToObj(i -> "chunk-" + i)
                                .toList());

        assertThat(result).hasSize(21);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> batches = ArgumentCaptor.forClass(List.class);
        verify(model, times(3)).embedAll(batches.capture());
        assertThat(new ArrayList<>(batches.getAllValues()).stream().map(List::size).toList())
                .containsExactly(10, 10, 1);
    }

    @Test
    void explainsUrlErrorsCausedByUsingTheMultimodalModel() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("hello")).thenThrow(new RuntimeException("url error, please check url"));
        LangChainEmbeddingService service = service(model, "qwen3-vl-embedding");

        assertThatThrownBy(() -> service.embed("hello"))
                .isInstanceOf(PipelineException.class)
                .hasMessageContaining("EMBEDDING_MODEL=text-embedding-v4")
                .hasMessageContaining("多模态 Embedding 请求格式");
    }

    private LangChainEmbeddingService service(EmbeddingModel model, String modelName) {
        RagProperties properties = mock(RagProperties.class);
        when(properties.embedding())
                .thenReturn(new RagProperties.Embedding("https://example.test/v1", "key", modelName, 3));
        return new LangChainEmbeddingService(model, properties);
    }
}
