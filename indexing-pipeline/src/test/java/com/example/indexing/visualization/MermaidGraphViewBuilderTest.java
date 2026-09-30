package com.example.indexing.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.indexing.graph.GraphSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class MermaidGraphViewBuilderTest {
  @Test
  void usesSafeInternalIdsAndEscapesLabels() {
    var graph =
        new GraphSnapshot(
            List.of(
                new GraphSnapshot.Node("id-1", "客户\"战略", "type"),
                new GraphSnapshot.Node("id-2", "洞察", "type")),
            List.of(new GraphSnapshot.Edge("id-1", "id-2", "contains", "e")),
            false);
    String result = new MermaidGraphViewBuilder().toMermaid(graph);
    assertThat(result).contains("N0", "客户\\\"战略", "contains").doesNotContain("id-1[");
  }
}
