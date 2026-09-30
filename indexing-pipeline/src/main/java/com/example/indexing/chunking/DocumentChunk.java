package com.example.indexing.chunking;

import java.util.List;
import java.util.Map;

public record DocumentChunk(
        String id,
        String documentId,
        String versionId,
        int ordinal,
        String fileName,
        String content,
        String title,
        String sectionPath,
        Integer pageNumber,
        Integer pageStart,
        Integer pageEnd,
        List<String> sourceBlockIds,
        String sheetName,
        List<String> blockTypes,
        int estimatedTokens,
        Map<String, Object> metadata) {
    public DocumentChunk {
        sourceBlockIds = sourceBlockIds == null ? List.of() : List.copyOf(sourceBlockIds);
        blockTypes = blockTypes == null ? List.of() : List.copyOf(blockTypes);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public DocumentChunk(
            String id,
            String documentId,
            String versionId,
            int ordinal,
            String fileName,
            String content,
            String title,
            String sectionPath,
            Integer pageNumber,
            String sheetName,
            List<String> blockTypes,
            int estimatedTokens,
            Map<String, Object> metadata) {
        this(
                id,
                documentId,
                versionId,
                ordinal,
                fileName,
                content,
                title,
                sectionPath,
                pageNumber,
                pageNumber,
                pageNumber,
                List.of(),
                sheetName,
                blockTypes,
                estimatedTokens,
                metadata);
    }
}
