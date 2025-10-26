// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import clearportx_amm_production.amm.pool.Pool;
import clearportx_amm_production.token.token.Token;
import com.digitalasset.quickstart.dto.PoolDTO;
import com.digitalasset.quickstart.dto.TokenDTO;
import com.digitalasset.quickstart.ledger.LedgerApi;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * LedgerReader - Authoritative contract reads via Ledger API
 *
 * This service reads contracts directly from the Canton Ledger API, providing:
 * - Immediate consistency (no PQS lag)
 * - No package allowlist issues
 * - Guaranteed active contracts only
 *
 * Use this for all core app reads (tokens, pools, LP tokens).
 * Reserve PQS for analytics queries (TVL, volume, fees).
 */
@Service
public class LedgerReader {
    private static final Logger logger = LoggerFactory.getLogger(LedgerReader.class);
    private final LedgerApi ledger;
    private final VolumeMetricsService volumeMetricsService;

    public LedgerReader(LedgerApi ledger, VolumeMetricsService volumeMetricsService) {
        this.ledger = ledger;
        this.volumeMetricsService = volumeMetricsService;
    }

    /**
     * Get all tokens owned by a specific party
     * Returns tokens in frontend-friendly DTO format
     */
    @WithSpan
    public CompletableFuture<List<TokenDTO>> tokensForParty(String party) {
        logger.info("Fetching tokens for party: {}", party);
        return ledger.getActiveContracts(Token.class)
                .thenApply(contracts -> contracts.stream()
                        .map(c -> c.payload)
                        .filter(t -> t.getOwner.getParty.equals(party))
                        .map(t -> new TokenDTO(
                                t.getSymbol,
                                t.getSymbol + " Token",  // Frontend expects a name field
                                10,  // Standard decimals for demo tokens
                                t.getAmount.toPlainString(),
                                t.getOwner.getParty
                        ))
                        .toList())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        logger.error("Failed to fetch tokens for party {}: {}", party, ex.getMessage());
                    } else {
                        logger.info("Fetched {} tokens for party {}", result.size(), party);
                    }
                });
    }

    /**
     * Get all active liquidity pools
     * Returns pools in frontend-friendly DTO format
     */
    @WithSpan
    public CompletableFuture<List<PoolDTO>> pools() {
        logger.info("Fetching all active pools");
        return ledger.getActiveContracts(Pool.class)
                .thenApply(contracts -> contracts.stream()
                        .map(c -> {
                            // Get 24h volume from metrics
                            double volume24h = volumeMetricsService.getVolume24h(
                                c.payload.getSymbolA,
                                c.payload.getSymbolB
                            );

                            return new PoolDTO(
                                    c.payload.getPoolId,  // poolId from Pool contract
                                    new PoolDTO.TokenInfoDTO(c.payload.getSymbolA, c.payload.getSymbolA, 10),
                                    new PoolDTO.TokenInfoDTO(c.payload.getSymbolB, c.payload.getSymbolB, 10),
                                    c.payload.getReserveA.toPlainString(),
                                    c.payload.getReserveB.toPlainString(),
                                    c.payload.getTotalLPSupply.toPlainString(),
                                    convertFeeBpsToRate(c.payload.getFeeBps),
                                    String.format("%.2f", volume24h)  // 24h volume
                            );
                        })
                        .toList())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        logger.error("Failed to fetch pools: {}", ex.getMessage());
                    } else {
                        logger.info("Fetched {} active pools", result.size());
                    }
                });
    }

    /**
     * Convert fee in basis points (e.g., 30) to decimal rate (e.g., "0.003" for 0.3%)
     */
    private String convertFeeBpsToRate(Long feeBps) {
        return String.valueOf(feeBps / 10000.0);
    }
}
