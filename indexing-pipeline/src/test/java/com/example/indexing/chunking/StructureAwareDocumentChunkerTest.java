package com.example.indexing.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.indexing.config.RagProperties;
import com.example.indexing.parsing.NormalizedDocument;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructureAwareDocumentChunkerTest {
  @Test
  void preservesStructureMetadataAndSplitsLongBlocks() {
    RagProperties p = properties(10, 2);
    var chunker = new StructureAwareDocumentChunker(p);
    var doc =
        new NormalizedDocument(
            "a.md",
            "text/markdown",
            List.of(
                new NormalizedDocument.Block(
                    "1", NormalizedDocument.BlockType.HEADING, "# 战略", 1, null, "战略", Map.of()),
                new NormalizedDocument.Block(
                    "2",
                    NormalizedDocument.BlockType.LIST,
                    "客户群洞察与评估 ".repeat(15),
                    1,
                    null,
                    "战略",
                    Map.of())),
            Map.of());
    List<DocumentChunk> chunks = chunker.chunk("d", "v", doc);
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allMatch(c -> c.documentId().equals("d") && c.versionId().equals("v"));
    assertThat(chunks.getFirst().sectionPath()).isEqualTo("战略");
    assertThat(chunks).flatExtracting(DocumentChunk::sourceBlockIds).contains("1", "2");
    assertThat(chunks.getFirst().pageStart()).isEqualTo(1);
    assertThat(chunks.getFirst().pageEnd()).isEqualTo(1);
  }

  static RagProperties properties(int max, int overlap) {
    return new RagProperties(
        new RagProperties.Storage("x"),
        new RagProperties.Docling("x", "x", "", Duration.ZERO, "tokenizer"),
        new RagProperties.Model("x", "x", "x", Duration.ofSeconds(30), 0, false, 4096),
        new RagProperties.Embedding("x", "x", "x", 3),
        new RagProperties.Jina("x", "x", Duration.ZERO),
        new RagProperties.Chunking(max, overlap),
        new RagProperties.Retrieval(20, 20, 20, 60, 30, 10),
        new RagProperties.Graph(2, 50, 100, .35),
        new RagProperties.Context(6000));
  }
}
