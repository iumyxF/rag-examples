package com.example.indexing.retrieval;

import com.example.indexing.config.RagProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class ReciprocalRankFusionStrategy implements FusionStrategy {
    private final int k;
    private final int limit;

    public ReciprocalRankFusionStrategy(RagProperties p) {
        this.k = p.retrieval().rrfK();
        this.limit = p.retrieval().fusedTopK();
    }

    @Override
    public List<RetrievalCandidate> fuse(Map<String, List<RetrievalCandidate>> results) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, RetrievalCandidate> representative = new LinkedHashMap<>();
        results.forEach(
                (source, list) -> {
                    for (int i = 0; i < list.size(); i++) {
                        RetrievalCandidate c = list.get(i);
                        // RRF combines ranks only, so scores from different retrievers need no normalization.
                        scores.merge(c.chunk().id(), 1.0 / (k + i + 1), Double::sum);
                        representative.putIfAbsent(c.chunk().id(), c);
                    }
                });
        List<RetrievalCandidate> fused = new ArrayList<>();
        scores.forEach((id, score) -> fused.add(representative.get(id).withRrf(score)));
        return fused.stream()
                .sorted(Comparator.comparingDouble(RetrievalCandidate::rrfScore).reversed())
                .limit(limit)
                .toList();
    }
}
