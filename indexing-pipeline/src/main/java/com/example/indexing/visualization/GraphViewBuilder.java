package com.example.indexing.visualization;

import com.example.indexing.graph.GraphSnapshot;

public interface GraphViewBuilder {
    String toMermaid(GraphSnapshot graph);
}
