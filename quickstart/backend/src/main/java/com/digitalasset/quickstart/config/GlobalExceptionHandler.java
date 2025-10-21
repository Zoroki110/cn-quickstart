// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.config;

import com.digitalasset.quickstart.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;

/**
 * Global exception handler for all REST controllers.
 *
 * Provides standardized error responses across all endpoints:
 * - ResponseStatusException (4xx/5xx errors)
 * - Validation errors (400 Bad Request)
 * - Access denied (403 Forbidden)
 * - Generic exceptions (500 Internal Server Error)
 * - CompletionException unwrapping (async errors)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle ResponseStatusException thrown by controllers.
     * This is the most common exception type in our codebase.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(
            ResponseStatusException ex,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String errorCode = getErrorCodeFromStatus(status);
        String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();

        ErrorResponse errorResponse = new ErrorResponse(
            errorCode,
            message,
            status.value(),
            request.getRequestURI()
        );

        // Add request ID if present
        String requestId = request.getHeader("X-Request-ID");
        if (requestId != null) {
            errorResponse.setRequestId(requestId);
        }

        logger.warn("ResponseStatusException: {} {} - {}",
            status.value(), request.getRequestURI(), message);

        return ResponseEntity.status(status).body(errorResponse);
    }

    /**
     * Handle validation errors (e.g., @Valid annotation failures).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        Map<String, String> validationErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            validationErrors.put(error.getField(), error.getDefaultMessage())
        );

        ErrorResponse errorResponse = new ErrorResponse(
            "VALIDATION_ERROR",
            "Request validation failed",
            HttpStatus.BAD_REQUEST.value(),
            request.getRequestURI()
        );
        errorResponse.setDetails(validationErrors);

        logger.warn("Validation error on {}: {}", request.getRequestURI(), validationErrors);

        return ResponseEntity.badRequest().body(errorResponse);
    }

    /**
     * Handle access denied errors (Spring Security).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException ex,
            HttpServletRequest request
    ) {
        ErrorResponse errorResponse = new ErrorResponse(
            "ACCESS_DENIED",
            "You do not have permission to access this resource",
            HttpStatus.FORBIDDEN.value(),
            request.getRequestURI()
        );

        logger.warn("Access denied: {} - {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }

    /**
     * Handle CompletionException (wraps async exceptions from CompletableFuture).
     * Unwraps and delegates to appropriate handler.
     */
    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<ErrorResponse> handleCompletionException(
            CompletionException ex,
            HttpServletRequest request
    ) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

        // Unwrap and handle the underlying exception
        if (cause instanceof ResponseStatusException) {
            return handleResponseStatusException((ResponseStatusException) cause, request);
        }

        // Fallback to generic error
        return handleGenericException(cause, request);
    }

    /**
     * Handle all other uncaught exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Throwable ex,
            HttpServletRequest request
    ) {
        // Check for specific Canton/DAML errors
        String message = ex.getMessage();
        String errorCode = "INTERNAL_SERVER_ERROR";
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;

        if (message != null) {
            if (message.contains("CONTRACT_NOT_FOUND")) {
                errorCode = "CONTRACT_NOT_FOUND";
                status = HttpStatus.CONFLICT;
                message = "Contract was archived or does not exist. Please refresh and retry.";
            } else if (message.contains("INSUFFICIENT_FUNDS") || message.contains("insufficient balance")) {
                errorCode = "INSUFFICIENT_FUNDS";
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                message = "Insufficient token balance for this operation";
            } else if (message.contains("slippage")) {
                errorCode = "SLIPPAGE_EXCEEDED";
                status = HttpStatus.UNPROCESSABLE_ENTITY;
            } else if (message.contains("expired") || message.contains("deadline")) {
                errorCode = "REQUEST_TIMEOUT";
                status = HttpStatus.REQUEST_TIMEOUT;
            }
        }

        ErrorResponse errorResponse = new ErrorResponse(
            errorCode,
            message != null ? message : "An unexpected error occurred",
            status.value(),
            request.getRequestURI()
        );

        // Log the full stack trace for 500 errors
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            logger.error("Unhandled exception on {}: {}", request.getRequestURI(), message, ex);
        } else {
            logger.warn("Exception on {}: {} - {}", request.getRequestURI(), errorCode, message);
        }

        return ResponseEntity.status(status).body(errorResponse);
    }

    /**
     * Map HTTP status to error code.
     */
    private String getErrorCodeFromStatus(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case UNPROCESSABLE_ENTITY -> "UNPROCESSABLE_ENTITY";
            case TOO_MANY_REQUESTS -> "RATE_LIMIT_EXCEEDED";
            case REQUEST_TIMEOUT -> "REQUEST_TIMEOUT";
            case INTERNAL_SERVER_ERROR -> "INTERNAL_SERVER_ERROR";
            case SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE";
            default -> status.name();
        };
    }
}
