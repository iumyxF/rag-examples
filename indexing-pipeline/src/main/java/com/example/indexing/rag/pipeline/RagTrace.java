package com.example.indexing.rag.pipeline;

import com.example.indexing.retrieval.QueryPlan;
import com.example.indexing.retrieval.RetrievalCandidate;

import java.util.List;
import java.util.Map;

public record RagTrace(
        QueryPlan queryPlan,
        Map<String, List<RetrievalCandidate>> retrieval,
        List<RetrievalCandidate> fused,
        List<RetrievalCandidate> reranked,
        String finalContext,
        String citationValidation) {
}
