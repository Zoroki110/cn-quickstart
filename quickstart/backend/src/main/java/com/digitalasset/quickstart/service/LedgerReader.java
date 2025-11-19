// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import clearportx_amm_drain_credit.amm.pool.Pool;
import clearportx_amm_drain_credit.lptoken.lptoken.LPToken;
import clearportx_amm_drain_credit.token.token.Token;
import com.digitalasset.quickstart.dto.LpTokenDTO;
import com.digitalasset.quickstart.dto.PoolDTO;
import com.digitalasset.quickstart.dto.TokenDTO;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.pqs.Pqs;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
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
    @Nullable
    private final Pqs pqs;

    @Autowired
    public LedgerReader(LedgerApi ledger, VolumeMetricsService volumeMetricsService, @Autowired(required = false) @Nullable Pqs pqs) {
        this.ledger = ledger;
        this.volumeMetricsService = volumeMetricsService;
        this.pqs = pqs;
        if (pqs == null) {
            logger.info("PQS not available - using Ledger API only");
        }
    }

    /**
     * Get all tokens owned by a specific party
     * Returns tokens in frontend-friendly DTO format
     */
    @WithSpan
    public CompletableFuture<List<TokenDTO>> tokensForParty(String party) {
        logger.info("Fetching tokens for party: {}", party);
        // Use party override to read the caller's authoritative ACS (not just app provider's view)
        return ledger.getActiveContractsForParty(Token.class, party)
                .thenApply(contracts -> contracts.stream()
                        .filter(c -> c.payload.getOwner.getParty.equals(party))
                        .map(c -> new TokenDTO(
                                c.payload.getSymbol,
                                c.payload.getSymbol + " Token",
                                10,
                                c.payload.getAmount.toPlainString(),
                                c.payload.getOwner.getParty
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
     * Get wallet-only tokens for a party (excludes pool canonical tokens when party is also pool operator)
     */
    @WithSpan
    public CompletableFuture<List<TokenDTO>> walletTokensForParty(String party) {
        logger.info("Fetching wallet tokens for party (excluding canonicals): {}", party);
        // 1) Build set of canonical token CIDs for pools where poolParty == party
        CompletableFuture<java.util.Set<String>> canonSetFut = ledger.getActiveContractsForParty(Pool.class, party)
                .thenApply(pools -> {
                    java.util.Set<String> canon = new java.util.HashSet<>();
                    for (var pac : pools) {
                        var pay = pac.payload;
                        if (party.equals(pay.getPoolParty.getParty)) {
                            pay.getTokenACid.ifPresent(cid -> canon.add(cid.getContractId));
                            pay.getTokenBCid.ifPresent(cid -> canon.add(cid.getContractId));
                        }
                    }
                    return canon;
                });
        // 2) Fetch party tokens and filter out canonicals
        return canonSetFut.thenCompose(canon -> ledger.getActiveContractsForParty(Token.class, party)
                .thenApply(contracts -> contracts.stream()
                        .filter(c -> c.payload.getOwner.getParty.equals(party))
                        .filter(c -> !canon.contains(c.contractId.getContractId))
                        .map(c -> new TokenDTO(
                                c.payload.getSymbol,
                                c.payload.getSymbol + " Token",
                                10,
                                c.payload.getAmount.toPlainString(),
                                c.payload.getOwner.getParty
                        ))
                        .toList())
        ).whenComplete((result, ex) -> {
            if (ex != null) {
                logger.error("Failed to fetch wallet tokens for party {}: {}", party, ex.getMessage());
            } else {
                logger.info("Fetched {} wallet tokens for party {}", result.size(), party);
            }
        });
    }

    /**
     * Get LP tokens owned by a party (liquidity positions)
     */
    @WithSpan
    public CompletableFuture<java.util.List<LpTokenDTO>> lpTokensForParty(String party) {
        logger.info("Fetching LP tokens for party: {}", party);
        return ledger.getActiveContractsForParty(LPToken.class, party)
                .thenApply(contracts -> contracts.stream()
                        .filter(c -> c.payload.getOwner.getParty.equals(party))
                        .map(c -> new LpTokenDTO(
                                c.payload.getPoolId,
                                c.payload.getAmount.toPlainString(),
                                c.contractId.getContractId,
                                c.payload.getOwner.getParty
                        ))
                        .toList())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        logger.error("Failed to fetch LP tokens for party {}: {}", party, ex.getMessage());
                    } else {
                        logger.info("Fetched {} LP tokens for party {}", result.size(), party);
                    }
                });
    }

    /**
     * Get all active liquidity pools
     * Returns pools in frontend-friendly DTO format
     * Primary: Ledger API (Canton 3.4.7 EventFormat)
     * Fallback: PQS if Ledger API fails
     */
    @WithSpan
    public CompletableFuture<List<PoolDTO>> pools() {
        logger.info("Fetching all active pools (Ledger API primary)");
        final java.util.Set<String> showcasePoolIds = java.util.Set.of("cc-cbtc-showcase");
        return ledger.getActiveContracts(Pool.class)
            .thenApply(contracts -> {
                LinkedHashMap<String, PoolDTO> bestPools = new LinkedHashMap<>();
                for (var c : contracts) {
                    if (!showcasePoolIds.contains(c.payload.getPoolId)) {
                        continue;
                    }
                    double volume24h = volumeMetricsService.getVolume24h(
                        c.payload.getSymbolA,
                        c.payload.getSymbolB
                    );
                    PoolDTO dto = new PoolDTO(
                        c.payload.getPoolId,
                        new PoolDTO.TokenInfoDTO(c.payload.getSymbolA, c.payload.getSymbolA, 10),
                        new PoolDTO.TokenInfoDTO(c.payload.getSymbolB, c.payload.getSymbolB, 10),
                        c.payload.getReserveA.toPlainString(),
                        c.payload.getReserveB.toPlainString(),
                        c.payload.getTotalLPSupply.toPlainString(),
                        convertFeeBpsToRate(c.payload.getFeeBps),
                        String.format("%.2f", volume24h)
                    );
                    bestPools.merge(
                        dto.poolId,
                        dto,
                        (existing, incoming) -> {
                            BigDecimal existingTvl = new BigDecimal(existing.reserveA)
                                .multiply(new BigDecimal(existing.reserveB));
                            BigDecimal incomingTvl = new BigDecimal(incoming.reserveA)
                                .multiply(new BigDecimal(incoming.reserveB));
                            return incomingTvl.compareTo(existingTvl) > 0 ? incoming : existing;
                        }
                    );
                }
                return List.copyOf(bestPools.values());
            })
            .exceptionallyCompose(ex -> {
                // Fallback to PQS if Ledger API fails and PQS is available
                if (pqs != null) {
                    logger.warn("Ledger API failed, falling back to PQS: {}", ex.getMessage());
                    return pqs.active(Pool.class)
                        .thenApply(contracts -> {
                            LinkedHashMap<String, PoolDTO> bestPools = new LinkedHashMap<>();
                            for (var c : contracts) {
                                if (!showcasePoolIds.contains(c.payload.getPoolId)) {
                                    continue;
                                }
                                double volume24h = volumeMetricsService.getVolume24h(
                                    c.payload.getSymbolA,
                                    c.payload.getSymbolB
                                );
                                PoolDTO dto = new PoolDTO(
                                    c.payload.getPoolId,
                                    new PoolDTO.TokenInfoDTO(c.payload.getSymbolA, c.payload.getSymbolA, 10),
                                    new PoolDTO.TokenInfoDTO(c.payload.getSymbolB, c.payload.getSymbolB, 10),
                                    c.payload.getReserveA.toPlainString(),
                                    c.payload.getReserveB.toPlainString(),
                                    c.payload.getTotalLPSupply.toPlainString(),
                                    convertFeeBpsToRate(c.payload.getFeeBps),
                                    String.format("%.2f", volume24h)
                                );
                                bestPools.merge(
                                    dto.poolId,
                                    dto,
                                    (existing, incoming) -> {
                                        BigDecimal existingTvl = new BigDecimal(existing.reserveA)
                                            .multiply(new BigDecimal(existing.reserveB));
                                        BigDecimal incomingTvl = new BigDecimal(incoming.reserveA)
                                            .multiply(new BigDecimal(incoming.reserveB));
                                        return incomingTvl.compareTo(existingTvl) > 0 ? incoming : existing;
                                    }
                                );
                            }
                            return List.copyOf(bestPools.values());
                        });
                } else {
                    return CompletableFuture.failedFuture(ex);
                }
            })
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
