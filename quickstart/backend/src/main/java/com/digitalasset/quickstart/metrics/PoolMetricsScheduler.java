// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.metrics;

import clearportx_amm.amm.pool.Pool;
import com.digitalasset.quickstart.ledger.LedgerApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Scheduled task to update pool metrics from Ledger API.
 *
 * Updates active pools count every 10 seconds independently of traffic.
 * This ensures metrics remain accurate even when no swaps are happening.
 *
 * CRITICAL: Do NOT trigger metrics updates from controllers.
 * Controller-based updates are traffic-dependent and unreliable.
 *
 * Configuration:
 * - Fixed rate: 10 seconds (configurable via scheduled.pool-metrics-interval-ms)
 * - Timeout: 500ms (fail-fast to avoid blocking)
 * - Resilience: Preserves last known value on timeout/error
 *
 * Active pool definition:
 * - Contract is NOT archived (present in ACS)
 * - reserveA > 0 AND reserveB > 0 (has liquidity)
 */
@Component
public class PoolMetricsScheduler {

    private static final Logger logger = LoggerFactory.getLogger(PoolMetricsScheduler.class);
    private static final long LEDGER_API_TIMEOUT_MS = 500;

    private final LedgerApi ledgerApi;
    private final SwapMetrics swapMetrics;

    // Last known value (preserved on error)
    private volatile int lastKnownActivePoolsCount = 0;

    public PoolMetricsScheduler(LedgerApi ledgerApi, SwapMetrics swapMetrics) {
        this.ledgerApi = ledgerApi;
        this.swapMetrics = swapMetrics;
    }

    /**
     * Update active pools count every 10 seconds.
     *
     * Fixed rate ensures consistent updates regardless of traffic.
     * Timeout prevents hanging on Ledger API unavailability.
     */
    @Scheduled(fixedRateString = "${scheduled.pool-metrics-interval-ms:10000}", initialDelay = 5000)
    public void updateActivePoolsCount() {
        try {
            // Query ACS with timeout to avoid blocking
            int activePoolsCount = ledgerApi.getActiveContracts(Pool.class)
                .orTimeout(LEDGER_API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .thenApply(pools -> (int) pools.stream()
                    .filter(p -> p.payload.getReserveA.compareTo(BigDecimal.ZERO) > 0)
                    .filter(p -> p.payload.getReserveB.compareTo(BigDecimal.ZERO) > 0)
                    .count())
                .join();

            // Update metric
            swapMetrics.setActivePoolsCount(activePoolsCount);
            lastKnownActivePoolsCount = activePoolsCount;

            logger.debug("✓ Active pools gauge updated: {} pools", activePoolsCount);

        } catch (Exception ex) {
            // Preserve last known value on error
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

            if (cause instanceof TimeoutException) {
                logger.warn("⚠️  Ledger API timeout while updating pools gauge (>{}ms), preserving last value: {}",
                    LEDGER_API_TIMEOUT_MS, lastKnownActivePoolsCount);
            } else {
                logger.warn("⚠️  Failed to update pools gauge: {}, preserving last value: {}",
                    cause.getMessage(), lastKnownActivePoolsCount);
            }

            // Keep metric at last known value (don't update)
        }
    }

    /**
     * Get last known active pools count (for monitoring).
     */
    public int getLastKnownActivePoolsCount() {
        return lastKnownActivePoolsCount;
    }
}
