package com.digitalasset.quickstart.controller;

import clearportx_amm_drain_credit.amm.pool.Pool;
import clearportx_amm_drain_credit.token.token.Token;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.util.Futures;
import com.digitalasset.transcode.java.Party;
import com.digitalasset.transcode.java.ContractId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.*;
import com.digitalasset.quickstart.service.PoolDirectoryService;
import com.digitalasset.quickstart.service.TransactionHistoryService;
import com.digitalasset.quickstart.service.TxPacer;
import com.digitalasset.quickstart.service.ResolveGrantService;

@RestController
@RequestMapping(value = "/api/clearportx/debug", produces = MediaType.APPLICATION_JSON_VALUE)
public class ClearportxDebugController {

    @Autowired
    private LedgerApi ledgerApi;
    @Autowired
    private PoolDirectoryService directory;
    @Autowired
    private TxPacer txPacer;
    @Autowired
    private TransactionHistoryService transactionHistoryService;
    @Autowired
    private ResolveGrantService resolveGrantService;

    @GetMapping("/whoami")
    public ResponseEntity<?> whoami(@RequestHeader(value = "X-Party", required = false) String xParty) {
        String party = (xParty != null && !xParty.isBlank()) ? xParty : System.getenv().getOrDefault("APP_PROVIDER_PARTY", "");
        return ResponseEntity.ok(Map.of("resolvedParty", party));
    }

