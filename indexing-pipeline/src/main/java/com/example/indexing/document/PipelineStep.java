package com.example.indexing.document;

import java.util.Arrays;

public enum PipelineStep {
    UPLOAD,
    DOCLING,
    CHUNKING,
    EMBEDDING,
    ELASTICSEARCH,
    GRAPH,
    ACTIVATE;

    public String code() {
        return name().toLowerCase();
    }

    public static PipelineStep executable(String value) {
        PipelineStep step = Arrays.stream(values())
                .filter(candidate -> candidate.code().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知流水线步骤: " + value));
        if (step == UPLOAD) {
            throw new IllegalArgumentException("上传步骤请使用文档上传接口");
        }
        return step;
    }
}
