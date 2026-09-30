package com.example.indexing.retrieval;

import com.example.indexing.chunking.DocumentChunk;

import java.util.List;
import java.util.Map;

public record RetrievalCandidate(
        DocumentChunk chunk,
        String source,
        int originalRank,
        double originalScore,
        double rrfScore,
        Double rerankScore,
        List<String> matchedEntities,
        List<String> graphPath,
        Map<String, Object> metadata) {
    public RetrievalCandidate withRrf(double score) {
        return new RetrievalCandidate(
                chunk,
                source,
                originalRank,
                originalScore,
                score,
                rerankScore,
                matchedEntities,
                graphPath,
                metadata);
    }

    public RetrievalCandidate withRerank(double score) {
        return new RetrievalCandidate(
                chunk,
                source,
                originalRank,
                originalScore,
                rrfScore,
                score,
                matchedEntities,
                graphPath,
                metadata);
    }
}
