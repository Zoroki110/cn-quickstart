// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.PoolDTO;
import com.digitalasset.quickstart.dto.TokenDTO;
import com.digitalasset.quickstart.security.PartyMappingService;
import com.digitalasset.quickstart.service.LedgerReader;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * LedgerController - Authoritative contract reads via Ledger API
 *
 * Provides REST endpoints for reading tokens and pools directly from Canton.
 * No PQS lag, no package allowlist issues, always authoritative.
 *
 * CORS configured globally in WebSecurityConfig
 */
@RestController
@RequestMapping("/api")
public class LedgerController {
    private static final Logger logger = LoggerFactory.getLogger(LedgerController.class);
    private final LedgerReader reader;
    private final PartyMappingService partyMappingService;

    public LedgerController(LedgerReader reader, PartyMappingService partyMappingService) {
        this.reader = reader;
        this.partyMappingService = partyMappingService;
    }

    /**
     * GET /api/tokens - Get all tokens for the authenticated user
     * Requires JWT authentication - party is extracted from JWT subject
     */
    @GetMapping("/tokens")
    @WithSpan
    public CompletableFuture<List<TokenDTO>> tokens(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            logger.error("GET /api/tokens called without valid JWT");
            throw new IllegalArgumentException("Authentication required - JWT subject missing");
        }

        String jwtSubject = jwt.getSubject();
        String cantonParty = partyMappingService.mapJwtSubjectToParty(jwtSubject);
        logger.info("GET /api/tokens - JWT subject: {}, Canton party: {}", jwtSubject, cantonParty);
        return reader.tokensForParty(cantonParty);
    }

    /**
     * GET /api/pools - Get all active liquidity pools
     */
    @GetMapping("/pools")
    @WithSpan
    public CompletableFuture<List<PoolDTO>> pools() {
        logger.info("GET /api/pools");
        return reader.pools();
    }
}
