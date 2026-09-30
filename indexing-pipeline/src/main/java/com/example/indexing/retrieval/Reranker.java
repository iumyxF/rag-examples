package com.example.indexing.retrieval;

import java.util.List;

public interface Reranker {
    List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates);
}
