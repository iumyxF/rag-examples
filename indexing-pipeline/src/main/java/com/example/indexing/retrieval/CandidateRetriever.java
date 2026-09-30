package com.example.indexing.retrieval;

import java.util.List;
import java.util.Map;

public interface CandidateRetriever {
    String name();

    List<RetrievalCandidate> retrieve(QueryPlan plan, Map<String, String> activeVersions);
}
