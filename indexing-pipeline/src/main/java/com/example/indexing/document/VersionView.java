package com.example.indexing.document;

import com.example.indexing.document.persistence.DocumentVersionEntity;
import java.time.LocalDateTime;
import java.util.List;

public record VersionView(
        String id, String status, String sourceVersionId, int chunkCount, int entityCount,
        int relationCount, LocalDateTime createdAt, LocalDateTime activatedAt,
        List<PipelineStepView> steps) {
    static VersionView from(DocumentVersionEntity version, List<PipelineStepView> steps) {
        return new VersionView(
                version.id, version.status, version.sourceVersionId, version.chunkCount,
                version.entityCount, version.relationCount, version.createdAt,
                version.activatedAt, steps);
    }
}
