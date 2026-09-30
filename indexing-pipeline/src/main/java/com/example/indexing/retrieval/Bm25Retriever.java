package com.example.indexing.retrieval;

import com.example.indexing.config.RagProperties;
import com.example.indexing.index.ChunkIndexRepository;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class Bm25Retriever implements CandidateRetriever {
    private final ChunkIndexRepository index;
    private final int topK;

    public Bm25Retriever(ChunkIndexRepository index, RagProperties p) {
        this.index = index;
        this.topK = p.retrieval().bm25TopK();
    }

    @Override
    public String name() {
        return "bm25";
    }

    @Override
    public List<RetrievalCandidate> retrieve(QueryPlan plan, Map<String, String> versions) {
        return index.searchBm25(plan.rewrittenQuery(), versions, topK);
    }
}
