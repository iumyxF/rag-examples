package com.example.indexing.rag.context;

import com.example.indexing.retrieval.RetrievalCandidate;

import java.util.List;

public interface ContextBuilder {
    BuiltContext build(List<RetrievalCandidate> candidates);
}
