package com.example.evaluation.common;

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
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<?> conflict(ConflictException error) {
        return response(HttpStatus.CONFLICT, error.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<?> badRequest(Exception error) {
        return response(HttpStatus.BAD_REQUEST, error.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> serverError(Exception error, HttpServletRequest request) {
        log.error(
                "Unexpected request failure: method={}, path={}, message={}",
                request.getMethod(),
                request.getRequestURI(),
                error.getMessage(),
                error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
    }

    private ResponseEntity<?> response(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("timestamp", Instant.now(), "status", status.value(), "message", message));
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }
}
