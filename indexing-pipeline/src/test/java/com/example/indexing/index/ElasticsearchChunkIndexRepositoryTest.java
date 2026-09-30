package com.example.indexing.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.indexing.chunking.DocumentChunk;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ElasticsearchChunkIndexRepositoryTest {
    @Test
    void readsChunkWhenAllPageFieldsAreNull() {
        Map<String, Object> source = source();
        source.put("page_number", null);
        source.put("page_start", null);
        source.put("page_end", null);

        DocumentChunk chunk = ElasticsearchChunkIndexRepository.fromSource(source);

        assertThat(chunk.pageNumber()).isNull();
        assertThat(chunk.pageStart()).isNull();
        assertThat(chunk.pageEnd()).isNull();
    }

    @Test
    void fallsBackToPageNumberWhenPageRangeIsMissing() {
        Map<String, Object> source = source();
        source.put("page_number", 7);
        source.put("page_start", null);
        source.put("page_end", null);

        DocumentChunk chunk = ElasticsearchChunkIndexRepository.fromSource(source);

        assertThat(chunk.pageNumber()).isEqualTo(7);
        assertThat(chunk.pageStart()).isEqualTo(7);
        assertThat(chunk.pageEnd()).isEqualTo(7);
    }

    private Map<String, Object> source() {
        Map<String, Object> source = new HashMap<>();
        source.put("chunk_id", "version-0");
        source.put("document_id", "document");
        source.put("version_id", "version");
        source.put("ordinal", 0);
        source.put("file_name", "guide.pdf");
        source.put("content", "content");
        source.put("title", "title");
        source.put("section_path", "section");
        source.put("source_block_ids", List.of("block-1"));
        source.put("sheet_name", null);
        source.put("block_types", List.of("PARAGRAPH"));
        source.put("estimated_tokens", 10);
        source.put("source_metadata", Map.of());
        return source;
    }
}
