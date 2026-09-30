package com.example.indexing.retrieval;

import java.util.List;

public interface QueryUnderstandingService {
    QueryPlan understand(String question, List<String> documentIds);
}