    @GetMapping("/party-acs")
    public ResponseEntity<?> partyAcs(
            @RequestParam(value = "template", required = false) String template,
            @RequestHeader(value = "X-Party", required = false) String xParty
    ) {
        String party = (xParty != null && !xParty.isBlank()) ? xParty : System.getenv().getOrDefault("APP_PROVIDER_PARTY", "");
        if (party.isBlank()) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Provide X-Party or APP_PROVIDER_PARTY"));
        try {
            if (template == null || template.isBlank() || template.equals("AMM.Pool.Pool")) {
                List<LedgerApi.ActiveContract<Pool>> pools = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
                List<String> cids = new ArrayList<>();
                for (var c : pools) cids.add(c.contractId.getContractId);
                return ResponseEntity.ok(Map.of("success", true, "party", party, "count", pools.size(), "cids", cids));
            } else if (template.equals("Token.Token.Token")) {
                List<LedgerApi.ActiveContract<Token>> toks = ledgerApi.getActiveContractsForParty(Token.class, party).join();
                List<String> cids = new ArrayList<>();
                for (var c : toks) cids.add(c.contractId.getContractId);
                return ResponseEntity.ok(Map.of("success", true, "party", party, "count", toks.size(), "cids", cids));
            }
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Unknown template: " + template));
        } catch (Exception e) {
            final String msg = String.valueOf(e.getMessage());
            // Structured error mapping for easier client handling
            if (msg.contains("CONTRACT_NOT_FOUND") || msg.contains("CONTRACT_NOT_ACTIVE")) {
                return ResponseEntity.status(409).body(Map.of(
                        "success", false,
                        "error", "STALE_OR_NOT_VISIBLE_REFRESH",
                        "message", "Pool or tokens changed; refresh and retry",
                        "retry_after_ms", 350
                ));
            }
            if (msg.contains("Insufficient") || msg.contains("insufficient")) {
                return ResponseEntity.status(422).body(Map.of(
                        "success", false,
                        "error", "INSUFFICIENT_BALANCE",
                        "message", msg
                ));
            }
            if (msg.contains("Pool not visible for party")) {
                return ResponseEntity.status(409).body(Map.of(
                        "success", false,
                        "error", "POOL_NOT_VISIBLE_FOR_PARTY_REFRESH",
                        "message", "Pool not visible for party; refresh ACS",
                        "retry_after_ms", 300
                ));
            }
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "INTERNAL", "message", msg));
        }
    }

    @PostMapping(value = "/create-pool-single", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createPoolSingle(@RequestBody Map<String, Object> reqBody,
                                              @RequestHeader(value = "X-Party", required = false) String xParty) {
        // Disabled to avoid constructor/signature mismatches across DAR variants.
        // Use PoolCreationController.createPoolDirect or external scripts instead.
        return ResponseEntity.status(501).body(Map.of(
                "success", false,
                "error", "create-pool-single disabled",
                "message", "Use /api/debug/create-pool-direct or existing pool resolution"
        ));
    }

    @PostMapping(value = "/add-liquidity-by-cid", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addLiquidityByCid(@RequestBody Map<String, Object> body,
                                               @RequestHeader(value = "X-Party") String xParty) {
        BigDecimal amountA = new BigDecimal(String.valueOf(body.getOrDefault("amountA", "0")));
        BigDecimal amountB = new BigDecimal(String.valueOf(body.getOrDefault("amountB", "0")));
        BigDecimal minLP = new BigDecimal(String.valueOf(body.getOrDefault("minLPTokens", "0")));
        String poolId = String.valueOf(body.getOrDefault("poolId", ""));
        String poolCidStr = String.valueOf(body.getOrDefault("poolCid", ""));
        boolean staleRetried = false;

        while (true) {
            try {
                final String currentPoolCid = poolCidStr;
                // Resolve pool payload; allow operator fallback to avoid trader-visibility gating
                var pool = (LedgerApi.ActiveContract<Pool>) null;
                var pay = (Pool) null;
                {
                    List<LedgerApi.ActiveContract<Pool>> pools = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
                    var maybe = pools.stream().filter(p -> p.contractId.getContractId.equals(currentPoolCid)).findFirst();
                    if (maybe.isPresent()) {
                        pool = maybe.get();
                        pay = pool.payload;
                    } else {
                        String operator = System.getenv().getOrDefault("APP_PROVIDER_PARTY", xParty);
                        var poolsOp = ledgerApi.getActiveContractsForParty(Pool.class, operator).join();
                        var matchOp = poolsOp.stream().filter(p -> p.contractId.getContractId.equals(currentPoolCid)).findFirst();
                        if (matchOp.isEmpty()) {
                            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Pool not visible for party or operator"));
                        }
                        pool = matchOp.get();
                        pay = pool.payload;
                    }
                }

                // Pre-flight: if canonical tokens referenced by this Pool are not alive for poolParty,
                // switch to a visible Pool instance for the same poolId whose canonicals are alive.
                try {
                    String poolPartyStr = pay.getPoolParty.getParty;
                    List<LedgerApi.ActiveContract<Token>> poolPartyToks = ledgerApi.getActiveContractsForParty(Token.class, poolPartyStr).join();
                    java.util.Set<String> alive = new java.util.HashSet<>();
                    for (var t : poolPartyToks) alive.add(t.contractId.getContractId);
                    boolean aAlive = pay.getTokenACid.map(cid -> alive.contains(cid.getContractId)).orElse(false);
                    boolean bAlive = pay.getTokenBCid.map(cid -> alive.contains(cid.getContractId)).orElse(false);
                    if (!(aAlive && bAlive)) {
                        List<LedgerApi.ActiveContract<Pool>> visPools = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
                        for (var cand : visPools) {
                            var pp = cand.payload;
                            if (!pp.getPoolId.equals(pay.getPoolId)) continue;
                            String ppParty = pp.getPoolParty.getParty;
                            List<LedgerApi.ActiveContract<Token>> toks = ledgerApi.getActiveContractsForParty(Token.class, ppParty).join();
                            java.util.Set<String> alive2 = new java.util.HashSet<>();
                            for (var t : toks) alive2.add(t.contractId.getContractId);
                            boolean okA = pp.getTokenACid.map(cid -> alive2.contains(cid.getContractId)).orElse(false);
                            boolean okB = pp.getTokenBCid.map(cid -> alive2.contains(cid.getContractId)).orElse(false);
                            if (okA && okB) {
                                pool = cand;
                                pay = pp;
                                break;
                            }
                        }
                    }
                } catch (Exception ignore) {
                    // best-effort freshness
                }

                List<LedgerApi.ActiveContract<Token>> tokens = ledgerApi.getActiveContractsForParty(Token.class, xParty).join();
                final String symA = pay.getSymbolA;
                final String symB = pay.getSymbolB;
                var tokA = tokens.stream().filter(t -> t.payload.getSymbol.equals(symA) && t.payload.getOwner.getParty.equals(xParty))
                        .max(Comparator.comparing(t -> t.payload.getAmount)).orElse(null);
                var tokB = tokens.stream().filter(t -> t.payload.getSymbol.equals(symB) && t.payload.getOwner.getParty.equals(xParty))
                        .max(Comparator.comparing(t -> t.payload.getAmount)).orElse(null);
                // Dev fallback: mint fresh tokens if missing or insufficient
                if (tokA == null || (tokA.payload.getAmount.compareTo(amountA) < 0)) {
                    Token freshA = new Token(pay.getIssuerA, new Party(xParty), pay.getSymbolA, amountA);
                    tokA = new LedgerApi.ActiveContract<>(
                            ledgerApi.createAndGetCid(freshA, List.of(pay.getIssuerA.getParty), List.of(), UUID.randomUUID().toString(), clearportx_amm_drain_credit.Identifiers.Token_Token__Token).join(),
                            freshA
                    );
                }
                if (tokB == null || (tokB.payload.getAmount.compareTo(amountB) < 0)) {
                    Token freshB = new Token(pay.getIssuerB, new Party(xParty), pay.getSymbolB, amountB);
                    tokB = new LedgerApi.ActiveContract<>(
                            ledgerApi.createAndGetCid(freshB, List.of(pay.getIssuerB.getParty), List.of(), UUID.randomUUID().toString(), clearportx_amm_drain_credit.Identifiers.Token_Token__Token).join(),
                            freshB
                    );
                }

                var deadline = Instant.now().plusSeconds(600);
                Pool.AddLiquidity choice = new Pool.AddLiquidity(
                        new Party(xParty),
                        new ContractId<>(tokA.contractId.getContractId),
                        new ContractId<>(tokB.contractId.getContractId),
                        amountA,
                        amountB,
                        minLP,
                        deadline
                );

                String cmdIdAdd = UUID.randomUUID().toString();
                txPacer.awaitSlot(2500);
                // Authorize with all controllers for AddLiquidity: provider (xParty), poolParty, lpIssuer
                var actAs = List.of(
                        xParty,
                        pay.getPoolParty.getParty,
                        pay.getLpIssuer.getParty,
                        pay.getIssuerA.getParty,
                        pay.getIssuerB.getParty
                );
                var readAs = List.of(
                        xParty,
                        pay.getPoolParty.getParty,
                        pay.getLpIssuer.getParty,
                        pay.getIssuerA.getParty,
                        pay.getIssuerB.getParty
                );
                // Canton 3.4.7: Use exerciseAndGetTransaction (flat) instead of exerciseAndGetTransactionTree
                var txn = ledgerApi.exerciseAndGetTransaction(
                        pool.contractId,
                        choice,
                        cmdIdAdd,
                        actAs,
                        readAs
                ).join();

                // Attempt to resolve the freshest pool by verifying canonical tokens exist (avoid stale pools)
                // Retry ACS briefly to absorb archive+recreate lag
                List<LedgerApi.ActiveContract<Pool>> poolsNow = null;
                final String targetPoolId = pay.getPoolId;
                for (int i = 0; i < 8; i++) {
                    poolsNow = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
                    if (poolsNow.stream().anyMatch(pac -> pac.payload.getPoolId.equals(targetPoolId))) break;
                    sleepQuiet(300);
                }
                // Canton 3.4.7: Extract from flat Transaction
                String createdPoolCid = extractCreatedPoolCid(txn);
                if (createdPoolCid != null) {
                    // Wait up to ~3s for ACS to show the new pool for this party
                    boolean visible = waitForPoolCidVisible(xParty, createdPoolCid, 10, 300);
                    if (!visible) {
                        return ResponseEntity.status(409).body(Map.of(
                                "success", false,
                                "error", "POOL_NOT_VISIBLE_FOR_PARTY_REFRESH",
                                "retry_after_ms", 300,
                                "newPoolCid", createdPoolCid,
                                "commandId", cmdIdAdd
                        ));
                    }
                    // Read-your-write barrier: wait for ledger end past this tx
                    long targetOffset = txn.getOffset();
                    for (int i = 0; i < 20; i++) {
                        long end = ledgerApi.getLedgerEndOffset().join();
                        if (end >= targetOffset) break;
                        sleepQuiet(200);
                    }
                }
                List<LedgerApi.ActiveContract<Token>> toksForPool = ledgerApi.getActiveContractsForParty(Token.class, pay.getPoolParty.getParty).join();
                java.util.function.Predicate<LedgerApi.ActiveContract<Pool>> hasAliveCanonicals = pac -> {
                    var pp = pac.payload;
                    boolean hasA = pp.getTokenACid
                            .map(cid -> toksForPool.stream().anyMatch(t -> t.contractId.getContractId.equals(cid.getContractId)))
                            .orElse(false);
                    boolean hasB = pp.getTokenBCid
                            .map(cid -> toksForPool.stream().anyMatch(t -> t.contractId.getContractId.equals(cid.getContractId)))
                            .orElse(false);
                    return hasA && hasB;
                };
                var freshest = poolsNow.stream()
                        .filter(pac -> pac.payload.getPoolId.equals(targetPoolId))
                        .filter(hasAliveCanonicals)
                        .findFirst()
                        .orElse(pool);
                directory.update(targetPoolId, (createdPoolCid != null ? createdPoolCid : freshest.contractId.getContractId), pay.getPoolParty.getParty);
                var finalPoolPayload = freshest.payload;
                LpTokenCreated lpCreated = extractLpTokenCreated(txn);
                BigDecimal mintedLp = (lpCreated != null && lpCreated.amount() != null)
                        ? lpCreated.amount()
                        : finalPoolPayload.getTotalLPSupply.subtract(pay.getTotalLPSupply);
                if (mintedLp.compareTo(BigDecimal.ZERO) <= 0) {
                    mintedLp = estimateLpMint(amountA, amountB, pay.getReserveA, pay.getReserveB, pay.getTotalLPSupply);
                }
                String lpTokenCid = lpCreated != null ? lpCreated.cid() : null;
                var historyEntry = transactionHistoryService.recordAddLiquidity(
                        finalPoolPayload.getPoolId,
                        freshest.contractId.getContractId,
                        finalPoolPayload.getSymbolA,
                        finalPoolPayload.getSymbolB,
                        amountA,
                        amountB,
                        minLP,
                        mintedLp,
                        xParty
                );
                var responseBody = new java.util.LinkedHashMap<String, Object>();
                responseBody.put("success", true);
                responseBody.put("poolCid", pool.contractId.getContractId);
                responseBody.put("commandId", cmdIdAdd);
                responseBody.put("lpAmount", mintedLp.toPlainString());
                if (createdPoolCid != null || !freshest.contractId.getContractId.equals(pool.contractId.getContractId)) {
                    responseBody.put("newPoolCid", createdPoolCid != null ? createdPoolCid : freshest.contractId.getContractId);
                }
                if (lpTokenCid != null) {
                    responseBody.put("lpTokenCid", lpTokenCid);
                }
                if (historyEntry != null && historyEntry.id != null) {
                    responseBody.put("historyEntryId", historyEntry.id);
                }
                return ResponseEntity.ok(responseBody);
            } catch (Exception e) {
                final String msg = String.valueOf(e.getMessage());
                if (isStaleContractError(msg) && !staleRetried && !poolId.isBlank()) {
                    staleRetried = true;
                    try {
                        var resolved = resolveGrantService.resolveAndGrant(poolId, xParty);
                        if (resolved != null && resolved.poolCid() != null && !resolved.poolCid().isBlank()
                                && !resolved.poolCid().equals(poolCidStr)) {
                            poolCidStr = resolved.poolCid();
                            continue;
                        }
                    } catch (Exception ignore) {
                        // fall through to standard handling
                    }
                }
                if (msg.contains("CONTRACT_NOT_FOUND") || msg.contains("CONTRACT_NOT_ACTIVE")) {
                    return ResponseEntity.status(409).body(Map.of(
                            "success", false,
                            "error", "STALE_OR_NOT_VISIBLE_REFRESH",
                            "message", "Pool or tokens changed; refresh and retry",
                            "retry_after_ms", 350
                    ));
                }
                if (msg.contains("Insufficient") || msg.contains("insufficient")) {
                    return ResponseEntity.status(422).body(Map.of(
                            "success", false,
                            "error", "INSUFFICIENT_BALANCE",
                            "message", msg
                    ));
                }
                if (msg.contains("Pool not visible for party")) {
                    return ResponseEntity.status(409).body(Map.of(
                            "success", false,
                            "error", "POOL_NOT_VISIBLE_FOR_PARTY_REFRESH",
                            "message", "Pool not visible for party; refresh ACS",
                            "retry_after_ms", 300
                    ));
                }
                return ResponseEntity.status(500).body(Map.of("success", false, "error", "INTERNAL", "message", msg));
            }
        }
    }

    @PostMapping(value = "/seed-liquidity", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> seedLiquidity(@RequestBody Map<String, Object> body,
                                           @RequestHeader(value = "X-Party") String xParty) {
        String poolId = String.valueOf(body.getOrDefault("poolId", ""));
        String poolCidOverride = String.valueOf(body.getOrDefault("poolCid", ""));
        BigDecimal amountA = new BigDecimal(String.valueOf(body.getOrDefault("amountA", "0")));
        BigDecimal amountB = new BigDecimal(String.valueOf(body.getOrDefault("amountB", "0")));
        BigDecimal minLP = new BigDecimal(String.valueOf(body.getOrDefault("minLPTokens", "0.0000000001")));
        if (poolId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Missing poolId"));
        }
        try {
            String operator = System.getenv().getOrDefault("APP_PROVIDER_PARTY", xParty);
            // Find freshest candidate for poolId using operator ACS, preferring alive canonicals
            List<LedgerApi.ActiveContract<Pool>> poolsOp = ledgerApi.getActiveContractsForParty(Pool.class, operator).join();
            java.util.List<LedgerApi.ActiveContract<Pool>> candidates = new java.util.ArrayList<>();
            for (var pac : poolsOp) {
                if (!poolCidOverride.isBlank()) {
                    if (pac.contractId.getContractId.equals(poolCidOverride)) {
                        candidates.add(pac);
                    }
                    continue;
                }
                if (!pac.payload.getPoolId.equals(poolId)) continue;
                candidates.add(pac);
            }
            if (candidates.isEmpty()) {
                // Auto-create a new gv-compat pool for the given poolId using operator as all roles
                try {
                    daml_stdlib_da_time_types.da.time.types.RelTime ttl = new daml_stdlib_da_time_types.da.time.types.RelTime(86400000000L);
                    Pool newPool = new Pool(
                            new com.digitalasset.transcode.java.Party(operator),
                            new com.digitalasset.transcode.java.Party(operator),
                            new com.digitalasset.transcode.java.Party(operator),
                            new com.digitalasset.transcode.java.Party(operator),
                            new com.digitalasset.transcode.java.Party(operator),
                            "ETH",
                            "USDC",
                            30L,
                            poolId,
                            ttl,
                            new java.math.BigDecimal("0.0"),
                            new java.math.BigDecimal("0.0"),
                            new java.math.BigDecimal("0.0"),
                            java.util.Optional.<ContractId<Token>>empty(),
                            java.util.Optional.<ContractId<Token>>empty(),
                            new com.digitalasset.transcode.java.Party(operator),
                            10000L,
                            5000L,
                            List.of()
                );
                    ledgerApi.createAndGetCid(
                            newPool,
                            java.util.List.of(operator),
                            java.util.List.of(),
                            java.util.UUID.randomUUID().toString(),
                            clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool
                    ).join();
                    // Refresh candidates after creation
                    poolsOp = ledgerApi.getActiveContractsForParty(Pool.class, operator).join();
                    candidates = new java.util.ArrayList<>();
                    for (var pac : poolsOp) {
                        if (poolId.equals(pac.payload.getPoolId)) candidates.add(pac);
                    }
                    if (candidates.isEmpty()) {
                        return ResponseEntity.status(404).body(Map.of("success", false, "error", "Pool auto-create failed"));
                    }
                } catch (Exception ce) {
                    return ResponseEntity.status(500).body(Map.of("success", false, "error", "Pool create error: " + ce.getMessage()));
                }
            }
            // Prefer candidates with alive canonicals for poolParty, then by TVL proxy
            candidates.sort((a, b) -> b.payload.getReserveA.multiply(b.payload.getReserveB)
                    .compareTo(a.payload.getReserveA.multiply(a.payload.getReserveB)));
            LedgerApi.ActiveContract<Pool> pool = candidates.get(0);
            {
                var best = pool;
                for (var cand : candidates) {
                    String pp = cand.payload.getPoolParty.getParty;
                    var toks = ledgerApi.getActiveContractsForParty(Token.class, pp).join();
                    java.util.Set<String> alive = new java.util.HashSet<>();
                    for (var t : toks) alive.add(t.contractId.getContractId);
                    boolean aAlive = cand.payload.getTokenACid.map(cid -> alive.contains(cid.getContractId)).orElse(false);
                    boolean bAlive = cand.payload.getTokenBCid.map(cid -> alive.contains(cid.getContractId)).orElse(false);
                    if (aAlive && bAlive) { best = cand; break; }
                }
                pool = best;
            }
            Pool pay = pool.payload;
            final String symA = pay.getSymbolA;
            final String symB = pay.getSymbolB;
            // Ensure provider has sufficient tokens; mint if needed
            List<LedgerApi.ActiveContract<Token>> myToks = ledgerApi.getActiveContractsForParty(Token.class, xParty).join();
            var tokA = myToks.stream().filter(t -> t.payload.getSymbol.equals(symA) && t.payload.getOwner.getParty.equals(xParty))
                    .max(Comparator.comparing(t -> t.payload.getAmount)).orElse(null);
            var tokB = myToks.stream().filter(t -> t.payload.getSymbol.equals(symB) && t.payload.getOwner.getParty.equals(xParty))
                    .max(Comparator.comparing(t -> t.payload.getAmount)).orElse(null);
            if (tokA == null || (tokA.payload.getAmount.compareTo(amountA) < 0)) {
                Token freshA = new Token(pay.getIssuerA, new Party(xParty), symA, amountA);
                tokA = new LedgerApi.ActiveContract<>(
                        ledgerApi.createAndGetCid(freshA, List.of(pay.getIssuerA.getParty), List.of(), UUID.randomUUID().toString(), clearportx_amm_drain_credit.Identifiers.Token_Token__Token).join(),
                        freshA
                );
                txPacer.awaitSlot(1200);
            }
            if (tokB == null || (tokB.payload.getAmount.compareTo(amountB) < 0)) {
                Token freshB = new Token(pay.getIssuerB, new Party(xParty), symB, amountB);
                tokB = new LedgerApi.ActiveContract<>(
                        ledgerApi.createAndGetCid(freshB, List.of(pay.getIssuerB.getParty), List.of(), UUID.randomUUID().toString(), clearportx_amm_drain_credit.Identifiers.Token_Token__Token).join(),
                        freshB
                );
                txPacer.awaitSlot(1200);
            }
            // Exercise AddLiquidity with robust parties
            var deadline = Instant.now().plusSeconds(600);
            Pool.AddLiquidity choice = new Pool.AddLiquidity(
                    new Party(xParty),
                    new ContractId<>(tokA.contractId.getContractId),
                    new ContractId<>(tokB.contractId.getContractId),
                    amountA, amountB, minLP, deadline
            );
            String cmdId = UUID.randomUUID().toString();
            txPacer.awaitSlot(2500);
            var actAs = List.of(
                    xParty,
                    pay.getPoolParty.getParty,
                    pay.getLpIssuer.getParty,
                    pay.getIssuerA.getParty,
                    pay.getIssuerB.getParty
            );
            var readAs = List.of(
                    xParty,
                    pay.getPoolParty.getParty,
                    pay.getLpIssuer.getParty,
                    pay.getIssuerA.getParty,
                    pay.getIssuerB.getParty
            );
            // Canton 3.4.7: Use flat Transaction instead of TransactionTree
            com.daml.ledger.api.v2.TransactionOuterClass.Transaction txn;
            try {
                txn = ledgerApi.exerciseAndGetTransaction(
                        pool.contractId,
                        choice,
                        cmdId,
                        actAs,
                        readAs
                ).join();
            } catch (Exception e1) {
                String msg = exceptionText(e1);
                if (msg.contains("NOT_FOUND") || msg.contains("CONTRACT_NOT_ACTIVE")) {
                    // Re-resolve freshest candidate and retry once
                    txPacer.awaitSlot(2200);
                    poolsOp = ledgerApi.getActiveContractsForParty(Pool.class, operator).join();
                    LedgerApi.ActiveContract<Pool> retry = poolsOp.stream()
                            .filter(p -> p.payload.getPoolId.equals(poolId))
                            .findFirst().orElse(pool);
                    // Canton 3.4.7: Use exerciseAndGetTransaction
                    ledgerApi.exerciseAndGetTransaction(
                            retry.contractId,
                            choice,
                            UUID.randomUUID().toString(),
                            actAs,
                            readAs
                    ).join();
                } else {
                    throw e1;
                }
            }
            // Update directory to reflect potential new CID created
            // Canton 3.4.7: Use getLastTransaction instead of getLastTxTree
            String createdPoolCid = extractCreatedPoolCid(ledgerApi.getLastTransaction().orElse(null));
            if (createdPoolCid != null) {
                directory.update(poolId, createdPoolCid, pay.getPoolParty.getParty);
            } else {
                directory.update(poolId, pool.contractId.getContractId, pay.getPoolParty.getParty);
            }
            Map<String, Object> payload = Map.of(
                    "success", true,
                    "poolCid", pool.contractId.getContractId,
                    "poolId", poolId
            );
            BigDecimal mintedEstimate = estimateLpMint(amountA, amountB, pay.getReserveA, pay.getReserveB, pay.getTotalLPSupply);
            transactionHistoryService.recordAddLiquidity(
                    poolId,
                    pool.contractId.getContractId,
                    symA,
                    symB,
                    amountA,
                    amountB,
                    minLP,
                    mintedEstimate,
                    xParty
            );
            return ResponseEntity.ok(payload);
        } catch (Exception e) {
            final String msg = String.valueOf(e.getMessage());
            if (msg.contains("CONTRACT_NOT_FOUND") || msg.contains("CONTRACT_NOT_ACTIVE")) {
                return ResponseEntity.status(409).body(Map.of(
                        "success", false,
                        "error", "STALE_OR_NOT_VISIBLE_REFRESH",
                        "message", "Pool or tokens changed; refresh and retry",
                        "retry_after_ms", 350
                ));
            }
            if (msg.contains("Insufficient") || msg.contains("insufficient")) {
                return ResponseEntity.status(422).body(Map.of(
                        "success", false,
                        "error", "INSUFFICIENT_BALANCE",
                        "message", msg
                ));
            }
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "INTERNAL", "message", msg));
        }
    }
    @PostMapping(value = "/swap-by-cid", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> swapByCid(@RequestBody Map<String, Object> body,
                                       @RequestHeader(value = "X-Party") String xParty) {
        String poolCidStr = String.valueOf(body.getOrDefault("poolCid", ""));
        String poolIdOpt = String.valueOf(body.getOrDefault("poolId", ""));
        String inputSymbol = String.valueOf(body.getOrDefault("inputSymbol", ""));
        String outputSymbol = String.valueOf(body.getOrDefault("outputSymbol", ""));
        BigDecimal amountIn = new BigDecimal(String.valueOf(body.getOrDefault("amountIn", "0")));
        BigDecimal minOutput = new BigDecimal(String.valueOf(body.getOrDefault("minOutput", "1")));
        try {
            // Resolve correct poolParty by fetching the pool payload by the provided poolCid
            String poolPartyStr;
            final String[] canonRef = new String[2];
            String resolvedPoolId = poolIdOpt;
            {
                var poolsForParty = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
                var match = poolsForParty.stream().filter(p -> p.contractId.getContractId.equals(poolCidStr)).findFirst();
                if (match.isPresent()) {
                    var payload = match.get().payload;
                    resolvedPoolId = payload.getPoolId;
                    poolPartyStr = payload.getPoolParty.getParty;
                    canonRef[0] = payload.getTokenACid.map(cid -> cid.getContractId).orElse(null);
                    canonRef[1] = payload.getTokenBCid.map(cid -> cid.getContractId).orElse(null);
                } else {
                    // Fallback: resolve via operator (APP_PROVIDER_PARTY) to avoid trader visibility gating
                    String operator = System.getenv().getOrDefault("APP_PROVIDER_PARTY", xParty);
                    var poolsForOp = ledgerApi.getActiveContractsForParty(Pool.class, operator).join();
                    var matchOp = poolsForOp.stream().filter(p -> p.contractId.getContractId.equals(poolCidStr)).findFirst();
                    if (matchOp.isEmpty()) {
                        return ResponseEntity.status(404).body(Map.of("success", false, "error", "Pool not found for operator or trader"));
                    }
                    var payload = matchOp.get().payload;
                    resolvedPoolId = payload.getPoolId;
                    poolPartyStr = payload.getPoolParty.getParty;
                    canonRef[0] = payload.getTokenACid.map(cid -> cid.getContractId).orElse(null);
                    canonRef[1] = payload.getTokenBCid.map(cid -> cid.getContractId).orElse(null);
                }
            }

            var tokens = ledgerApi.getActiveContractsForParty(Token.class, xParty).join();
            var inputTok = tokens.stream()
                    .filter(t -> t.payload.getSymbol.equals(inputSymbol) && t.payload.getOwner.getParty.equals(xParty))
                    .filter(t -> { // don't reuse pool canonical tokens as trader input
                        String cid = t.contractId.getContractId;
                        String ca = canonRef[0];
                        String cb = canonRef[1];
                        return (ca == null || !ca.equals(cid)) && (cb == null || !cb.equals(cid));
                    })
                    .max(Comparator.comparing(t -> t.payload.getAmount)).orElse(null);
            if (inputTok == null) return ResponseEntity.status(409).body(Map.of("success", false, "error", "INPUT_TOKEN_STALE_OR_NOT_VISIBLE: refresh & retry"));
            if (inputTok.payload.getAmount.compareTo(amountIn) < 0) return ResponseEntity.status(422).body(Map.of("success", false, "error", "Insufficient input"));

            // Skip PRE_SWAP trace to avoid constructor/signature drift across DAR variants

            var deadline = Instant.now().plusSeconds(600);
            Pool.AtomicSwap choice = new Pool.AtomicSwap(
                    new Party(xParty),
                    inputTok.contractId,
                    inputSymbol,
                    amountIn,
                    outputSymbol,
                    minOutput.compareTo(BigDecimal.ZERO) > 0 ? minOutput : BigDecimal.ONE,
                    5000L, // 50% max tolerance as enforced by DAML
                    deadline
            );

            // Execute with pacing; do not depend on ACS visibility for immediate follow-up
            String cmdId = UUID.randomUUID().toString();
            txPacer.awaitSlot(3500);
            // Canton 3.4.7: Use flat Transaction instead of TransactionTree
            com.daml.ledger.api.v2.TransactionOuterClass.Transaction txn;
            try {
                txn = ledgerApi.exerciseAndGetTransaction(
                        new ContractId<Pool>(poolCidStr),
                        choice,
                        cmdId,
                        List.of(xParty, poolPartyStr),
                        List.of(poolPartyStr, xParty)
                ).join();
            } catch (Exception first) {
                String msg = exceptionText(first);
                // Prefer CONTRACT_NOT_ACTIVE handling over generic NOT_FOUND
                if (msg.contains("CONTRACT_NOT_ACTIVE")) {
                    // Attempt one automatic recovery: reselect a fresh input token (or mint one) and retry once
                    try {
                        txPacer.awaitSlot(2200);
                        var freshTokens = ledgerApi.getActiveContractsForParty(Token.class, xParty).join();
                        var freshInputOpt = freshTokens.stream()
                                .filter(t -> t.payload.getSymbol.equals(inputSymbol) && t.payload.getOwner.getParty.equals(xParty))
                                .filter(t -> t.payload.getAmount.compareTo(amountIn) >= 0)
                                .max(Comparator.comparing(t -> t.payload.getAmount));
                        LedgerApi.ActiveContract<Token> freshInput = freshInputOpt.orElse(null);
                        if (freshInput == null) {
                            // Mint a small fresh input token and retry
                            Token freshMint = new Token(new Party(xParty), new Party(xParty), inputSymbol,
                                    amountIn.max(new BigDecimal("1.0")));
                    ContractId<Token> mintedCid = ledgerApi.createAndGetCid(
                                    freshMint,
                                    List.of(xParty),
                                    List.of(),
                                    UUID.randomUUID().toString(),
                            clearportx_amm_drain_credit.Identifiers.Token_Token__Token
                            ).join();
                            freshInput = new LedgerApi.ActiveContract<>(mintedCid, freshMint);
                            txPacer.awaitSlot(2200);
                        }
                        // Retry once with the freshly selected/minted token
                        Pool.AtomicSwap retryChoice = new Pool.AtomicSwap(
                                new Party(xParty),
                                freshInput.contractId,
                                inputSymbol,
                                amountIn,
                                outputSymbol,
                                minOutput.compareTo(BigDecimal.ZERO) > 0 ? minOutput : BigDecimal.ONE,
                                5000L,
                                Instant.now().plusSeconds(600)
                        );
                        ledgerApi.exerciseAndGetResultWithParties(
                                new ContractId<Pool>(poolCidStr),
                                retryChoice,
                                UUID.randomUUID().toString(),
                                List.of(xParty, poolPartyStr),
                                List.of(poolPartyStr, xParty)
                        ).join();
                        return ResponseEntity.ok(Map.of("success", true, "recovered", true));
                    } catch (Exception second) {
                        return ResponseEntity.status(409).body(Map.of(
                                "success", false,
                                "error", "CONTRACT_NOT_ACTIVE",
                                "diagnostics", Map.of(
                                        "firstMessage", msg,
                                        "secondMessage", exceptionText(second),
                                        "poolCid", poolCidStr,
                                        "poolId", poolIdOpt,
                                        "actAs", List.of(xParty, poolPartyStr),
                                        "readAs", List.of(poolPartyStr, xParty),
                                        "inputSymbol", inputSymbol,
                                        "outputSymbol", outputSymbol,
                                        "amountIn", amountIn.toPlainString(),
                                        "minOutput", minOutput.toPlainString()
                                )
                        ));
                    }
                } else if (msg.contains("NOT_FOUND") || msg.contains("CONTRACT_NOT_FOUND")) {
                    return ResponseEntity.status(409).body(Map.of(
                            "success", false,
                            "error", "STALE_OR_NOT_VISIBLE_REFRESH",
                            "retry_after_ms", 2200
                    ));
                }
                // Map DAML slippage assertion to 422 (client error)
                if (msg.contains("Min output not met")) {
                    return ResponseEntity.status(422).body(Map.of(
                            "success", false,
                            "error", "SLIPPAGE_MIN_OUTPUT_NOT_MET",
                            "message", "Min output not met (slippage)"
                    ));
                }
                if (msg.contains("CONTRACT_NOT_ACTIVE")) {
                    // Diagnostics: capture which CID went inactive and surrounding context
                    String inactiveCid = extractInactiveCid(msg);
                    Map<String, Object> diag = new LinkedHashMap<>();
                    diag.put("stage", "first-attempt");
                    diag.put("errorMessage", msg);
                    diag.put("inactiveCid", inactiveCid);
                    diag.put("poolCid", poolCidStr);
                    diag.put("poolId", poolIdOpt);
                    diag.put("actAs", List.of(xParty, poolPartyStr));
                    diag.put("readAs", List.of(poolPartyStr, xParty));
                    diag.put("inputTokCid", inputTok.contractId.getContractId);
                    diag.put("inputSymbol", inputSymbol);
                    diag.put("outputSymbol", outputSymbol);
                    diag.put("amountIn", amountIn.toPlainString());
                    diag.put("minOutput", minOutput.toPlainString());
                    diag.put("canonA", canonRef[0]);
                    diag.put("canonB", canonRef[1]);
                    return ResponseEntity.status(409).body(Map.of(
                            "success", false,
                            "error", "CONTRACT_NOT_ACTIVE",
                            "diagnostics", diag
                    ));
                } else if (msg.contains("Price impact too high")) {
                    return ResponseEntity.status(422).body(Map.of("success", false, "error", "PRICE_IMPACT_TOO_HIGH"));
                } else if (msg.contains("UNHANDLED_EXCEPTION/DA.Exception.AssertionFailed:AssertionFailed: Input reserve must be positive")) {
                    return ResponseEntity.status(409).body(Map.of("success", false, "error", "POOL_EMPTY: add liquidity first"));
                } else {
                    throw first;
                }
            }
            // Extract created output token and new poolCid from tx (best effort)
            // Canton 3.4.7: Use flat Transaction instead of TransactionTree
            String createdOutputTokenCid = null;
            String createdPoolCid = extractCreatedPoolCid(txn);
            try {
                // Canton 3.4.7: Flat event list instead of map
                var events = txn.getEventsList();
                for (var ev : events) {
                    if (ev.hasCreated()) {
                        var created = ev.getCreated();
                        var tmpl = created.getTemplateId();
                        if ("Token.Token".equals(tmpl.getModuleName()) && "Token".equals(tmpl.getEntityName())) {
                            createdOutputTokenCid = created.getContractId();
                        }
                    }
                }
            } catch (Exception ignore) {}
            Map<String, Object> payload = Map.of(
                    "success", true,
                    "outputTokenCid", createdOutputTokenCid,
                    "newPoolCid", createdPoolCid,
                    "commandId", cmdId
            );
            BigDecimal resolvedOut = resolveTokenAmount(createdOutputTokenCid, xParty);
            if (resolvedOut.compareTo(BigDecimal.ZERO) <= 0) {
                resolvedOut = minOutput.compareTo(BigDecimal.ZERO) > 0 ? minOutput : amountIn;
            }
            String historyPoolId = (resolvedPoolId != null && !resolvedPoolId.isBlank()) ? resolvedPoolId : poolIdOpt;
            transactionHistoryService.recordSwap(
                    historyPoolId,
                    poolCidStr,
                    inputSymbol,
                    outputSymbol,
                    amountIn,
                    resolvedOut,
                    xParty
            );
            return ResponseEntity.ok(payload);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/directory")
    public ResponseEntity<?> directorySnapshot() {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "lastUpdated", directory.lastUpdated().toString(),
                "mapping", directory.snapshot()
        ));
    }

    @GetMapping("/pool/fetch-cid")
    public ResponseEntity<?> fetchPoolByCid(@RequestParam("cid") String poolCid,
                                            @RequestHeader(value = "X-Party") String xParty) {
        try {
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
            boolean visible = pools.stream().anyMatch(p -> p.contractId.getContractId.equals(poolCid));
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "party", xParty,
                    "poolCid", poolCid,
                    "visible", visible
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping(value = "/pool/grant-visibility", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> grantVisibility(@RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-Party") String xParty) {
        return ResponseEntity.status(501).body(Map.of("success", false, "error", "GrantVisibility not available in current bindings"));
    }

    @GetMapping("/pool-by-cid")
    public ResponseEntity<?> poolByCid(@RequestParam("cid") String poolCid,
                                       @RequestHeader(value = "X-Party") String xParty) {
        try {
            var pools = ledgerApi.getActiveContractsForParty(Pool.class, xParty).join();
            var maybe = pools.stream().filter(p -> p.contractId.getContractId.equals(poolCid)).findFirst();
            if (maybe.isEmpty()) return ResponseEntity.status(404).body(Map.of("success", false, "error", "Pool not visible for party"));
            var pay = maybe.get().payload;
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("success", true);
            payload.put("poolCid", poolCid);
            payload.put("poolId", pay.getPoolId);
            payload.put("symbolA", pay.getSymbolA);
            payload.put("symbolB", pay.getSymbolB);
            payload.put("reserveA", pay.getReserveA.toPlainString());
            payload.put("reserveB", pay.getReserveB.toPlainString());
            payload.put("tokenACid", pay.getTokenACid.map(cid -> cid.getContractId).orElse(null));
            payload.put("tokenBCid", pay.getTokenBCid.map(cid -> cid.getContractId).orElse(null));
            payload.put("maxInBps", pay.getMaxInBps);
            payload.put("maxOutBps", pay.getMaxOutBps);
            return ResponseEntity.ok(payload);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/token-by-cid")
    public ResponseEntity<?> tokenByCid(@RequestParam("cid") String tokenCid,
                                        @RequestHeader(value = "X-Party") String xParty) {
        try {
            var toks = ledgerApi.getActiveContractsForParty(Token.class, xParty).join();
            var maybe = toks.stream().filter(t -> t.contractId.getContractId.equals(tokenCid)).findFirst();
            if (maybe.isEmpty()) {
                // Fallback via operator view
                String operator = System.getenv().getOrDefault("APP_PROVIDER_PARTY", xParty);
                var toksOp = ledgerApi.getActiveContractsForParty(Token.class, operator).join();
                maybe = toksOp.stream().filter(t -> t.contractId.getContractId.equals(tokenCid)).findFirst();
                if (maybe.isEmpty()) {
                    return ResponseEntity.status(404).body(Map.of("success", false, "error", "Token not visible for party"));
                }
            }
            var t = maybe.get().payload;
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "contractId", tokenCid,
                    "symbol", t.getSymbol,
                    "amount", t.getAmount.toPlainString(),
                    "owner", t.getOwner.getParty
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping(value = "/create-pool-gv", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createPoolGv(@RequestBody Map<String, Object> req,
                                          @RequestHeader(value = "X-Party", required = false) String xParty) {
        try {
            String operator = String.valueOf(req.getOrDefault("operatorParty", xParty));
            String poolParty = String.valueOf(req.getOrDefault("poolParty", xParty));
            String ethIssuer = String.valueOf(req.getOrDefault("ethIssuer", xParty));
            String usdcIssuer = String.valueOf(req.getOrDefault("usdcIssuer", xParty));
            String lpIssuer = String.valueOf(req.getOrDefault("lpIssuer", xParty));
            String feeReceiver = String.valueOf(req.getOrDefault("feeReceiver", xParty));
            String poolId = String.valueOf(req.getOrDefault("poolId", "ETH-USDC"));
            boolean bootstrap = Boolean.parseBoolean(String.valueOf(req.getOrDefault("bootstrapTokens", "true")));

            if (operator.isBlank() || poolParty.isBlank() || ethIssuer.isBlank() || usdcIssuer.isBlank() || lpIssuer.isBlank() || feeReceiver.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Missing parties"));
            }

            // Optionally mint canonical tokens for poolParty (issuers mint to poolParty)
            if (bootstrap) {
                Token eth = new Token(new Party(ethIssuer), new Party(poolParty), "ETH", new BigDecimal("10.0"));
                Token usdc = new Token(new Party(usdcIssuer), new Party(poolParty), "USDC", new BigDecimal("25000.0"));
                txPacer.awaitSlot(800);
                Futures.retry(
                        () -> ledgerApi.createAndGetCid(
                                eth,
                                List.of(ethIssuer),
                                List.of(),
                                UUID.randomUUID().toString(),
                                clearportx_amm_drain_credit.Identifiers.Token_Token__Token),
                        6, 600).join();
                txPacer.awaitSlot(800);
                Futures.retry(
                        () -> ledgerApi.createAndGetCid(
                                usdc,
                                List.of(usdcIssuer),
                                List.of(),
                                UUID.randomUUID().toString(),
                                clearportx_amm_drain_credit.Identifiers.Token_Token__Token),
                        6, 600).join();
            }

            // Create Pool
            Pool pool = new Pool(
                    new Party(operator),
                    new Party(poolParty),
                    new Party(lpIssuer),
                    new Party(ethIssuer),
                    new Party(usdcIssuer),
                    "ETH", "USDC", 30L, poolId,
                    new daml_stdlib_da_time_types.da.time.types.RelTime(86400000000L),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    Optional.<ContractId<Token>>empty(), Optional.<ContractId<Token>>empty(),
                    new Party(feeReceiver), 10000L, 5000L, List.of()
                );
            txPacer.awaitSlot(1000);
            var poolCid = Futures.retry(
                    () -> ledgerApi.createAndGetCid(
                            pool,
                            List.of(operator, poolParty),
                            List.of(),
                            UUID.randomUUID().toString(),
                            clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool),
                    6, 600).join();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "poolCid", poolCid.getContractId,
                    "poolId", poolId
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private BigDecimal resolveTokenAmount(String tokenCid, String partyHint) {
        if (tokenCid == null || tokenCid.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            var tokens = ledgerApi.getActiveContractsForParty(Token.class, partyHint).join();
            for (var token : tokens) {
                if (token.contractId.getContractId.equals(tokenCid)) {
                    return token.payload.getAmount;
                }
            }
            String operator = System.getenv().getOrDefault("APP_PROVIDER_PARTY", partyHint);
            var opTokens = ledgerApi.getActiveContractsForParty(Token.class, operator).join();
            for (var token : opTokens) {
                if (token.contractId.getContractId.equals(tokenCid)) {
                    return token.payload.getAmount;
                }
            }
        } catch (Exception ignore) {
        }
        return BigDecimal.ZERO;
    }

    private static String extractInactiveCid(String message) {
        if (message == null) return null;
        String key1 = "inactive contract ";
        int i = message.indexOf(key1);
        if (i >= 0) {
            int start = i + key1.length();
            int end = message.indexOf(' ', start);
            if (end < 0) end = message.indexOf(')', start);
            if (end < 0) end = message.length();
            return message.substring(start, end);
        }
        String key2 = "Contract could not be found with id ";
        int j = message.indexOf(key2);
        if (j >= 0) {
            int start = j + key2.length();
            int end = message.indexOf(' ', start);
            if (end < 0) end = message.length();
            return message.substring(start, end);
        }
        return null;
    }

    private static String exceptionText(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        while (cur != null) {
            String part = cur.getMessage();
            if (part == null || part.isBlank()) {
                part = cur.toString();
            }
            sb.append(part);
            if (cur.getCause() != null) sb.append(" | ");
            cur = cur.getCause();
        }
        return sb.toString();
    }

    private boolean isStaleContractError(String msg) {
        return msg != null && (msg.contains("CONTRACT_NOT_FOUND") || msg.contains("CONTRACT_NOT_ACTIVE"));
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // Canton 3.4.7: Changed from TransactionTree to flat Transaction
    private static String extractCreatedPoolCid(com.daml.ledger.api.v2.TransactionOuterClass.Transaction txn) {
        try {
            // Canton 3.4.7: Flat event list instead of event map
            java.util.List<com.daml.ledger.api.v2.EventOuterClass.Event> events = txn.getEventsList();
            for (com.daml.ledger.api.v2.EventOuterClass.Event ev : events) {
                if (ev.hasCreated()) {
                    var created = ev.getCreated();
                    var tmpl = created.getTemplateId();
                    // Match by module/entity to avoid package hash issues
                    if ("AMM.Pool".equals(tmpl.getModuleName()) && "Pool".equals(tmpl.getEntityName())) {
                        return created.getContractId();
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static LpTokenCreated extractLpTokenCreated(com.daml.ledger.api.v2.TransactionOuterClass.Transaction txn) {
        try {
            java.util.List<com.daml.ledger.api.v2.EventOuterClass.Event> events = txn.getEventsList();
            for (com.daml.ledger.api.v2.EventOuterClass.Event ev : events) {
                if (ev.hasCreated()) {
                    var created = ev.getCreated();
                    var tmpl = created.getTemplateId();
                    if ("LPToken.LPToken".equals(tmpl.getModuleName()) && "LPToken".equals(tmpl.getEntityName())) {
                        java.math.BigDecimal amount = java.math.BigDecimal.ZERO;
                        if (created.hasCreateArguments()) {
                            var fields = created.getCreateArguments().getFieldsList();
                            if (fields.size() >= 4) {
                                var amountValue = fields.get(3).getValue();
                                if (amountValue.hasNumeric()) {
                                    amount = new java.math.BigDecimal(amountValue.getNumeric());
                                }
                            }
                        }
                        return new LpTokenCreated(created.getContractId(), amount);
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private record LpTokenCreated(String cid, java.math.BigDecimal amount) { }


    private static BigDecimal estimateLpMint(BigDecimal amountA, BigDecimal amountB, BigDecimal reserveA, BigDecimal reserveB, BigDecimal totalLp) {
        if (totalLp == null || totalLp.compareTo(BigDecimal.ZERO) == 0
                || reserveA == null || reserveA.compareTo(BigDecimal.ZERO) == 0
                || reserveB == null || reserveB.compareTo(BigDecimal.ZERO) == 0) {
            double product = amountA.multiply(amountB).doubleValue();
            double sqrt = product <= 0 ? 0 : Math.sqrt(product);
            return BigDecimal.valueOf(sqrt);
        }
        BigDecimal shareA = amountA.multiply(totalLp).divide(reserveA, MathContext.DECIMAL64);
        BigDecimal shareB = amountB.multiply(totalLp).divide(reserveB, MathContext.DECIMAL64);
        return shareA.min(shareB);
    }

    private boolean waitForPoolCidVisible(String party, String poolCid, int attempts, long delayMs) {
        for (int i = 0; i < attempts; i++) {
            List<LedgerApi.ActiveContract<Pool>> acs = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
            boolean found = acs.stream().anyMatch(pac -> pac.contractId.getContractId.equals(poolCid));
            if (found) return true;
            sleepQuiet(delayMs);
        }
        return false;
    }

    /**
     * Simple E2E swap test - mints fresh trader token and executes swap
     * This endpoint tests the complete Canton 3.4.7 integration:
     * 1. Query pool via getActiveContracts (EventFormat)
     * 2. Create trader token via createAndGetCid (TransactionFormat)
     * 3. Execute AtomicSwap via exerciseAndGetTransaction (flat Transaction)
     */
    @PostMapping(value = "/e2e-swap-test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> e2eSwapTest(@RequestBody Map<String, Object> body,
                                          @RequestHeader(value = "X-Party") String xParty) {
        String poolId = String.valueOf(body.getOrDefault("poolId", ""));
        String inputSymbol = String.valueOf(body.getOrDefault("inputSymbol", ""));
        String outputSymbol = String.valueOf(body.getOrDefault("outputSymbol", ""));
        BigDecimal amountIn = new BigDecimal(String.valueOf(body.getOrDefault("amountIn", "1.0")));
        BigDecimal minOutput = new BigDecimal(String.valueOf(body.getOrDefault("minOutput", "0.0001")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testName", "E2E Canton 3.4.7 Swap Test");
        List<String> steps = new ArrayList<>();

        try {
            // Step 1: Find pool by poolId
            steps.add("Finding pool: " + poolId);
            String operator = System.getenv().getOrDefault("APP_PROVIDER_PARTY", xParty);
            List<LedgerApi.ActiveContract<Pool>> pools = ledgerApi.getActiveContractsForParty(Pool.class, operator).join();
            LedgerApi.ActiveContract<Pool> poolContract = pools.stream()
                    .filter(p -> p.payload.getPoolId.equals(poolId))
                    .max(Comparator.comparing(p -> p.payload.getReserveA.multiply(p.payload.getReserveB)))
                    .orElse(null);
            if (poolContract == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Pool not found: " + poolId, "steps", steps));
            }
            steps.add("✓ Found pool: " + poolContract.contractId.getContractId.substring(0, 20) + "...");
            result.put("poolCid", poolContract.contractId.getContractId);
            result.put("reservesBefore", Map.of(
                    poolContract.payload.getSymbolA, poolContract.payload.getReserveA.toPlainString(),
                    poolContract.payload.getSymbolB, poolContract.payload.getReserveB.toPlainString()
            ));

            // Step 2: Mint fresh trader token WITH CORRECT ISSUER
            // Token issuer MUST match pool's issuer for merge compatibility
            boolean inputIsA = inputSymbol.equals(poolContract.payload.getSymbolA);
            String correctIssuer = inputIsA
                    ? poolContract.payload.getIssuerA.getParty
                    : poolContract.payload.getIssuerB.getParty;
            steps.add("Minting " + amountIn + " " + inputSymbol + " (issuer: " + correctIssuer.substring(0, 20) + "...)");

            Token traderToken = new Token(new Party(correctIssuer), new Party(xParty), inputSymbol, amountIn);
            ContractId<Token> traderTokenCid = ledgerApi.createAndGetCid(
                    traderToken,
                    List.of(correctIssuer),  // actAs issuer, not trader
                    List.of(),
                    UUID.randomUUID().toString(),
                    clearportx_amm_drain_credit.Identifiers.Token_Token__Token
            ).join();
            steps.add("✓ Minted token: " + traderTokenCid.getContractId.substring(0, 20) + "...");
            result.put("traderTokenCid", traderTokenCid.getContractId);
            result.put("tokenIssuer", correctIssuer);
            txPacer.awaitSlot(2000);

            // Step 3: Execute AtomicSwap
            steps.add("Executing AtomicSwap: " + amountIn + " " + inputSymbol + " → " + outputSymbol);
            String poolPartyStr = poolContract.payload.getPoolParty.getParty;
            Pool.AtomicSwap swapChoice = new Pool.AtomicSwap(
                    new Party(xParty),
                    traderTokenCid,
                    inputSymbol,
                    amountIn,
                    outputSymbol,
                    minOutput,
                    5000L, // 50% max price impact
                    Instant.now().plusSeconds(600)
            );
            String cmdId = UUID.randomUUID().toString();
            txPacer.awaitSlot(3000);

            com.daml.ledger.api.v2.TransactionOuterClass.Transaction txn = ledgerApi.exerciseAndGetTransaction(
                    poolContract.contractId,
                    swapChoice,
                    cmdId,
                    List.of(xParty, poolPartyStr),
                    List.of(poolPartyStr, xParty)
            ).join();

            steps.add("✓ Swap transaction committed: " + txn.getUpdateId());
            result.put("transactionId", txn.getUpdateId());

            // Step 4: Parse results from flat Transaction
            String outputTokenCid = null;
            String newPoolCid = null;
            for (com.daml.ledger.api.v2.EventOuterClass.Event evt : txn.getEventsList()) {
                if (evt.hasCreated()) {
                    var created = evt.getCreated();
                    var tmpl = created.getTemplateId();
                    if ("Token.Token".equals(tmpl.getModuleName()) && "Token".equals(tmpl.getEntityName())) {
                        // This is the output token for trader
                        outputTokenCid = created.getContractId();
                    }
                    if ("AMM.Pool".equals(tmpl.getModuleName()) && "Pool".equals(tmpl.getEntityName())) {
                        newPoolCid = created.getContractId();
                    }
                }
            }
            result.put("outputTokenCid", outputTokenCid);
            result.put("newPoolCid", newPoolCid);

            // Step 5: Verify new pool reserves
            if (newPoolCid != null) {
                steps.add("Verifying updated reserves...");
                txPacer.awaitSlot(1500);
                List<LedgerApi.ActiveContract<Pool>> updatedPools = ledgerApi.getActiveContractsForParty(Pool.class, operator).join();
                String finalPoolCid = newPoolCid;
                LedgerApi.ActiveContract<Pool> newPool = updatedPools.stream()
                        .filter(p -> p.contractId.getContractId.equals(finalPoolCid))
                        .findFirst().orElse(null);
                if (newPool != null) {
                    result.put("reservesAfter", Map.of(
                            newPool.payload.getSymbolA, newPool.payload.getReserveA.toPlainString(),
                            newPool.payload.getSymbolB, newPool.payload.getReserveB.toPlainString()
                    ));
                    steps.add("✓ Reserves updated successfully");
                }
            }

            result.put("success", true);
            result.put("steps", steps);
            result.put("message", "E2E swap test completed successfully!");
            return ResponseEntity.ok(result);

        } catch (Exception ex) {
            String errorMsg = exceptionText(ex);
            steps.add("✗ Error: " + errorMsg);
            result.put("success", false);
            result.put("error", errorMsg);
            result.put("steps", steps);
            return ResponseEntity.status(500).body(result);
        }
    }
}

