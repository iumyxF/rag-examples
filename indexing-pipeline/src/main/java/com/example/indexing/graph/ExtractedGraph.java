package com.example.indexing.graph;

import java.util.List;

public record ExtractedGraph(List<Node> nodes, List<Edge> edges) {
    public static ExtractedGraph empty() {
        return new ExtractedGraph(List.of(), List.of());
    }

    public record Node(String displayName, String canonicalName, String type, String description) {
    }

    public record Edge(
            String source,
            String target,
            String relation,
            String rawRelation,
            String evidence,
            double confidence) {
    }
}
