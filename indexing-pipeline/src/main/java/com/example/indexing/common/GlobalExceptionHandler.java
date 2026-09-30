package com.example.indexing.common;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PipelineBusyException.class)
    ResponseEntity<?> conflict(PipelineBusyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("timestamp", Instant.now(), "message", e.getMessage()));
    }

    @ExceptionHandler(PipelineException.class)
    ResponseEntity<?> pipeline(PipelineException e, HttpServletRequest request) {
        log.error(
                "Pipeline request failed: stage={}, method={}, path={}, message={}",
                e.stage(),
                request.getMethod(),
                request.getRequestURI(),
                e.getMessage(),
                e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("timestamp", Instant.now(), "stage", e.stage(), "message", e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<?> badRequest(Exception e) {
        return ResponseEntity.badRequest()
                .body(Map.of("timestamp", Instant.now(), "message", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e, HttpServletRequest request) {
        log.error(
                "Unexpected request failure: method={}, path={}, message={}",
                request.getMethod(),
                request.getRequestURI(),
                e.getMessage(),
                e);
        return ResponseEntity.internalServerError()
                .body(Map.of("timestamp", Instant.now(), "stage", "unexpected", "message", e.getMessage()));
    }
}
