package com.example.indexing.retrieval;

import java.util.List;

public record QueryPlan(
        String originalQuery,
        String rewrittenQuery,
        List<String> keywords,
        List<String> entities,
        String intent,
        int graphDepth,
        List<String> documentIds) {
}
