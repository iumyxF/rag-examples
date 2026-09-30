package com.example.indexing.rag.pipeline;

import com.example.indexing.graph.GraphSnapshot;
import com.example.indexing.rag.context.BuiltContext;
import com.example.indexing.retrieval.RetrievalCandidate;
import com.example.indexing.retrieval.QueryPlan;

import java.util.List;
import java.util.Map;

public record EvaluationExecution(
        QueryPlan queryPlan,
        String answer,
        List<BuiltContext.Citation> contextCitations,
        List<Integer> usedCitationIds,
        Map<String, List<RetrievalCandidate>> retrieval,
        GraphSnapshot graphTrace,
        Map<String, String> activeVersions,
        Map<String, String> sourceContentHashes,
        Map<String, Long> timings,
        String citationValidation,
        String finalContext,
        List<String> contextChunkIds) {
}
