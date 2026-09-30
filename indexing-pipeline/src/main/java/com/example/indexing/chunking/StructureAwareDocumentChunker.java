package com.example.indexing.chunking;

import com.example.indexing.config.RagProperties;
import com.example.indexing.parsing.NormalizedDocument;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class StructureAwareDocumentChunker implements DocumentChunker {
    private final int maxChars;
    private final int overlapChars;

    public StructureAwareDocumentChunker(RagProperties properties) {
        this.maxChars = properties.chunking().maxTokens() * 4;
        this.overlapChars = properties.chunking().overlapTokens() * 4;
    }

    @Override
    public List<DocumentChunk> chunk(
            String documentId, String versionId, NormalizedDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        LinkedHashSet<String> types = new LinkedHashSet<>();
        LinkedHashSet<String> sourceBlockIds = new LinkedHashSet<>();
        String section = "";
        Integer pageStart = null;
        Integer pageEnd = null;
        String sheet = null;
        String lastSourceBlockId = null;
        String lastBlockType = null;
        Integer lastBlockPage = null;
        Map<String, Object> sourceMetadata = Map.of();
        int ordinal = 0;
        for (NormalizedDocument.Block block : document.blocks()) {
            if (content.length() > 0 && content.length() + block.text().length() + 2 > maxChars) {
                ordinal =
                        emit(
                                chunks,
                                documentId,
                                versionId,
                                document.fileName(),
                                content.toString(),
                                section,
                                pageStart,
                                pageEnd,
                                sourceBlockIds,
                                sheet,
                                types,
                                ordinal,
                                sourceMetadata);
                String overlap = tail(content.toString(), overlapChars);
                content.setLength(0);
                content.append(overlap);
                types.clear();
                sourceBlockIds.clear();
                pageStart = null;
                pageEnd = null;
                if (!overlap.isBlank() && lastSourceBlockId != null) {
                    sourceBlockIds.add(lastSourceBlockId);
                    types.add(lastBlockType);
                    pageStart = lastBlockPage;
                    pageEnd = lastBlockPage;
                }
            }
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append(block.text());
            types.add(block.type().name());
            sourceBlockIds.add(block.id());
            if (block.sectionPath() != null && !block.sectionPath().isBlank()) {
                section = block.sectionPath();
            }
            if (block.pageNumber() != null) {
                pageStart = pageStart == null ? block.pageNumber() : Math.min(pageStart, block.pageNumber());
                pageEnd = pageEnd == null ? block.pageNumber() : Math.max(pageEnd, block.pageNumber());
            }
            if (block.sheetName() != null) {
                sheet = block.sheetName();
            }
            if (block.metadata() != null && !block.metadata().isEmpty()) {
                sourceMetadata = block.metadata();
            }
            lastSourceBlockId = block.id();
            lastBlockType = block.type().name();
            lastBlockPage = block.pageNumber();
            while (content.length() > maxChars) {
                String part = content.substring(0, maxChars);
                ordinal =
                        emit(
                                chunks,
                                documentId,
                                versionId,
                                document.fileName(),
                                part,
                                section,
                                pageStart,
                                pageEnd,
                                sourceBlockIds,
                                sheet,
                                types,
                                ordinal,
                                sourceMetadata);
                content.delete(0, Math.max(1, maxChars - overlapChars));
            }
        }
        if (!content.isEmpty()) {
            emit(
                    chunks,
                    documentId,
                    versionId,
                    document.fileName(),
                    content.toString(),
                    section,
                    pageStart,
                    pageEnd,
                    sourceBlockIds,
                    sheet,
                    types,
                    ordinal,
                    sourceMetadata);
        }
        return chunks;
    }

    private int emit(
            List<DocumentChunk> out,
            String doc,
            String version,
            String file,
            String text,
            String section,
            Integer pageStart,
            Integer pageEnd,
            LinkedHashSet<String> sourceBlockIds,
            String sheet,
            LinkedHashSet<String> types,
            int ordinal) {
        return emit(
                out,
                doc,
                version,
                file,
                text,
                section,
                pageStart,
                pageEnd,
                sourceBlockIds,
                sheet,
                types,
                ordinal,
                Map.of());
    }

    private int emit(
            List<DocumentChunk> out,
            String doc,
            String version,
            String file,
            String text,
            String section,
            Integer pageStart,
            Integer pageEnd,
            LinkedHashSet<String> sourceBlockIds,
            String sheet,
            LinkedHashSet<String> types,
            int ordinal,
            Map<String, Object> metadata) {
        String id = version + "-" + ordinal;
        out.add(
                new DocumentChunk(
                        id,
                        doc,
                        version,
                        ordinal,
                        file,
                        text.strip(),
                        section,
                        section,
                        pageStart,
                        pageStart,
                        pageEnd,
                        List.copyOf(sourceBlockIds),
                        sheet,
                        List.copyOf(types),
                        Math.max(1, text.length() / 4),
                        metadata));
        return ordinal + 1;
    }

    private String tail(String text, int length) {
        if (length <= 0 || text.length() <= length) {
            return length <= 0 ? "" : text;
        }
        int start = Math.max(0, text.length() - length);
        int boundary = text.indexOf(' ', start);
        return text.substring(boundary < 0 ? start : boundary + 1);
    }
}
