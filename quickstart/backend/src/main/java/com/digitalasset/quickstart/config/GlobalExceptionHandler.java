// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.config;

import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.digitalasset.quickstart.dto.ErrorMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * Global exception handler returning the shared ApiResponse error model.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        Map<String, String> validationErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                validationErrors.put(error.getField(), error.getDefaultMessage())
        );

        ApiError apiError = new ApiError(
                ErrorCode.VALIDATION,
                "Request validation failed",
                Map.of("fields", validationErrors),
                false,
                null,
                null
        );

        String requestId = resolveRequestId(request);
        logger.warn("[GlobalExceptionHandler] [requestId={}] Validation failed: {}", requestId, validationErrors);
        return ResponseEntity.status(ErrorMapper.toHttpStatus(apiError.code))
                .body(ApiResponse.failure(requestId, apiError));
    }

    /**
     * Catch-all handler for unexpected exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception ex,
            HttpServletRequest request
    ) {
        Throwable root = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
        String requestId = resolveRequestId(request);
        String message = root.getMessage() != null ? root.getMessage() : "Unexpected error";

        ApiError apiError = new ApiError(
                ErrorCode.INTERNAL,
                message,
                null,
                false,
                null,
                null
        );

        logger.error("[GlobalExceptionHandler] [requestId={}] Unhandled exception: {}", requestId, message, root);
        return ResponseEntity.status(ErrorMapper.toHttpStatus(apiError.code))
                .body(ApiResponse.failure(requestId, apiError));
    }

    private String resolveRequestId(HttpServletRequest request) {
        if (request == null) {
            return "req-" + UUID.randomUUID().toString().substring(0, 8);
        }
        Object attr = request.getAttribute("requestId");
        if (attr instanceof String s && !s.isBlank()) {
            return s;
        }
        String header = request.getHeader("X-Request-Id");
        if (header != null && !header.isBlank()) {
            return header;
        }
        return "req-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
