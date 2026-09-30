package com.example.indexing.retrieval;

import com.example.indexing.config.RagProperties;
import com.example.indexing.embedding.EmbeddingService;
import com.example.indexing.index.ChunkIndexRepository;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class VectorRetriever implements CandidateRetriever {
    private final ChunkIndexRepository index;
    private final EmbeddingService embeddings;
    private final int topK;

    public VectorRetriever(ChunkIndexRepository index, EmbeddingService embeddings, RagProperties p) {
        this.index = index;
        this.embeddings = embeddings;
        this.topK = p.retrieval().vectorTopK();
    }

    @Override
    public String name() {
        return "vector";
    }

    @Override
    public List<RetrievalCandidate> retrieve(QueryPlan plan, Map<String, String> versions) {
        return index.searchVector(embeddings.embed(plan.rewrittenQuery()), versions, topK);
    }
}
