package com.example.indexing.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DoclingDocumentParserAdapterTest {
  @Test
  void configuresHybridChunkingWithTheEmbeddingTokenizer() {
    var request =
        DoclingDocumentParserAdapter.hybridChunkRequest("qwen3-vl-embedding-2b-tokenizer", 800);

    assertThat(request.getChunkingOptions().getTokenizer())
        .isEqualTo("qwen3-vl-embedding-2b-tokenizer");
    assertThat(request.getChunkingOptions().getMaxTokens()).isEqualTo(800);
  }

  @Test
  void recognizesMarkdownStructures() {
    var blocks =
        DoclingDocumentParserAdapter.parseMarkdown(
            "# 标题\n\n## 章节\n\n1. 项目一\n2. 项目二\n\n|A|B|\n|-|-|\n|1|2|");
    assertThat(blocks)
        .extracting(NormalizedDocument.Block::type)
        .containsExactly(
            NormalizedDocument.BlockType.TITLE, NormalizedDocument.BlockType.HEADING,
            NormalizedDocument.BlockType.LIST, NormalizedDocument.BlockType.TABLE);
  }

  @Test
  void generatesDeterministicBlockIds() {
    var first = DoclingDocumentParserAdapter.parseMarkdown("# 标题\n\n稳定的正文");
    var second = DoclingDocumentParserAdapter.parseMarkdown("# 标题\n\n稳定的正文");

    assertThat(first).extracting(NormalizedDocument.Block::id)
        .containsExactlyElementsOf(second.stream().map(NormalizedDocument.Block::id).toList());
  }
}
