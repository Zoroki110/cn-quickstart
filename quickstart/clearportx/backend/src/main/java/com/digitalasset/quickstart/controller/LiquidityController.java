// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ContractNotActiveError;
import com.digitalasset.quickstart.common.errors.InputTokenStaleOrNotVisibleError;
import com.digitalasset.quickstart.common.errors.LedgerVisibilityError;
import com.digitalasset.quickstart.common.errors.PoolEmptyError;
import com.digitalasset.quickstart.common.errors.PriceImpactTooHighError;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.digitalasset.quickstart.dto.AddLiquidityRequest;
import com.digitalasset.quickstart.dto.AddLiquidityResponse;
import com.digitalasset.quickstart.dto.ErrorResponse;
import com.digitalasset.quickstart.security.JwtAuthService;
import com.digitalasset.quickstart.security.JwtAuthService.AuthenticatedUser;
import com.digitalasset.quickstart.security.PartyMappingService;
import com.digitalasset.quickstart.service.AddLiquidityService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.CompletableFuture;

/**
 * LiquidityController - Handles liquidity provision and removal.
 */
@RestController
@RequestMapping("/api/liquidity")
public class LiquidityController {

    private static final Logger logger = LoggerFactory.getLogger(LiquidityController.class);
    private final AddLiquidityService addLiquidityService;
    private final PartyMappingService partyMappingService;
    private final JwtAuthService jwtAuthService;

    public LiquidityController(AddLiquidityService addLiquidityService,
                               PartyMappingService partyMappingService,
                               JwtAuthService jwtAuthService) {
        this.addLiquidityService = addLiquidityService;
        this.partyMappingService = partyMappingService;
        this.jwtAuthService = jwtAuthService;
    }

    @PostMapping("/add")
    @WithSpan
    @PreAuthorize("@partyGuard.isLiquidityProvider(#jwt)")
    public CompletableFuture<AddLiquidityResponse> addLiquidity(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddLiquidityRequest req,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader
    ) {
        Result<AddLiquidityService.AddLiquidityCommand, DomainError> pipeline =
                resolveProviderParty(jwt, authorizationHeader)
                        .flatMap(providerParty -> parseAndValidate(providerParty, req));

        if (pipeline.isErr()) {
            throw toHttpException(pipeline.getErrorUnsafe());
        }

        AddLiquidityService.AddLiquidityCommand command = pipeline.getValueUnsafe();
        logger.info("POST /api/liquidity/add - provider: {}, poolId: {}", command.providerParty(), command.poolId());

        return addLiquidityService.addLiquidity(command)
                .thenApply(this::mapToHttpResponse);
    }

    private Result<AddLiquidityService.AddLiquidityCommand, DomainError> parseAndValidate(
            final String providerParty,
            final AddLiquidityRequest request
    ) {
        if (request.poolId == null || request.poolId.isBlank()) {
            return Result.err(new ValidationError("poolId is required"));
        }
        try {
            BigDecimal amountA = request.amountA.setScale(10, RoundingMode.DOWN);
            BigDecimal amountB = request.amountB.setScale(10, RoundingMode.DOWN);
            BigDecimal minLp = request.minLPTokens.setScale(10, RoundingMode.DOWN);
            return Result.ok(new AddLiquidityService.AddLiquidityCommand(
                    request.poolId,
                    amountA,
                    amountB,
                    minLp,
                    providerParty
            ));
        } catch (ArithmeticException ex) {
            return Result.err(new ValidationError("Amounts must support scale=10 precision"));
        }
    }

