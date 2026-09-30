package com.example.indexing.retrieval;

import com.example.indexing.config.RagProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.scoring.ScoringModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JinaReranker implements Reranker {
    private static final Logger log = LoggerFactory.getLogger(JinaReranker.class);
    private final ScoringModel model;
    private final int limit;

    public JinaReranker(ScoringModel model, RagProperties p) {
        this.model = model;
        this.limit = p.retrieval().rerankTopK();
    }

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<TextSegment> segments =
                candidates.stream().map(c -> TextSegment.from(c.chunk().content())).toList();
        List<Double> scores;
        try {
            scores = model.scoreAll(segments, query).content();
        } catch (RateLimitException error) {
            log.warn(
                    "Jina rerank rate limited; using RRF order fallback: candidates={}, queryLength={}, message={}",
                    candidates.size(),
                    query == null ? 0 : query.length(),
                    error.getMessage());
            return fallback(candidates);
        }
        List<RetrievalCandidate> result = new ArrayList<>();
        if (scores.size() != candidates.size()) {
            throw new IllegalStateException("Jina 返回分数数量与候选数量不一致");
        }
        for (int i = 0; i < candidates.size(); i++)
            result.add(candidates.get(i).withRerank(scores.get(i)));
        return result.stream()
                .sorted(Comparator.comparing(RetrievalCandidate::rerankScore).reversed())
                .limit(limit)
                .toList();
    }

    private List<RetrievalCandidate> fallback(List<RetrievalCandidate> candidates) {
        return candidates.stream()
                .limit(limit)
                .map(candidate -> {
                    Map<String, Object> metadata = new LinkedHashMap<>(candidate.metadata());
                    metadata.put("rerankFallback", "JINA_RATE_LIMIT");
                    return new RetrievalCandidate(
                            candidate.chunk(),
                            candidate.source(),
                            candidate.originalRank(),
                            candidate.originalScore(),
                            candidate.rrfScore(),
                            null,
                            candidate.matchedEntities(),
                            candidate.graphPath(),
                            Map.copyOf(metadata));
                })
                .toList();
    }
}
