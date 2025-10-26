package com.digitalasset.quickstart.controller;

import clearportx_amm_production.amm.pool.Pool;
import clearportx_amm_production.token.token.Token;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.service.PartyRegistryService;
import com.digitalasset.transcode.java.Party;
import com.digitalasset.transcode.java.ContractId;
import daml_stdlib_da_time_types.da.time.types.RelTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Direct pool creation endpoint for debugging.
 * Creates a pool using the backend's LedgerApi with full logging.
 */
@RestController
@RequestMapping("/api/debug")
public class PoolCreationController {

    private static final Logger logger = LoggerFactory.getLogger(PoolCreationController.class);

    @Autowired
    private LedgerApi ledgerApi;

    @Autowired
    private PartyRegistryService partyRegistry;

    @PostMapping("/create-pool-direct")
    public ResponseEntity<?> createPoolDirect(@RequestBody CreatePoolRequest request) {
        logger.info("=== DIRECT POOL CREATION REQUEST ===");
        logger.info("Request: {}", request);

        Map<String, Object> result = new HashMap<>();
        List<String> steps = new ArrayList<>();

        try {
            // Step 1: Resolve parties
            steps.add("Resolving parties");
            logger.info("Step 1: Resolving parties...");

            String operatorFqid = partyRegistry.resolve(request.operatorParty)
                .orElseThrow(() -> new IllegalArgumentException("Operator party not found: " + request.operatorParty));
            String poolPartyFqid = partyRegistry.resolve(request.poolParty)
                .orElseThrow(() -> new IllegalArgumentException("Pool party not found: " + request.poolParty));
            String ethIssuerFqid = partyRegistry.resolve(request.ethIssuer)
                .orElseThrow(() -> new IllegalArgumentException("ETH issuer not found: " + request.ethIssuer));
            String usdcIssuerFqid = partyRegistry.resolve(request.usdcIssuer)
                .orElseThrow(() -> new IllegalArgumentException("USDC issuer not found: " + request.usdcIssuer));
            String lpIssuerFqid = partyRegistry.resolve(request.lpIssuer)
                .orElseThrow(() -> new IllegalArgumentException("LP issuer not found: " + request.lpIssuer));
            String feeReceiverFqid = partyRegistry.resolve(request.feeReceiver)
                .orElseThrow(() -> new IllegalArgumentException("Fee receiver not found: " + request.feeReceiver));

            logger.info("  Operator: {} -> {}", request.operatorParty, operatorFqid);
            logger.info("  Pool party: {} -> {}", request.poolParty, poolPartyFqid);
            logger.info("  ETH issuer: {} -> {}", request.ethIssuer, ethIssuerFqid);
            logger.info("  USDC issuer: {} -> {}", request.usdcIssuer, usdcIssuerFqid);
            logger.info("  LP issuer: {} -> {}", request.lpIssuer, lpIssuerFqid);
            logger.info("  Fee receiver: {} -> {}", request.feeReceiver, feeReceiverFqid);

            result.put("parties", Map.of(
                "operator", operatorFqid,
                "poolParty", poolPartyFqid,
                "ethIssuer", ethIssuerFqid,
                "usdcIssuer", usdcIssuerFqid,
                "lpIssuer", lpIssuerFqid,
                "feeReceiver", feeReceiverFqid
            ));

            // Step 2: Create ETH token
            steps.add("Creating ETH token");
            logger.info("Step 2: Creating ETH token (100 ETH)...");

            Token ethToken = new Token(
                new Party(ethIssuerFqid),
                new Party(poolPartyFqid),
                "ETH",
                new BigDecimal("100.0")
            );

            ContractId<Token> ethTokenCid = ledgerApi.createAndGetCid(
                ethToken,
                List.of(ethIssuerFqid),
                Collections.emptyList(),
                UUID.randomUUID().toString(),
                clearportx_amm_production.Identifiers.Token_Token__Token
            ).join();

            logger.info("  ✓ ETH token created: {}", ethTokenCid);
            result.put("ethTokenCid", ethTokenCid.toString());

            // Step 3: Create USDC token
            steps.add("Creating USDC token");
            logger.info("Step 3: Creating USDC token (200,000 USDC)...");

            Token usdcToken = new Token(
                new Party(usdcIssuerFqid),
                new Party(poolPartyFqid),
                "USDC",
                new BigDecimal("200000.0")
            );

            ContractId<Token> usdcTokenCid = ledgerApi.createAndGetCid(
                usdcToken,
                List.of(usdcIssuerFqid),
                Collections.emptyList(),
                UUID.randomUUID().toString(),
                clearportx_amm_production.Identifiers.Token_Token__Token
            ).join();

            logger.info("  ✓ USDC token created: {}", usdcTokenCid);
            result.put("usdcTokenCid", usdcTokenCid.toString());

            // Step 4: Create Pool contract
            steps.add("Creating Pool contract");
            logger.info("Step 4: Creating Pool contract...");

            Pool pool = new Pool(
                new Party(operatorFqid),
                new Party(poolPartyFqid),
                new Party(lpIssuerFqid),
                new Party(ethIssuerFqid),
                new Party(usdcIssuerFqid),
                "ETH",
                "USDC",
                30L,
                "eth-usdc-direct",
                new RelTime(86400000000L),
                new BigDecimal("0.0"),
                new BigDecimal("0.0"),
                new BigDecimal("0.0"),
                Optional.empty(),
                Optional.empty(),
                new Party(feeReceiverFqid),
                10000L,
                5000L
            );

            ContractId<Pool> poolCid = ledgerApi.createAndGetCid(
                pool,
                List.of(operatorFqid, poolPartyFqid),
                Collections.emptyList(),
                UUID.randomUUID().toString(),
                clearportx_amm_production.Identifiers.AMM_Pool__Pool
            ).join();

            logger.info("  ✓ Pool created: {}", poolCid);
            result.put("poolCid", poolCid.toString());

            // Step 5: Verify pool is visible
            steps.add("Verifying pool visibility");
            logger.info("Step 5: Verifying pool visibility...");

            var pools = ledgerApi.getActiveContracts(Pool.class).join();
            logger.info("  Operator sees {} pool(s)", pools.size());

            result.put("poolCount", pools.size());
            result.put("success", true);
            result.put("steps", steps);
            result.put("message", "Pool created successfully!");

            logger.info("=== POOL CREATION SUCCESSFUL ===");

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Pool creation failed at step: {}", steps.isEmpty() ? "unknown" : steps.get(steps.size() - 1), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("failedAt", steps.isEmpty() ? "unknown" : steps.get(steps.size() - 1));
            result.put("steps", steps);
            return ResponseEntity.status(500).body(result);
        }
    }

    public static class CreatePoolRequest {
        public String operatorParty;
        public String poolParty;
        public String ethIssuer;
        public String usdcIssuer;
        public String lpIssuer;
        public String feeReceiver;

        @Override
        public String toString() {
            return String.format("CreatePoolRequest{op=%s, pool=%s, ethIss=%s, usdcIss=%s, lpIss=%s, fee=%s}",
                operatorParty, poolParty, ethIssuer, usdcIssuer, lpIssuer, feeReceiver);
        }
    }
}
