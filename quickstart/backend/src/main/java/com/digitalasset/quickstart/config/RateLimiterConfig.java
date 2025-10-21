// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.config;

import com.digitalasset.quickstart.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token bucket rate limiter for Canton Network devnet compliance.
 *
 * Devnet Requirement: 0.5 TPS (transactions per second) GLOBAL across all nodes
 * Implementation: 0.4 TPS global limit (safely under requirement)
 *
 * ⚠️  CRITICAL LIMITATION - NOT CLUSTER-SAFE:
 * This in-memory rate limiter is LOCAL to a single JVM instance.
 * If deploying multiple pods/replicas, the ACTUAL global rate can exceed 0.5 TPS.
 *
 * For production devnet with multiple replicas, you MUST use:
 * - Distributed token bucket (Redis/Memcached with Lua scripts)
 * - Central rate limiting service (e.g., Envoy global rate limit)
 * - Load balancer rate limiting (e.g., NGINX rate_limit_req zone)
 *
 * Two-tier limiting:
 * 1. Global: 1 request per 2.5 seconds (0.4 TPS) across all parties
 * 2. Per-party: 10 requests per minute (0.167 TPS per party)
 *
 * Returns HTTP 429 with Retry-After header when rate limit exceeded.
 *
 * Configuration:
 * - rate-limiter.enabled=true/false (default: false for localnet)
 * - rate-limiter.global-tps=0.4 (for devnet)
 * - rate-limiter.per-party-rpm=10 (requests per minute per party)
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
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RateLimitInterceptor rateLimitInterceptor(Clock clock,
                                                     @Autowired(required = false) DistributedRateLimiter distributedLimiter) {
        long globalIntervalMs = (long) (1000.0 / globalTps);
        long perPartyIntervalMs = (long) (60000.0 / perPartyRpm);

        if (distributedLimiter != null) {
            logger.info("✓ Using DISTRIBUTED rate limiter (cluster-safe via Redis)");
        } else {
            logger.warn("⚠️  Rate limiter initialized: global={} TPS ({}ms), per-party={} RPM ({}ms)",
                globalTps, globalIntervalMs, perPartyRpm, perPartyIntervalMs);
            logger.warn("⚠️  WARNING: This rate limiter is LOCAL to this JVM instance only!");
            logger.warn("⚠️  For multi-pod deployments, set rate-limiter.distributed=true and configure Redis");
        }

        return new RateLimitInterceptor(globalIntervalMs, perPartyIntervalMs, globalTps, perPartyRpm, clock, distributedLimiter);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor(clock(), null));
    }

    /**
     * Interceptor that applies rate limiting to swap endpoints.
     * Uses simple token bucket algorithm with AtomicLong timestamps.
     */
    public class RateLimitInterceptor implements HandlerInterceptor {

        private final long globalIntervalMs;
        private final long perPartyIntervalMs;
        private final double globalTps;
        private final int perPartyRpm;
        private final Clock clock;
        private final DistributedRateLimiter distributedLimiter;

        // Global rate limiter state (local fallback)
        private final AtomicLong lastGlobalRequest = new AtomicLong(0);

        // Per-party rate limiter state (local fallback)
        private final Map<String, AtomicLong> partyLastRequest = new ConcurrentHashMap<>();

        public RateLimitInterceptor(long globalIntervalMs, long perPartyIntervalMs, double globalTps, int perPartyRpm, Clock clock, DistributedRateLimiter distributedLimiter) {
            this.globalIntervalMs = globalIntervalMs;
            this.perPartyIntervalMs = perPartyIntervalMs;
            this.globalTps = globalTps;
            this.perPartyRpm = perPartyRpm;
            this.clock = clock;
            this.distributedLimiter = distributedLimiter;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            String path = request.getRequestURI();

            // Only rate-limit write operations (swaps, liquidity changes)
            if (!isRateLimitedEndpoint(path)) {
                return true;
            }

            String party = extractParty(request);

            // Use distributed limiter if available, otherwise fall back to local
            if (distributedLimiter != null) {
                return handleDistributedRateLimit(request, response, path, party);
            } else {
                return handleLocalRateLimit(request, response, path, party);
            }
        }

        private boolean handleDistributedRateLimit(HttpServletRequest request, HttpServletResponse response, String path, String party) throws Exception {
            var now = clock.instant();

            if (!distributedLimiter.tryAcquire(party, now)) {
                int retryAfterSeconds = distributedLimiter.getRetryAfterSeconds(party, now);

                logger.warn("⚠️  Distributed rate limit exceeded for {} {}, retry in {}s",
                    request.getMethod(), path, retryAfterSeconds);

                writeRateLimitResponse(response, path, request, retryAfterSeconds);
                return false;
            }

            logger.debug("✓ Distributed rate limit check passed for {} {}", request.getMethod(), path);
            return true;
        }

        private boolean handleLocalRateLimit(HttpServletRequest request, HttpServletResponse response, String path, String party) throws Exception {
            long now = clock.millis();  // Use injected Clock for testability

            // Check BOTH global and per-party limits, take the MAXIMUM wait time
            long globalWaitTime = getGlobalRateLimitWaitTime(now);
            long partyWaitTime = party != null ? getPartyRateLimitWaitTime(party, now) : 0;

            long maxWaitTime = Math.max(globalWaitTime, partyWaitTime);

            if (maxWaitTime > 0) {
                int retryAfterSeconds = (int) Math.ceil(maxWaitTime / 1000.0);
                logger.warn("⚠️  Local rate limit exceeded for {} {}, retry in {}s",
                    request.getMethod(), path, retryAfterSeconds);
                writeRateLimitResponse(response, path, request, retryAfterSeconds);
                return false;
            }

            logger.debug("✓ Rate limit check passed for {} {}", request.getMethod(), path);
            return true;
        }

        private void writeRateLimitResponse(HttpServletResponse response, String path, HttpServletRequest request, int retryAfterSeconds) throws Exception {
            ErrorResponse errorResponse = new ErrorResponse(
                "RATE_LIMIT_EXCEEDED",
                "Rate limit exceeded for devnet compliance",
                HttpStatus.TOO_MANY_REQUESTS.value(),
                path
            );
            errorResponse.setRetryAfter(retryAfterSeconds);

            String requestId = request.getHeader("X-Request-ID");
            if (requestId != null) {
                errorResponse.setRequestId(requestId);
            }

            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setHeader("Vary", "Origin");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json; charset=UTF-8");

            String responseBody = new ObjectMapper().writeValueAsString(errorResponse);
            response.getWriter().write(responseBody);
            response.getWriter().flush();
            response.flushBuffer();
        }

        private boolean isRateLimitedEndpoint(String path) {
            // Rate-limit write operations that create ledger transactions
            return path.startsWith("/api/swap/") ||
                   path.startsWith("/api/liquidity/") ||
                   path.startsWith("/api/init/");
        }

        /**
         * Get time to wait (in ms) before next global request is allowed.
         * Returns 0 if request can proceed immediately.
         */
        private long getGlobalRateLimitWaitTime(long now) {
            long lastRequest = lastGlobalRequest.get();
            long timeSinceLastRequest = now - lastRequest;

            if (timeSinceLastRequest < globalIntervalMs) {
                return globalIntervalMs - timeSinceLastRequest;
            }

            // Try to claim the token
            if (lastGlobalRequest.compareAndSet(lastRequest, now)) {
                return 0; // Success - request can proceed
            }

            // CAS failed - someone else claimed it, retry
            return getGlobalRateLimitWaitTime(now);
        }

        /**
         * Check global rate limit using token bucket algorithm.
         * Allows 1 request every globalIntervalMs milliseconds.
         * @deprecated Use getGlobalRateLimitWaitTime instead
         */
        @Deprecated
        private boolean checkGlobalRateLimit(long now) {
            return getGlobalRateLimitWaitTime(now) == 0;
        }

        /**
         * Get time to wait (in ms) before next request is allowed for a specific party.
         * Returns 0 if request can proceed immediately.
         */
        private long getPartyRateLimitWaitTime(String party, long now) {
            AtomicLong lastRequest = partyLastRequest.computeIfAbsent(party, k -> new AtomicLong(0));
            long lastRequestTime = lastRequest.get();
            long timeSinceLastRequest = now - lastRequestTime;

            if (timeSinceLastRequest < perPartyIntervalMs) {
                return perPartyIntervalMs - timeSinceLastRequest;
            }

            // Try to claim the token
            if (lastRequest.compareAndSet(lastRequestTime, now)) {
                return 0; // Success - request can proceed
            }

            // CAS failed - someone else claimed it, retry
            return getPartyRateLimitWaitTime(party, now);
        }

        /**
         * Check per-party rate limit using token bucket algorithm.
         * Allows perPartyRpm requests per minute for each party.
         * @deprecated Use getPartyRateLimitWaitTime instead
         */
        @Deprecated
        private boolean checkPartyRateLimit(String party, long now) {
            return getPartyRateLimitWaitTime(party, now) == 0;
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
