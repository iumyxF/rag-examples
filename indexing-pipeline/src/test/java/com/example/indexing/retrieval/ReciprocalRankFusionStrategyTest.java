package com.example.indexing.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.indexing.chunking.DocumentChunk;
import com.example.indexing.config.RagProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionStrategyTest {
  @Test
  void rewardsCandidatesFoundByMultipleRetrievers() {
    var p =
        new RagProperties(
            new RagProperties.Storage("x"),
            new RagProperties.Docling("x", "x", "", Duration.ZERO, "tokenizer"),
            new RagProperties.Model("x", "x", "x", Duration.ofSeconds(30), 0, false, 4096),
            new RagProperties.Embedding("x", "x", "x", 3),
            new RagProperties.Jina("x", "x", Duration.ZERO),
            new RagProperties.Chunking(10, 2),
            new RagProperties.Retrieval(20, 20, 20, 60, 30, 10),
            new RagProperties.Graph(2, 50, 100, .35),
            new RagProperties.Context(6000));
    var fusion = new ReciprocalRankFusionStrategy(p);
    var a = candidate("a", "bm25", 1);
    var b = candidate("b", "bm25", 2);
    var a2 = candidate("a", "vector", 2);
    var result = fusion.fuse(Map.of("bm25", List.of(a, b), "vector", List.of(a2)));
    assertThat(result).extracting(c -> c.chunk().id()).containsExactly("a", "b");
    assertThat(result.getFirst().rrfScore()).isGreaterThan(result.get(1).rrfScore());
  }

  private RetrievalCandidate candidate(String id, String source, int rank) {
    var chunk =
        new DocumentChunk(id, "d", "v", rank, "f", id, "", "", null, null, List.of(), 1, Map.of());
    return new RetrievalCandidate(chunk, source, rank, 1, 0, null, List.of(), List.of(), Map.of());
  }
}
