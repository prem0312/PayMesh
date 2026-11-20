package com.demo.upimesh.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidArgument() {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpectedFailure(Exception exception) {
        HttpStatus status = exception instanceof ErrorResponse errorResponse
                ? HttpStatus.valueOf(errorResponse.getStatusCode().value())
                : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is4xxClientError()) {
            return ResponseEntity.status(status).body(Map.of("error", "invalid_request"));
        }
        log.error("Unhandled HTTP request failure", exception);
        return ResponseEntity.status(status)
                .body(Map.of("error", "internal_error"));
    }
}
