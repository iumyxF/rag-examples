package com.example.indexing.rag.context;

import java.util.List;

public record BuiltContext(String text, List<Citation> citations, List<String> chunkIds) {
    public record Citation(
            int id,
            String chunkId,
            String documentId,
            String fileName,
            Integer pageNumber,
            Integer pageStart,
            Integer pageEnd,
            List<String> sourceBlockIds,
            String sheetName,
            String sectionPath,
            String excerpt) {
        public Citation(
                int id,
                String chunkId,
                String documentId,
                String fileName,
                Integer pageNumber,
                String sheetName,
                String sectionPath,
                String excerpt) {
            this(
                    id,
                    chunkId,
                    documentId,
                    fileName,
                    pageNumber,
                    pageNumber,
                    pageNumber,
                    List.of(),
                    sheetName,
                    sectionPath,
                    excerpt);
        }
    }
}
