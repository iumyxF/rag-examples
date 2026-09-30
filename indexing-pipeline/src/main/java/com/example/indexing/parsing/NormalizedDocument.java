package com.example.indexing.parsing;

import java.util.List;
import java.util.Map;

public record NormalizedDocument(
        String fileName, String mediaType, List<Block> blocks, Map<String, Object> metadata) {
    public record Block(
            String id,
            BlockType type,
            String text,
            Integer pageNumber,
            String sheetName,
            String sectionPath,
            Map<String, Object> metadata) {
    }

    public enum BlockType {
        TITLE,
        HEADING,
        PARAGRAPH,
        LIST,
        TABLE,
        CODE,
        OTHER
    }
}
