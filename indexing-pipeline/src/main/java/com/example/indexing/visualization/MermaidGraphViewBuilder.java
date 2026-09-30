package com.example.indexing.visualization;

import com.example.indexing.graph.GraphSnapshot;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class MermaidGraphViewBuilder implements GraphViewBuilder {
    @Override
    public String toMermaid(GraphSnapshot graph) {
        StringBuilder out = new StringBuilder("graph LR\n");
        Map<String, String> ids = new HashMap<>();
        int i = 0;
        for (GraphSnapshot.Node node : graph.nodes()) {
            String id = "N" + i++;
            ids.put(node.id(), id);
            out.append("  ").append(id).append("[\"").append(escape(node.name())).append("\"]\n");
        }
        for (GraphSnapshot.Edge edge : graph.edges())
            if (ids.containsKey(edge.source()) && ids.containsKey(edge.target()))
                out.append("  ")
                        .append(ids.get(edge.source()))
                        .append(" -- \"")
                        .append(escape(edge.relation()))
                        .append("\" --> ")
                        .append(ids.get(edge.target()))
                        .append("\n");
        if (graph.truncated()) {
            out.append("  TRUNCATED[\"图谱已截断\"]\n");
        }
        return out.toString();
    }

    private String escape(String value) {
        return value == null
                ? ""
                : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("[", "(")
                .replace("]", ")");
    }
}
