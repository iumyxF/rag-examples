package com.example.indexing.document;

import com.example.indexing.document.persistence.PipelineStepEntity;
import java.time.LocalDateTime;

public record PipelineStepView(
        String code,
        String status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMs,
        int itemCount,
        String summary,
        String errorMessage) {
    static PipelineStepView from(PipelineStepEntity step) {
        return new PipelineStepView(
                step.stepCode, step.status, step.startedAt, step.finishedAt, step.durationMs,
                step.itemCount, step.outputSummary, step.errorMessage);
    }
}
