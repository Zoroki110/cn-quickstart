// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple token bucket rate limiter for Canton Network devnet compliance.
 *
 * Devnet Requirement: 0.5 TPS (transactions per second)
 * Implementation: 0.4 TPS global limit (safely under requirement)
 *
 * Two-tier limiting:
 * 1. Global: 1 request per 2.5 seconds (0.4 TPS) across all parties
 * 2. Per-party: 10 requests per minute (0.167 TPS per party)
 *
 * Returns HTTP 429 when rate limit exceeded.
 *
 * Configuration:
 * - rate-limiter.enabled=true/false (default: false for localnet)
 * - rate-limiter.global-tps=0.4 (for devnet)
 * - rate-limiter.per-party-rpm=10 (requests per minute per party)
 *
 * Simple implementation using AtomicLong for token bucket - no external dependencies.
 */
@Configuration
@ConditionalOnProperty(name = "rate-limiter.enabled", havingValue = "true", matchIfMissing = false)
public class RateLimiterConfig implements WebMvcConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiterConfig.class);

    @Value("${rate-limiter.global-tps:0.4}")
    private double globalTps;

    @Value("${rate-limiter.per-party-rpm:10}")
    private int perPartyRpm;

    @Bean
    public RateLimitInterceptor rateLimitInterceptor() {
        long globalIntervalMs = (long) (1000.0 / globalTps);
        long perPartyIntervalMs = (long) (60000.0 / perPartyRpm);

        logger.info("✓ Rate limiter initialized: global={} TPS ({}ms), per-party={} RPM ({}ms)",
            globalTps, globalIntervalMs, perPartyRpm, perPartyIntervalMs);

        return new RateLimitInterceptor(globalIntervalMs, perPartyIntervalMs);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor());
    }

    /**
     * Interceptor that applies rate limiting to swap endpoints.
     * Uses simple token bucket algorithm with AtomicLong timestamps.
     */
    public class RateLimitInterceptor implements HandlerInterceptor {

        private final long globalIntervalMs;
        private final long perPartyIntervalMs;

        // Global rate limiter state
        private final AtomicLong lastGlobalRequest = new AtomicLong(0);

        // Per-party rate limiter state
        private final Map<String, AtomicLong> partyLastRequest = new ConcurrentHashMap<>();

        public RateLimitInterceptor(long globalIntervalMs, long perPartyIntervalMs) {
            this.globalIntervalMs = globalIntervalMs;
            this.perPartyIntervalMs = perPartyIntervalMs;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            String path = request.getRequestURI();

            // Only rate-limit write operations (swaps, liquidity changes)
            if (!isRateLimitedEndpoint(path)) {
                return true;
            }

            long now = System.currentTimeMillis();

            // Check global rate limit first (most restrictive)
            if (!checkGlobalRateLimit(now)) {
                logger.warn("⚠️  Global rate limit exceeded for {} {} (max {} TPS)",
                    request.getMethod(), path, globalTps);
                throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED: Global limit is " + globalTps + " TPS for devnet compliance"
                );
            }

            // Check per-party rate limit
            String party = extractParty(request);
            if (party != null && !checkPartyRateLimit(party, now)) {
                logger.warn("⚠️  Per-party rate limit exceeded for party {} on {} {}",
                    party, request.getMethod(), path);
                throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED: Per-party limit is " + perPartyRpm + " requests per minute"
                );
            }

            logger.debug("✓ Rate limit check passed for {} {}", request.getMethod(), path);
            return true;
        }

        private boolean isRateLimitedEndpoint(String path) {
            // Rate-limit write operations that create ledger transactions
            return path.startsWith("/api/swap/") ||
                   path.startsWith("/api/liquidity/") ||
                   path.startsWith("/api/init/");
        }

        /**
         * Check global rate limit using token bucket algorithm.
         * Allows 1 request every globalIntervalMs milliseconds.
         */
        private boolean checkGlobalRateLimit(long now) {
            while (true) {
                long lastRequest = lastGlobalRequest.get();
                long timeSinceLastRequest = now - lastRequest;

                // If not enough time has passed, deny the request
                if (timeSinceLastRequest < globalIntervalMs) {
                    return false;
                }

                // Try to update the timestamp atomically
                if (lastGlobalRequest.compareAndSet(lastRequest, now)) {
                    return true;
                }
                // If CAS failed, another thread updated it - retry
            }
        }

        /**
         * Check per-party rate limit using token bucket algorithm.
         * Allows perPartyRpm requests per minute for each party.
         */
        private boolean checkPartyRateLimit(String party, long now) {
            AtomicLong lastRequest = partyLastRequest.computeIfAbsent(party, k -> new AtomicLong(0));

            while (true) {
                long lastRequestTime = lastRequest.get();
                long timeSinceLastRequest = now - lastRequestTime;

                // If not enough time has passed, deny the request
                if (timeSinceLastRequest < perPartyIntervalMs) {
                    return false;
                }

                // Try to update the timestamp atomically
                if (lastRequest.compareAndSet(lastRequestTime, now)) {
                    return true;
                }
                // If CAS failed, another thread updated it - retry
            }
        }

        private String extractParty(HttpServletRequest request) {
            // Extract party from JWT claims or request attributes
            // This will be set by the OAuth2 security filter
            Object partyAttribute = request.getAttribute("party");
            if (partyAttribute != null) {
                return partyAttribute.toString();
            }

            // Fallback: extract from Authorization header (JWT)
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                // Party will be available from SecurityContext in production
                // For now, use a default party for testing
                return "default-party";
            }

            return null;
        }
    }
}
