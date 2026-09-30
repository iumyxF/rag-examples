package com.example.indexing.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.indexing.chunking.DocumentChunk;
import com.example.indexing.config.RagProperties;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JinaRerankerTest {
    private final ScoringModel model = mock(ScoringModel.class);

    @Test
    void reranksCandidatesByJinaScore() {
        when(model.scoreAll(anyList(), eq("query"))).thenReturn(Response.from(List.of(0.1, 0.9)));
        JinaReranker reranker = new JinaReranker(model, properties(2));

        List<RetrievalCandidate> result =
                reranker.rerank("query", List.of(candidate("first", 0.2), candidate("second", 0.1)));

        assertThat(result).extracting(value -> value.chunk().id()).containsExactly("second", "first");
        assertThat(result).extracting(RetrievalCandidate::rerankScore).containsExactly(0.9, 0.1);
    }

    @Test
    void fallsBackToRrfOrderWhenJinaRateLimitIsExceeded() {
        when(model.scoreAll(anyList(), eq("query")))
                .thenThrow(new RateLimitException("RATE_TOKEN_LIMIT_EXCEEDED"));
        JinaReranker reranker = new JinaReranker(model, properties(2));

        List<RetrievalCandidate> result = reranker.rerank(
                "query",
                List.of(candidate("first", 0.3), candidate("second", 0.2), candidate("third", 0.1)));

        assertThat(result).extracting(value -> value.chunk().id()).containsExactly("first", "second");
        assertThat(result).allSatisfy(candidate -> {
            assertThat(candidate.rerankScore()).isNull();
            assertThat(candidate.metadata()).containsEntry("rerankFallback", "JINA_RATE_LIMIT");
        });
    }

    private RetrievalCandidate candidate(String id, double rrfScore) {
        DocumentChunk chunk =
                new DocumentChunk(id, "document", "version", 0, "guide.pdf", id, "", "", null,
                        null, List.of(), 1, Map.of());
        return new RetrievalCandidate(
                chunk, "fused", 1, 0, rrfScore, null, List.of(), List.of(), Map.of());
    }

    private RagProperties properties(int rerankTopK) {
        return new RagProperties(
                new RagProperties.Storage("x"),
                new RagProperties.Docling("x", "x", "", Duration.ZERO, "tokenizer"),
                new RagProperties.Model("x", "x", "x", Duration.ofSeconds(30), 0, false, 4096),
                new RagProperties.Embedding("x", "x", "x", 3),
                new RagProperties.Jina("x", "x", Duration.ZERO),
                new RagProperties.Chunking(10, 2),
                new RagProperties.Retrieval(20, 20, 20, 60, 30, rerankTopK),
                new RagProperties.Graph(2, 50, 100, .35),
                new RagProperties.Context(6000));
    }
}
