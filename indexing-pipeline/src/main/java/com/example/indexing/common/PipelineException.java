package com.example.indexing.common;

public class PipelineException extends RuntimeException {
    private final String stage;

    public PipelineException(String stage, String message) {
        super(message);
        this.stage = stage;
    }

    public PipelineException(String stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }
}
