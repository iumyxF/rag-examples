package com.example.indexing.common;

public class PipelineBusyException extends RuntimeException {
    public PipelineBusyException(String message) {
        super(message);
    }
}
