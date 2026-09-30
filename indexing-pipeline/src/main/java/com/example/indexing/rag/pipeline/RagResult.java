package com.example.indexing.rag.pipeline;

import com.example.indexing.rag.context.BuiltContext;

import java.util.List;

public record RagResult(
        String answer, List<BuiltContext.Citation> citations, String localGraph, RagTrace trace) {
}
