package com.example.indexing.retrieval;

import java.util.List;
import java.util.Map;

public interface FusionStrategy {
    List<RetrievalCandidate> fuse(Map<String, List<RetrievalCandidate>> results);
}
