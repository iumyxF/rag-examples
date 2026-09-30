package com.example.indexing.retrieval;

import com.example.indexing.config.RagProperties;
import com.example.indexing.graph.GraphRepository;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class GraphRetriever implements CandidateRetriever {
    private final GraphRepository graph;
    private final RagProperties properties;

    public GraphRetriever(GraphRepository graph, RagProperties properties) {
        this.graph = graph;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "graph";
    }

    @Override
    public List<RetrievalCandidate> retrieve(QueryPlan plan, Map<String, String> versions) {
        return graph.search(
                plan,
                versions,
                properties.retrieval().graphTopK(),
                properties.graph().maxNodes(),
                properties.graph().maxEdges());
    }
}
