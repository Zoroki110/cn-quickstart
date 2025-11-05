package com.digitalasset.quickstart.controller;

import clearportx_amm_production.amm.pool.Pool;
import clearportx_amm_production.token.token.Token;
import clearportx_amm_production.lptoken.lptoken.LPToken;
import com.digitalasset.quickstart.dto.AddLiquidityRequest;
import clearportx_amm_production.amm.swaprequest.SwapRequest;
import clearportx_amm_production.amm.swaprequest.SwapReady;
import com.digitalasset.quickstart.ledger.LedgerApi;
// import com.digitalasset.quickstart.service.PartyRegistryService;
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

    // @Autowired
    // private PartyRegistryService partyRegistry;

    @PostMapping("/create-pool-direct")
    public ResponseEntity<?> createPoolDirect(@RequestBody CreatePoolRequest request) {
        logger.info("=== DIRECT POOL CREATION REQUEST ===");
        logger.info("Request: {}", request);

        Map<String, Object> result = new HashMap<>();
        List<String> steps = new ArrayList<>();

        try {
            // Step 1: Using provided party IDs directly (assuming they are fully qualified)
            steps.add("Using provided party IDs");
            logger.info("Step 1: Using provided party IDs directly...");

            String operatorFqid = request.operatorParty;
            String poolPartyFqid = request.poolParty;
            String ethIssuerFqid = request.ethIssuer;
            String usdcIssuerFqid = request.usdcIssuer;
            String lpIssuerFqid = request.lpIssuer;
            String feeReceiverFqid = request.feeReceiver;

            logger.info("  Operator: {}", operatorFqid);
            logger.info("  Pool party: {}", poolPartyFqid);
            logger.info("  ETH issuer: {}", ethIssuerFqid);
            logger.info("  USDC issuer: {}", usdcIssuerFqid);
            logger.info("  LP issuer: {}", lpIssuerFqid);
            logger.info("  Fee receiver: {}", feeReceiverFqid);

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

    @PostMapping("/mint-tokens")
    public ResponseEntity<?> mintTokens(@RequestBody MintTokensRequest request) {
        logger.info("=== MINT TOKENS REQUEST ===");
        logger.info("Request: {}", request);

        Map<String, Object> result = new HashMap<>();
        List<String> steps = new ArrayList<>();

        try {
            steps.add("Minting tokens for " + request.ownerParty);
            logger.info("Minting {} {} and {} {} for {}",
                request.amountA, request.symbolA, request.amountB, request.symbolB, request.ownerParty);

            // Mint Token A
            Token tokenA = new Token(
                new Party(request.issuerParty),
                new Party(request.ownerParty),
                request.symbolA,
                new BigDecimal(request.amountA)
            );

            ContractId<Token> tokenACid = ledgerApi.createAndGetCid(
                tokenA,
                List.of(request.issuerParty),
                Collections.emptyList(),
                UUID.randomUUID().toString(),
                clearportx_amm_production.Identifiers.Token_Token__Token
            ).join();

            logger.info("  ✓ {} token created: {}", request.symbolA, tokenACid);
            result.put("tokenACid", tokenACid.toString());

            // Mint Token B
            Token tokenB = new Token(
                new Party(request.issuerParty),
                new Party(request.ownerParty),
                request.symbolB,
                new BigDecimal(request.amountB)
            );

            ContractId<Token> tokenBCid = ledgerApi.createAndGetCid(
                tokenB,
                List.of(request.issuerParty),
                Collections.emptyList(),
                UUID.randomUUID().toString(),
                clearportx_amm_production.Identifiers.Token_Token__Token
            ).join();

            logger.info("  ✓ {} token created: {}", request.symbolB, tokenBCid);
            result.put("tokenBCid", tokenBCid.toString());

            result.put("success", true);
            result.put("steps", steps);
            result.put("message", "Tokens minted successfully!");

            logger.info("=== TOKENS MINTED SUCCESSFULLY ===");

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Token minting failed", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("steps", steps);
            return ResponseEntity.status(500).body(result);
        }
    }

    public static class MintTokensRequest {
        public String issuerParty;
        public String ownerParty;
        public String symbolA;
        public String amountA;
        public String symbolB;
        public String amountB;

        @Override
        public String toString() {
            return String.format("MintTokensRequest{issuer=%s, owner=%s, %s: %s, %s: %s}",
                issuerParty, ownerParty, symbolA, amountA, symbolB, amountB);
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

    @PostMapping("/add-liquidity")
    public ResponseEntity<?> addLiquidityDebug(@RequestBody AddLiquidityRequest req) {
        logger.info("=== ADD LIQUIDITY (DEBUG) REQUEST ===");
        logger.info("poolId: {}, amountA: {}, amountB: {}, minLP: {}", req.poolId, req.amountA, req.amountB, req.minLPTokens);

        Map<String, Object> result = new HashMap<>();
        List<String> steps = new ArrayList<>();

        try {
            steps.add("Locate pool");
            var pools = ledgerApi.getActiveContracts(Pool.class).join();
            Optional<LedgerApi.ActiveContract<Pool>> maybePool = pools.stream()
                .filter(p -> p.payload.getPoolId.equals(req.poolId))
                .findFirst();
            if (maybePool.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "error", "Pool not found: " + req.poolId
                ));
            }
            var pool = maybePool.get();
            var poolPayload = pool.payload;

            String liquidityProvider = poolPayload.getPoolParty.getParty; // Use pool party as provider for debug
            String poolParty = poolPayload.getPoolParty.getParty;
            String lpIssuer = poolPayload.getLpIssuer.getParty;

            steps.add("Find provider tokens");
            var tokens = ledgerApi.getActiveContracts(Token.class).join();
            Optional<LedgerApi.ActiveContract<Token>> maybeTokenA = tokens.stream()
                .filter(t -> t.payload.getSymbol.equals(poolPayload.getSymbolA) &&
                             t.payload.getOwner.getParty.equals(liquidityProvider))
                .findFirst();
            Optional<LedgerApi.ActiveContract<Token>> maybeTokenB = tokens.stream()
                .filter(t -> t.payload.getSymbol.equals(poolPayload.getSymbolB) &&
                             t.payload.getOwner.getParty.equals(liquidityProvider))
                .findFirst();
            if (maybeTokenA.isEmpty() || maybeTokenB.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "error", "Provider tokens not found for symbols: " + poolPayload.getSymbolA + ", " + poolPayload.getSymbolB
                ));
            }

            var tokenA = maybeTokenA.get();
            var tokenB = maybeTokenB.get();

            steps.add("Exercise AddLiquidity");
            var deadline = java.time.Instant.now().plusSeconds(600);
            Pool.AddLiquidity choice = new Pool.AddLiquidity(
                new com.digitalasset.transcode.java.Party(liquidityProvider),
                new com.digitalasset.transcode.java.ContractId<>(tokenA.contractId.getContractId),
                new com.digitalasset.transcode.java.ContractId<>(tokenB.contractId.getContractId),
                req.amountA,
                req.amountB,
                req.minLPTokens,
                deadline
            );

            String commandId = java.util.UUID.randomUUID().toString();
            var tuple = ledgerApi.exerciseAndGetResultWithParties(
                pool.contractId,
                choice,
                commandId,
                java.util.List.of(liquidityProvider, poolParty, lpIssuer),
                java.util.List.of(poolParty)
            ).join();

            com.digitalasset.transcode.java.ContractId<LPToken> lpTokenCid = tuple.get_1;
            com.digitalasset.transcode.java.ContractId<Pool> newPoolCid = tuple.get_2;

            result.put("success", true);
            result.put("lpTokenCid", lpTokenCid.getContractId);
            result.put("newPoolCid", newPoolCid.getContractId);
            result.put("steps", steps);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Add liquidity (debug) failed", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("steps", steps);
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/swap-debug")
    public ResponseEntity<?> swapDebug(@RequestBody Map<String, Object> body) {
        String poolId = String.valueOf(body.getOrDefault("poolId", ""));
        String inputSymbol = String.valueOf(body.getOrDefault("inputSymbol", ""));
        String outputSymbol = String.valueOf(body.getOrDefault("outputSymbol", ""));
        java.math.BigDecimal amountIn = new java.math.BigDecimal(String.valueOf(body.getOrDefault("amountIn", "0")));
        java.math.BigDecimal minOutput = new java.math.BigDecimal(String.valueOf(body.getOrDefault("minOutput", "0")));

        Map<String, Object> result = new HashMap<>();
        List<String> steps = new ArrayList<>();

        try {
            if (poolId.isBlank() || inputSymbol.isBlank() || outputSymbol.isBlank() || amountIn.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "poolId, inputSymbol, outputSymbol and amountIn are required"
                ));
            }

            steps.add("Locate pool");
            var pools = ledgerApi.getActiveContracts(Pool.class).join();
            var poolOpt = pools.stream().filter(p -> p.payload.getPoolId.equals(poolId)).findFirst();
            if (poolOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Pool not found: " + poolId));
            }
            var pool = poolOpt.get();
            var poolPayload = pool.payload;
            String trader = poolPayload.getPoolParty.getParty; // Use pool party for demo trading
            String poolParty = poolPayload.getPoolParty.getParty;
            String poolOperator = poolPayload.getPoolOperator.getParty;

            steps.add("Find trader input token");
            var tokens = ledgerApi.getActiveContracts(Token.class).join();
            var inputTokenOpt = tokens.stream()
                .filter(t -> t.payload.getSymbol.equals(inputSymbol) && t.payload.getOwner.getParty.equals(trader))
                .findFirst();
            if (inputTokenOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Input token not found for trader"));
            }
            var inputToken = inputTokenOpt.get();
            if (inputToken.payload.getAmount.compareTo(amountIn) < 0) {
                return ResponseEntity.status(422).body(Map.of("success", false, "error", "Insufficient input balance"));
            }

            steps.add("Create SwapRequest and prepare");
            java.time.Instant deadline = java.time.Instant.now().plusSeconds(600);
            long maxPriceImpactBps = 5000L; // 50% max per contract constraint

            SwapRequest swapReq = new SwapRequest(
                new com.digitalasset.transcode.java.Party(trader),
                new com.digitalasset.transcode.java.ContractId<>(pool.contractId.getContractId),
                new com.digitalasset.transcode.java.Party(poolParty),
                new com.digitalasset.transcode.java.Party(poolOperator),
                poolPayload.getIssuerA,
                poolPayload.getIssuerB,
                poolPayload.getSymbolA,
                poolPayload.getSymbolB,
                poolPayload.getFeeBps,
                poolPayload.getMaxTTL,
                new com.digitalasset.transcode.java.ContractId<>(inputToken.contractId.getContractId),
                inputSymbol,
                amountIn,
                outputSymbol,
                minOutput.compareTo(java.math.BigDecimal.ZERO) > 0 ? minOutput : java.math.BigDecimal.ONE,
                deadline,
                maxPriceImpactBps
            );

            SwapRequest.PrepareSwap prepare = new SwapRequest.PrepareSwap(new com.digitalasset.transcode.java.Party(poolOperator));
            String baseCmd = java.util.UUID.randomUUID().toString();

            var swapRequestCid = ledgerApi.createAndGetCid(
                swapReq,
                java.util.List.of(trader),
                java.util.List.of(poolParty),
                baseCmd + "-create",
                swapReq.templateId()
            ).join();

            var prepared = ledgerApi.exerciseAndGetResult(
                swapRequestCid,
                prepare,
                baseCmd + "-prepare"
            ).join();

            com.digitalasset.transcode.java.ContractId<SwapReady> swapReadyCid = prepared.get_1;

            steps.add("Execute swap");
            SwapReady.ExecuteSwap exec = new SwapReady.ExecuteSwap();
            var receiptCid = ledgerApi.exerciseAndGetResult(
                swapReadyCid,
                exec,
                baseCmd + "-execute"
            ).join();

            result.put("success", true);
            result.put("swapReadyCid", swapReadyCid.getContractId);
            result.put("receiptCid", receiptCid.getContractId);
            result.put("steps", steps);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Swap (debug) failed", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("steps", steps);
            return ResponseEntity.status(500).body(result);
        }
    }
}