    private Result<String, DomainError> resolveProviderParty(final Jwt jwt, final String authorizationHeader) {
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            Result<AuthenticatedUser, DomainError> authResult = jwtAuthService.authenticate(authorizationHeader);
            if (authResult.isOk()) {
                return Result.ok(authResult.getValueUnsafe().partyId());
            }
            return Result.err(authResult.getErrorUnsafe());
        }
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            logger.error("POST /api/liquidity/add called without valid JWT");
            return Result.err(new ValidationError("Authentication required - JWT subject missing", ValidationError.Type.AUTHENTICATION));
        }
        return Result.ok(partyMappingService.mapJwtSubjectToParty(jwt.getSubject()));
    }

    private AddLiquidityResponse mapToHttpResponse(final Result<AddLiquidityResponse, DomainError> result) {
        if (result.isOk()) {
            return result.getValueUnsafe();
        }
        throw toHttpException(result.getErrorUnsafe());
    }

    private RuntimeException toHttpException(final DomainError error) {
        HttpStatus status = HttpStatus.resolve(error.httpStatus());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return new ResponseStatusException(status, error.code() + ": " + error.message());
    }
}
// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.digitalasset.quickstart.dto.AddLiquidityRequest;
import com.digitalasset.quickstart.dto.AddLiquidityResponse;
import com.digitalasset.quickstart.security.PartyMappingService;
import com.digitalasset.quickstart.service.AddLiquidityService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.CompletableFuture;

/**
 * LiquidityController - Handles liquidity provision and removal
 */
@RestController
@RequestMapping("/api/liquidity")
public class LiquidityController {
    private static final Logger logger = LoggerFactory.getLogger(LiquidityController.class);
    private final AddLiquidityService addLiquidityService;
    private final PartyMappingService partyMappingService;

    public LiquidityController(AddLiquidityService addLiquidityService, PartyMappingService partyMappingService) {
        this.addLiquidityService = addLiquidityService;
        this.partyMappingService = partyMappingService;
    }

    @PostMapping("/add")
    @WithSpan
    @PreAuthorize("@partyGuard.isLiquidityProvider(#jwt)")
    public CompletableFuture<AddLiquidityResponse> addLiquidity(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddLiquidityRequest req
    ) {
        Result<AddLiquidityService.AddLiquidityCommand, DomainError> pipeline =
                resolveProviderParty(jwt).flatMap(providerParty -> parseAndValidate(providerParty, req));

        if (pipeline.isErr()) {
            throw toHttpException(pipeline.getErrorUnsafe());
        }

        AddLiquidityService.AddLiquidityCommand command = pipeline.getValueUnsafe();
        logger.info("POST /api/liquidity/add - provider: {}, poolId: {}", command.providerParty(), command.poolId());

        return addLiquidityService.addLiquidity(command)
                .thenApply(this::mapToHttpResponse);
    }

    private Result<AddLiquidityService.AddLiquidityCommand, DomainError> parseAndValidate(
            final String providerParty,
            final AddLiquidityRequest request
    ) {
        if (request.poolId == null || request.poolId.isBlank()) {
            return Result.err(new ValidationError("poolId is required"));
        }
        try {
            BigDecimal amountA = request.amountA.setScale(10, RoundingMode.DOWN);
            BigDecimal amountB = request.amountB.setScale(10, RoundingMode.DOWN);
            BigDecimal minLp = request.minLPTokens.setScale(10, RoundingMode.DOWN);
            return Result.ok(new AddLiquidityService.AddLiquidityCommand(
                    request.poolId,
                    amountA,
                    amountB,
                    minLp,
                    providerParty
            ));
        } catch (ArithmeticException ex) {
            return Result.err(new ValidationError("Amounts must support scale=10 precision"));
        }
    }

    private Result<String, DomainError> resolveProviderParty(final Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            logger.error("POST /api/liquidity/add called without valid JWT");
            return Result.err(new ValidationError("Authentication required - JWT subject missing", ValidationError.Type.AUTHENTICATION));
        }
        return Result.ok(partyMappingService.mapJwtSubjectToParty(jwt.getSubject()));
    }

    private AddLiquidityResponse mapToHttpResponse(final Result<AddLiquidityResponse, DomainError> result) {
        if (result.isOk()) {
            return result.getValueUnsafe();
        }
        throw toHttpException(result.getErrorUnsafe());
    }

    private RuntimeException toHttpException(final DomainError error) {
        HttpStatus status = HttpStatus.resolve(error.httpStatus());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return new ResponseStatusException(status, error.code() + ": " + error.message());
    }
}

