// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.controller;

import clearportx_amm.amm.pool.Pool;
import clearportx_amm.token.token.Token;
import clearportx_amm.lptoken.lptoken.LPToken;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.Party;
import com.digitalasset.quickstart.dto.AddLiquidityRequest;
import com.digitalasset.quickstart.dto.AddLiquidityResponse;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.ledger.StaleAcsRetry;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.security.PartyMappingService;
import com.digitalasset.quickstart.metrics.SwapMetrics;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * LiquidityController - Handles liquidity provision and removal
 *
 * CORS configured globally in WebSecurityConfig
 */
@RestController
@RequestMapping("/api/liquidity")
public class LiquidityController {
    private static final Logger logger = LoggerFactory.getLogger(LiquidityController.class);
    private final LedgerApi ledger;
    private final AuthUtils authUtils;
    private final PartyMappingService partyMappingService;
    private final SwapMetrics swapMetrics;

    public LiquidityController(LedgerApi ledger, AuthUtils authUtils, PartyMappingService partyMappingService, SwapMetrics swapMetrics) {
        this.ledger = ledger;
        this.authUtils = authUtils;
        this.partyMappingService = partyMappingService;
        this.swapMetrics = swapMetrics;
    }

    /**
     * POST /api/liquidity/add - Add liquidity to a pool
     *
     * Multi-party authorization: actAs = [liquidityProvider, poolParty, lpIssuer]
     *
     * Flow:
     * 1. Extract liquidityProvider from JWT
     * 2. Validate pool + provider tokens at ledger end
     * 3. Check amounts >= requested
     * 4. Exercise Pool.AddLiquidity
     * 5. Return new pool CID + LP token CID
     */
    @PostMapping("/add")
    @WithSpan
    @PreAuthorize("@partyGuard.isLiquidityProvider(#jwt)")
    public CompletableFuture<AddLiquidityResponse> addLiquidity(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody AddLiquidityRequest req
    ) {
        if (jwt == null || jwt.getSubject() == null) {
            logger.error("POST /api/liquidity/add called without valid JWT");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required - JWT subject missing");
        }

        String jwtSubject = jwt.getSubject();
        String liquidityProvider = partyMappingService.mapJwtSubjectToParty(jwtSubject);
        String commandId = UUID.randomUUID().toString();

        // Enforce scale=10 for all amounts
        BigDecimal amountA = req.amountA.setScale(10, RoundingMode.DOWN);
        BigDecimal amountB = req.amountB.setScale(10, RoundingMode.DOWN);
        BigDecimal minLPTokens = req.minLPTokens.setScale(10, RoundingMode.DOWN);

        logger.info("POST /api/liquidity/add - JWT subject: {}, Canton party: {}, poolId: {}, amountA: {}, amountB: {}, commandId: {}",
            jwtSubject, liquidityProvider, req.poolId, amountA, amountB, commandId);

        // Step 1: Validate pool at ledger end
        return ledger.getActiveContracts(Pool.class)
            .thenCompose(pools -> {
                // Note: Active pools count is updated by PoolMetricsScheduler (scheduled task)
                // Not updated here to avoid traffic-dependent metrics

                // Find pool by poolId
                Optional<LedgerApi.ActiveContract<Pool>> maybePool = pools.stream()
                    .filter(p -> p.payload.getPoolId.equals(req.poolId))
                    .findFirst();

                if (maybePool.isEmpty()) {
                    logger.error("Pool not found: {}", req.poolId);
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pool not found: " + req.poolId);
                }

                LedgerApi.ActiveContract<Pool> pool = maybePool.get();
                Pool poolPayload = pool.payload;

                // Extract party IDs from pool
                String poolParty = poolPayload.getPoolParty.getParty;
                String lpIssuer = poolPayload.getLpIssuer.getParty;

                logger.info("Found pool {} - poolParty: {}, lpIssuer: {}, reserveA: {}, reserveB: {}",
                    req.poolId, poolParty, lpIssuer, poolPayload.getReserveA, poolPayload.getReserveB);

                // Step 2: Validate provider's tokens at ledger end
                return ledger.getActiveContracts(Token.class)
                    .thenCompose(tokens -> {
                        // Find provider's token for symbolA
                        Optional<LedgerApi.ActiveContract<Token>> maybeTokenA = tokens.stream()
                            .filter(t -> t.payload.getSymbol.equals(poolPayload.getSymbolA) &&
                                         t.payload.getOwner.getParty.equals(liquidityProvider))
                            .findFirst();

                        if (maybeTokenA.isEmpty()) {
                            logger.error("Token A ({}) not found for provider {}", poolPayload.getSymbolA, liquidityProvider);
                            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "Token " + poolPayload.getSymbolA + " not found for provider");
                        }

                        // Find provider's token for symbolB
                        Optional<LedgerApi.ActiveContract<Token>> maybeTokenB = tokens.stream()
                            .filter(t -> t.payload.getSymbol.equals(poolPayload.getSymbolB) &&
                                         t.payload.getOwner.getParty.equals(liquidityProvider))
                            .findFirst();

                        if (maybeTokenB.isEmpty()) {
                            logger.error("Token B ({}) not found for provider {}", poolPayload.getSymbolB, liquidityProvider);
                            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "Token " + poolPayload.getSymbolB + " not found for provider");
                        }

                        LedgerApi.ActiveContract<Token> tokenA = maybeTokenA.get();
                        LedgerApi.ActiveContract<Token> tokenB = maybeTokenB.get();

                        // Check amounts
                        if (tokenA.payload.getAmount.compareTo(amountA) < 0) {
                            logger.error("Insufficient token A: has {}, needs {}", tokenA.payload.getAmount, amountA);
                            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "Insufficient " + poolPayload.getSymbolA + ": have " + tokenA.payload.getAmount + ", need " + amountA);
                        }

                        if (tokenB.payload.getAmount.compareTo(amountB) < 0) {
                            logger.error("Insufficient token B: has {}, needs {}", tokenB.payload.getAmount, amountB);
                            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "Insufficient " + poolPayload.getSymbolB + ": have " + tokenB.payload.getAmount + ", need " + amountB);
                        }

                        logger.info("Token validation passed - tokenA CID: {}, tokenB CID: {}, commandId: {}",
                            tokenA.contractId.getContractId, tokenB.contractId.getContractId, commandId);

                        // Step 3: Exercise AddLiquidity choice with retry on CONTRACT_NOT_FOUND
                        // Multi-party actAs: [liquidityProvider, poolParty, lpIssuer]

                        // Create deadline (10 minutes from now)
                        Instant deadline = Instant.now().plusSeconds(600);

                        Pool.AddLiquidity choice = new Pool.AddLiquidity(
                            new Party(liquidityProvider),
                            new ContractId<>(tokenA.contractId.getContractId),
                            new ContractId<>(tokenB.contractId.getContractId),
                            amountA,
                            amountB,
                            minLPTokens,
                            deadline
                        );

                        logger.info("Exercising AddLiquidity with actAs=[{}, {}, {}], commandId: {}",
                            liquidityProvider, poolParty, lpIssuer, commandId);

                        // Wrap with StaleAcsRetry for automatic CONTRACT_NOT_FOUND handling
                        return StaleAcsRetry.run(
                            () -> ledger.exerciseAndGetResultWithParties(
                                pool.contractId,
                                choice,
                                commandId,
                                List.of(liquidityProvider, poolParty, lpIssuer),
                                List.of(poolParty),  // readAs for visibility
                                List.of()  // No disclosed contracts
                            ),
                            () -> {
                                // Refresh ACS snapshot by re-querying (no-op for now, validation happens before exercise)
                                logger.info("Refreshing ACS snapshot for retry");
                            },
                            "AddLiquidity"
                        ).thenApply(result -> {
                            // Result is Tuple2<ContractId<LPToken>, ContractId<Pool>>
                            // Access tuple elements with get_1 and get_2
                            ContractId<LPToken> lpTokenCid = result.get_1;
                            ContractId<Pool> newPoolCid = result.get_2;

                            String newReserveA = poolPayload.getReserveA.add(amountA).toPlainString();
                            String newReserveB = poolPayload.getReserveB.add(amountB).toPlainString();

                            logger.info("METRIC: add_liquidity_success{{provider=\"{}\", pool=\"{}\"}}",
                                liquidityProvider, req.poolId);
                            logger.info("AddLiquidity success - lpTokenCid: {}, newPoolCid: {}, reserveA: {}, reserveB: {}",
                                lpTokenCid.getContractId, newPoolCid.getContractId, newReserveA, newReserveB);

                            return new AddLiquidityResponse(
                                lpTokenCid.getContractId,
                                newPoolCid.getContractId,
                                newReserveA,
                                newReserveB
                            );
                        });
                    });
            })
            .exceptionally(ex -> {
                // Handle CONTRACT_NOT_FOUND with clear error
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                String errorMessage = cause.getMessage();

                logger.error("AddLiquidity failed: {}", errorMessage, ex);

                if (errorMessage != null && errorMessage.contains("CONTRACT_NOT_FOUND")) {
                    logger.warn("Stale CID detected - pool or token was archived");
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Pool or token changed; refresh and retry");
                }

                if (errorMessage != null && errorMessage.contains("Deadline passed")) {
                    throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "Deadline expired");
                }

                if (errorMessage != null && errorMessage.contains("Slippage")) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Slippage protection triggered: " + errorMessage);
                }

                logger.error("METRIC: add_liquidity_failure{{reason=\"{}\", pool=\"{}\"}}",
                    errorMessage, req.poolId);

                // Re-throw as ResponseStatusException if already one
                if (cause instanceof ResponseStatusException) {
                    throw (ResponseStatusException) cause;
                }

                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage, cause);
            });
    }
}
