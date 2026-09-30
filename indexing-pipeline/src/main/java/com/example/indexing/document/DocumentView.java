package com.example.indexing.document;

import com.example.indexing.document.persistence.DocumentEntity;

import java.time.LocalDateTime;

public record DocumentView(
        String id,
        String fileName,
        String contentHash,
        String mediaType,
        String status,
        String activeVersionId,
        String workingVersionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public static DocumentView from(DocumentEntity d) {
        return new DocumentView(
                d.id,
                d.fileName,
                d.contentHash,
                d.mediaType,
                d.status,
                d.activeVersionId,
                d.workingVersionId,
                d.createdAt,
                d.updatedAt);
    }
}
