// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import clearportx_amm_production_gv.amm.pool.Pool;
import clearportx_amm_production_gv.token.token.Token;
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
        // Use party override to read the caller's authoritative ACS (not just app provider's view)
        return ledger.getActiveContractsForParty(Token.class, party)
                .thenApply(contracts -> contracts.stream()
                        .filter(c -> c.payload.getOwner.getParty.equals(party))
                        .map(c -> new TokenDTO(
                                c.payload.getSymbol,
                                c.payload.getSymbol + " Token",
                                10,
                                c.payload.getAmount.toPlainString(),
                                c.payload.getOwner.getParty,
                                c.contractId.getContractId
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
                                c.payload.getOwner.getParty,
                                c.contractId.getContractId
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
