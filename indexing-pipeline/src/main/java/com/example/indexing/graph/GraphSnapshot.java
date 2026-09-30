package com.example.indexing.graph;

import java.util.List;

public record GraphSnapshot(List<Node> nodes, List<Edge> edges, boolean truncated) {
    public record Node(String id, String name, String type) {
    }

    public record Edge(String source, String target, String relation, String evidence) {
    }
}
