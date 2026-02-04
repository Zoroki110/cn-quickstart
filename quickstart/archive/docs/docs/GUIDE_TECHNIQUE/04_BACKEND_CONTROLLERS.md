# MODULE 04 - BACKEND CONTROLLERS REST API

**Auteur**: Documentation technique ClearportX  
**Date**: 2025-10-21  
**Version**: 1.0.0  
**Prérequis**: Module 01 (Architecture), Module 03 (DAML Swap System)

---

## TABLE DES MATIÈRES

1. [Vue d'ensemble de l'architecture backend](#1-vue-densemble-de-larchitecture-backend)
2. [SwapController - Endpoints de swap](#2-swapcontroller---endpoints-de-swap)
3. [LiquidityController - Endpoints de liquidité](#3-liquiditycontroller---endpoints-de-liquidité)
4. [LedgerHealthController - Diagnostics système](#4-ledgerhealthcontroller---diagnostics-système)
5. [Sécurité et authentification](#5-sécurité-et-authentification)
6. [Gestion d'erreurs et retry logic](#6-gestion-derreurs-et-retry-logic)
7. [Métriques et observabilité](#7-métriques-et-observabilité)

---

## 1. VUE D'ENSEMBLE DE L'ARCHITECTURE BACKEND

### 1.1 Stack technologique

```
┌────────────────────────────────────────────────────────────────┐
│ SPRING BOOT 3.4.2 BACKEND ARCHITECTURE                         │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│ Layer 1: REST Controllers (@RestController)                    │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ • SwapController.java       - /api/swap/*               │   │
│ │ • LiquidityController.java  - /api/liquidity/*          │   │
│ │ • LedgerHealthController.java - /api/health/*           │   │
│ │ • ClearportXInitController.java - /api/init/*           │   │
│ └──────────────────────────────────────────────────────────┘   │
│             ↓ calls                                             │
│                                                                 │
│ Layer 2: Services (@Service)                                   │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ • LedgerApi.java          - DAML Ledger API wrapper     │   │
│ │ • LedgerReader.java       - Query active contracts      │   │
│ │ • IdempotencyService.java - Prevent duplicate swaps     │   │
│ │ • LedgerHealthService.java - PQS sync monitoring        │   │
│ │ • SwapMetrics.java        - Prometheus metrics          │   │
│ └──────────────────────────────────────────────────────────┘   │
│             ↓ communicates with                                 │
│                                                                 │
│ Layer 3: External Systems                                      │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ • Canton Ledger API (gRPC) - DAML transaction submission│   │
│ │ • PQS PostgreSQL           - Contract query database    │   │
│ │ • Prometheus               - Metrics scraping           │   │
│ │ • Keycloak OAuth2          - JWT authentication         │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### 1.2 Flow de requête typique

```
┌────────────────────────────────────────────────────────────────┐
│ REQUEST FLOW: Frontend → Backend → Canton → Response           │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│ 1. Frontend HTTP Request                                       │
│    POST /api/swap/atomic                                       │
│    Headers:                                                    │
│      Authorization: Bearer <JWT>                               │
│      X-Idempotency-Key: swap-12345-abc                         │
│    Body: { poolId, inputSymbol, inputAmount, minOutput }      │
│                                                                 │
│ 2. Spring Security Filter Chain                                │
│    ┌─────────────────────────────────────────────────────┐    │
│    │ a. WebSecurityConfig                                │    │
│    │    → Validate JWT signature (Keycloak public key)   │    │
│    │    → Extract JWT claims (subject, party)            │    │
│    │ b. PartyGuard                                       │    │
│    │    → @PreAuthorize check                            │    │
│    │    → Verify party authorization                     │    │
│    │ c. RateLimiterConfig (if devnet)                    │    │
│    │    → Global rate limit: 0.4 TPS                     │    │
│    │    → Per-party rate limit: 10 RPM                   │    │
│    └─────────────────────────────────────────────────────┘    │
│                                                                 │
│ 3. Controller Method Execution                                 │
│    SwapController.atomicSwap(@AuthenticationPrincipal Jwt)     │
│    ┌─────────────────────────────────────────────────────┐    │
│    │ a. Extract trader from JWT claims                   │    │
│    │ b. Validate input (SwapValidator)                   │    │
│    │ c. Check idempotency (IdempotencyService)           │    │
│    │ d. Query pool & tokens (LedgerApi.getActiveContracts)│   │
│    │ e. Create AtomicSwapProposal (DAML template)        │    │
│    │ f. Submit to Canton Ledger API (gRPC)               │    │
│    │ g. Parse Receipt from transaction result            │    │
│    │ h. Update metrics (SwapMetrics)                     │    │
│    │ i. Cache response (IdempotencyService)              │    │
│    └─────────────────────────────────────────────────────┘    │
│                                                                 │
│ 4. Canton Ledger Processing                                    │
│    ┌─────────────────────────────────────────────────────┐    │
│    │ • DAML interpretation (smart contract execution)    │    │
│    │ • Authorization checks (signatories/observers)      │    │
│    │ • Privacy filtering (only visible to stakeholders)  │    │
│    │ • Consensus (Canton Network validators)             │    │
│    │ • Contract creation/archival (ACS update)           │    │
│    └─────────────────────────────────────────────────────┘    │
│                                                                 │
│ 5. Response to Frontend                                        │
│    HTTP 200 OK                                                 │
│    {                                                            │
│      "receiptCid": "00abc123...",                              │
│      "trader": "Alice::1220...",                               │
│      "inputSymbol": "ETH",                                     │
│      "outputSymbol": "USDC",                                   │
│      "amountIn": "99.925",                                     │
│      "amountOut": "181277.45",                                 │
│      "timestamp": "2025-01-15T10:30:45Z"                       │
│    }                                                            │
│                                                                 │
│ TOTAL LATENCY: ~500-800ms                                      │
│ • Security checks: ~50ms                                       │
│ • Contract queries: ~100ms (PQS indexed)                       │
│ • DAML transaction: ~300-500ms (Canton consensus)              │
│ • Response serialization: ~50ms                                │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

---

## 2. SWAPCONTROLLER - ENDPOINTS DE SWAP

### 2.1 Architecture du controller

**Fichier**: `backend/src/main/java/com/digitalasset/quickstart/controller/SwapController.java` (666 lignes)

**Annotations clés**:
```java
@RestController                     // ← Spring MVC REST controller
@RequestMapping("/api/swap")        // ← Base path pour tous les endpoints
@CrossOrigin(origins = {...})       // ← CORS config (localhost:3000, 3001)
public class SwapController {
    private final LedgerApi ledger;              // ← DAML Ledger API wrapper
    private final AuthUtils authUtils;           // ← JWT/Party utilities
    private final PartyMappingService partyMapping;  // ← JWT subject → Canton party
    private final SwapMetrics swapMetrics;       // ← Prometheus metrics
    private final SwapValidator swapValidator;   // ← Input validation
    private final IdempotencyService idempotency; // ← Duplicate prevention
}
```

### 2.2 Endpoint POST /api/swap/atomic - Swap atomique

**Signature** (lines 421-428):
```java
@PostMapping("/atomic")
@WithSpan  // ← OpenTelemetry distributed tracing
@PreAuthorize("@partyGuard.isAuthenticated(#jwt)")  // ← Security check
public CompletableFuture<AtomicSwapResponse> atomicSwap(
    @AuthenticationPrincipal Jwt jwt,  // ← Injecté par Spring Security
    @Valid @RequestBody PrepareSwapRequest req,  // ← Validated DTO
    @RequestHeader(value = SwapConstants.IDEMPOTENCY_HEADER, required = false) String idempotencyKey
)
```

**PrepareSwapRequest DTO** (structure):
```java
public class PrepareSwapRequest {
    @NotNull @NotBlank
    private String poolId;           // "ETH-USDC-pool-0.3%"
    
    @NotNull @NotBlank
    private String inputSymbol;      // "ETH"
    
    @NotNull @NotBlank
    private String outputSymbol;     // "USDC"
    
    @NotNull @DecimalMin("0.0000000001")  // Min 10 decimals
    private BigDecimal inputAmount;  // 100.0
    
    @NotNull @DecimalMin("0.0")
    private BigDecimal minOutput;    // 180000.0 (slippage protection)
    
    @NotNull @Min(0) @Max(5000)     // Max 50% price impact
    private Integer maxPriceImpactBps;  // 1000 (10%)
}
```

**Flow complet** (lines 429-664):

```
┌────────────────────────────────────────────────────────────────┐
│ POST /api/swap/atomic - ATOMIC SWAP FLOW (16 ÉTAPES)           │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│ ÉTAPE 1: Security Validation (lines 429-433)                   │
│   if (jwt == null || jwt.getSubject() == null) {               │
│     throw UNAUTHORIZED("JWT subject missing");                 │
│   }                                                             │
│   String trader = partyMapping.mapJwtSubjectToParty(jwt);      │
│   ← Exemple: "alice@example.com" → "Alice::1220abc..."         │
│                                                                 │
│ ÉTAPE 2: Idempotency Check (lines 439-446)                     │
│   if (idempotencyKey != null) {                                │
│     Object cached = idempotencyService.checkIdempotency(key);  │
│     if (cached != null) {                                      │
│       return CompletableFuture.completedFuture(cached);        │
│     }                                                           │
│   }                                                             │
│   ← Prevent duplicate swaps (15 min cache TTL)                 │
│                                                                 │
│ ÉTAPE 3: Input Validation (lines 450-454)                      │
│   swapValidator.validateTokenPair(inputSymbol, outputSymbol);  │
│   ← Check: symbols not equal, not empty, valid format          │
│   swapValidator.validateInputAmount(inputAmount);              │
│   ← Check: > 0, scale <= 10, not infinity/NaN                  │
│   swapValidator.validateMinOutput(minOutput);                  │
│   ← Check: >= 0, reasonable value                              │
│   swapValidator.validateMaxPriceImpact(maxPriceImpactBps);     │
│   ← Check: 0 <= bps <= 5000 (max 50%)                          │
│                                                                 │
│ ÉTAPE 4: Enforce Scale (lines 457-458)                         │
│   BigDecimal inputAmount = req.inputAmount.setScale(           │
│     SwapConstants.SCALE,  // 10 decimals                       │
│     RoundingMode.DOWN     // Truncate (no rounding up)         │
│   );                                                            │
│   ← Canton DAML requires exact scale=10 for Numeric 10         │
│                                                                 │
│ ÉTAPE 5: Record Metrics Start (lines 464-465)                  │
│   long startTime = System.currentTimeMillis();                 │
│   swapMetrics.recordSwapPrepared(inputSymbol, outputSymbol);   │
│   ← Increment swap_prepared_total{pair="ETH-USDC"} counter     │
│                                                                 │
│ ÉTAPE 6: Find Pool (lines 468-500)                             │
│   ledger.getActiveContracts(Pool.class)                        │
│     .thenCompose(pools -> {                                    │
│       // Update active pools metric                            │
│       long activePoolsCount = pools.stream()                   │
│         .filter(p -> p.reserveA > 0 && p.reserveB > 0)         │
│         .count();                                              │
│       swapMetrics.setActivePoolsCount((int) activePoolsCount); │
│                                                                 │
│       // Find pool: either by poolId OR auto-discover          │
│       Optional<Pool> maybePool;                                │
│       if (req.poolId != null) {                                │
│         // Exact poolId match                                  │
│         maybePool = pools.stream()                             │
│           .filter(p -> p.poolId.equals(req.poolId))            │
│           .filter(p -> p.reserveA > 0 && p.reserveB > 0)       │
│           .findFirst();                                        │
│       } else {                                                  │
│         // Auto-discover pool by token symbols                 │
│         maybePool = pools.stream()                             │
│           .filter(p -> (p.symbolA == inputSymbol &&            │
│                         p.symbolB == outputSymbol) ||          │
│                        (p.symbolA == outputSymbol &&           │
│                         p.symbolB == inputSymbol))             │
│           .filter(p -> p.reserveA > 0 && p.reserveB > 0)       │
│           .findFirst();                                        │
│       }                                                         │
│                                                                 │
│       if (maybePool.isEmpty()) {                               │
│         throw NOT_FOUND("Pool not found or has no liquidity"); │
│       }                                                         │
│     });                                                         │
│                                                                 │
│   ← Query PQS (fast, indexed) ou Ledger API (ACS snapshot)     │
│                                                                 │
│ ÉTAPE 7: Find Trader Token (lines 512-528)                     │
│   ledger.getActiveContracts(Token.class)                       │
│     .thenCompose(tokens -> {                                   │
│       Optional<Token> maybeToken = tokens.stream()             │
│         .filter(t -> t.symbol == inputSymbol)                  │
│         .filter(t -> t.owner == trader)                        │
│         .filter(t -> t.amount >= inputAmount)                  │
│         .findFirst();                                          │
│                                                                 │
│       if (maybeToken.isEmpty()) {                              │
│         throw BAD_REQUEST("Insufficient " + inputSymbol);      │
│       }                                                         │
│     });                                                         │
│                                                                 │
│ ÉTAPE 8: Calculate Deadline (lines 530)                        │
│   Instant deadline = Instant.now().plusSeconds(300);  // 5 min │
│   ← DAML swap doit être exécuté avant deadline                 │
│                                                                 │
│ ÉTAPE 9: Create AtomicSwapProposal Template (lines 533-552)    │
│   AtomicSwapProposal proposal = new AtomicSwapProposal(        │
│     new Party(trader),                 // trader              │
│     pool.contractId,                   // poolCid             │
│     new Party(poolParty),              // poolParty           │
│     new Party(poolOperator),           // poolOperator        │
│     new Party(issuerA),                // issuerA             │
│     new Party(issuerB),                // issuerB             │
│     symbolA,                           // symbolA             │
│     symbolB,                           // symbolB             │
│     feeBps,                            // 30 (0.3%)           │
│     maxTTL,                            // hours(2)            │
│     new Party(protocolFeeReceiver),    // ClearportX treasury │
│     traderToken.contractId,            // traderInputTokenCid │
│     inputSymbol,                       // "ETH"               │
│     inputAmount,                       // 100.0               │
│     outputSymbol,                      // "USDC"              │
│     minOutput,                         // 180000.0            │
│     (long) maxPriceImpactBps,          // 1000 (10%)          │
│     deadline                           // now + 5 min         │
│   );                                                            │
│                                                                 │
│ ÉTAPE 10: Create Proposal on Ledger (lines 558-564)            │
│   ledger.createAndGetCid(                                      │
│     proposal,                          // template            │
│     List.of(trader),                   // actAs: trader       │
│     List.of(),                         // readAs: none        │
│     commandId + "-create",             // unique command ID   │
│     proposal.TEMPLATE_ID               // package ID + module │
│   ).thenCompose(proposalCid -> { ... });                       │
│                                                                 │
│   ← Canton Ledger API gRPC call (CreateCommand)                │
│   ← Returns: proposalCid = ContractId<AtomicSwapProposal>      │
│                                                                 │
│ ÉTAPE 11: Exercise ExecuteAtomicSwap (lines 566-575)           │
│   AtomicSwapProposal.ExecuteAtomicSwap choice =                │
│     new AtomicSwapProposal.ExecuteAtomicSwap();                │
│                                                                 │
│   ledger.exerciseAndGetResult(                                 │
│     proposalCid,                       // contract to exercise │
│     choice,                            // choice to call       │
│     commandId + "-execute"             // unique command ID   │
│   ).thenCompose(receiptCid -> { ... });                        │
│                                                                 │
│   ← Nested DAML execution:                                     │
│     1. PrepareSwap (protocol fee extraction)                   │
│     2. ExecuteSwap (AMM calculation + swap)                    │
│     3. Returns: receiptCid = ContractId<Receipt>               │
│                                                                 │
│ ÉTAPE 12: Fetch Receipt (lines 577-594)                        │
│   ledger.getActiveContracts(Receipt.class)                     │
│     .thenApply(receipts -> {                                   │
│       Optional<Receipt> maybeReceipt = receipts.stream()       │
│         .filter(r -> r.contractId == receiptCid)               │
│         .findFirst();                                          │
│                                                                 │
│       if (maybeReceipt.isEmpty()) {                            │
│         // Receipt not yet indexed by PQS - return minimal     │
│         return new AtomicSwapResponse(                         │
│           receiptCid, trader, inputSymbol, outputSymbol,       │
│           inputAmount, minOutput, now                          │
│         );                                                      │
│       }                                                         │
│                                                                 │
│       Receipt receipt = maybeReceipt.get();                    │
│     });                                                         │
│                                                                 │
│ ÉTAPE 13: Record Metrics Success (lines 598-614)               │
│   long executionTimeMs = System.currentTimeMillis() - startTime;│
│                                                                 │
│   swapMetrics.recordSwapExecuted(                              │
│     inputSymbol, outputSymbol,                                 │
│     amountIn, amountOut,                                       │
│     priceImpactBps,                                            │
│     executionTimeMs                                            │
│   );                                                            │
│                                                                 │
│   // Fee metrics (25% protocol, 75% LP)                        │
│   BigDecimal totalFee = amountIn * 0.003;  // 0.3%             │
│   BigDecimal protocolFee = totalFee * 0.25; // 25%             │
│   BigDecimal lpFee = totalFee * 0.75;       // 75%             │
│   swapMetrics.recordProtocolFee(inputSymbol, protocolFee);     │
│   swapMetrics.recordLpFee(inputSymbol, lpFee);                 │
│                                                                 │
│ ÉTAPE 14: Build Response (lines 621-629)                       │
│   AtomicSwapResponse response = new AtomicSwapResponse(        │
│     receiptCid.getContractId,          // "00abc123..."       │
│     receipt.trader.getParty,           // "Alice::1220..."    │
│     receipt.inputSymbol,               // "ETH"               │
│     receipt.outputSymbol,              // "USDC"              │
│     receipt.amountIn.toPlainString(),  // "99.925"            │
│     receipt.amountOut.toPlainString(), // "181277.45"         │
│     receipt.timestamp.toString()       // "2025-01-15T10:30:45Z"│
│   );                                                            │
│                                                                 │
│ ÉTAPE 15: Cache Idempotency (lines 632-639)                    │
│   if (idempotencyKey != null) {                                │
│     idempotencyService.registerSuccess(                        │
│       idempotencyKey,                  // "swap-12345-abc"    │
│       commandId,                       // UUID                │
│       receiptCid.getContractId,        // "00abc123..."       │
│       response                         // Full response object│
│     );                                                          │
│   }                                                             │
│   ← Cached for 15 minutes (prevent duplicate swaps)            │
│                                                                 │
│ ÉTAPE 16: Return CompletableFuture (line 641)                  │
│   return response;  // HTTP 200 OK                             │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

**Gestion d'erreurs** (lines 647-663):

```java
.exceptionally(ex -> {
    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
    String errorMessage = "Atomic swap failed: " + cause.getMessage();

    // Record metrics: swap failure
    swapMetrics.recordSwapFailed(inputSymbol, outputSymbol, cause.getMessage());

    logger.error("METRIC: atomic_swap_failure{{reason=\"{}\"}}",
        cause.getMessage().replace("\"", "'"));

    // Re-throw appropriate HTTP status
    if (cause instanceof ResponseStatusException) {
        throw (ResponseStatusException) cause;  // Already has status code
    }

    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage, cause);
});
```

**Codes d'erreur HTTP** :
- `401 UNAUTHORIZED` : JWT missing ou invalid
- `400 BAD_REQUEST` : Invalid input (amount < 0, invalid symbols, etc.)
- `404 NOT_FOUND` : Pool ou Token non trouvé
- `409 CONFLICT` : CONTRACT_NOT_FOUND (stale CID, retry needed)
- `422 UNPROCESSABLE_ENTITY` : Slippage protection triggered
- `408 REQUEST_TIMEOUT` : Deadline expired
- `429 TOO_MANY_REQUESTS` : Rate limit exceeded (devnet 0.4 TPS)

### 2.3 Endpoint POST /api/swap/prepare - Two-Step Swap (Part 1)

**Usage** : Tests DAML, debugging (production utilise `/atomic`)

**Flow** (lines 78-247):

```
POST /api/swap/prepare
{
  "poolId": "ETH-USDC-pool-0.3%",
  "inputSymbol": "ETH",
  "outputSymbol": "USDC",
  "inputAmount": "100.0",
  "minOutput": "180000.0",
  "maxPriceImpactBps": 1000
}

↓

1. Validate pool exists (with reserves > 0)
2. Find trader's input token (amount >= inputAmount)
3. Create SwapRequest contract
4. Exercise SwapRequest.PrepareSwap
   • TransferSplit: protocol fee → treasury
   • Transfer: remainder → poolParty
   • Create SwapReady

↓

Response:
{
  "swapReadyCid": "00ghi789...",
  "poolInputTokenCid": "00jkl012...",
  "inputSymbol": "ETH",
  "outputSymbol": "USDC",
  "inputAmount": "99.925",     ← AFTER protocol fee (100 - 0.075)
  "minOutput": "180000.0"
}
```

**Pourquoi `createAndGetCid` au lieu de `create` ?** (lines 186-194):

```java
// OLD APPROACH (race condition):
// create SwapRequest → wait for ACS sync → query by filter → get CID
// PROBLEM: ACS lag = CID not found immediately

// NEW APPROACH (deterministic, race-free):
ledger.createAndGetCid(
    swapRequest,
    List.of(trader),        // actAs: trader creates
    List.of(poolParty),     // readAs: poolParty observes
    commandId + "-create",
    swapRequest.templateId() // Extract from instance (correct package ID)
).thenCompose(swapRequestCid -> {
    // CID extracted from transaction tree (immediate, no query)
    logger.info("✅ SwapRequest created with CID: {} (via transaction tree)", 
        swapRequestCid.getContractId);
    
    // Now exercise PrepareSwap on the CID
    return ledger.exerciseAndGetResult(swapRequestCid, prepareChoice, ...);
});
```

**Avantages** :
- ✅ Pas de race condition (CID immédiat depuis transaction tree)
- ✅ Pas besoin de query ACS (plus rapide)
- ✅ Déterministe (même si PQS lag)

### 2.4 Endpoint POST /api/swap/execute - Two-Step Swap (Part 2)

**Flow** (lines 258-415):

```
POST /api/swap/execute
{
  "swapReadyCid": "00ghi789..."
}

↓

1. Fetch SwapReady contract (validate exists)
2. Verify executingParty == poolParty (security)
3. Exercise SwapReady.ExecuteSwap (with automatic retry)
   • Fetch pool (actual reserves)
   • AMM calculation
   • Consolidate input
   • Transfer output
   • Update pool reserves
   • Create Receipt
4. Fetch Receipt (get swap details)
5. Record metrics (swap executed, fees collected)

↓

Response:
{
  "receiptCid": "00mno345...",
  "trader": "Alice::1220...",
  "inputSymbol": "ETH",
  "outputSymbol": "USDC",
  "amountIn": "99.925",
  "amountOut": "181277.45",
  "timestamp": "2025-01-15T10:30:45Z"
}
```

**StaleAcsRetry pattern** (lines 318-326):

```java
// Automatic retry on CONTRACT_NOT_FOUND (stale pool CID)
return StaleAcsRetry.run(
    () -> ledger.exerciseAndGetResult(
        swapReadyCid,
        executeChoice,
        commandId
    ),
    () -> logger.info("Refreshing ACS for ExecuteSwap retry (stale pool CID)"),
    "ExecuteSwap"
).thenCompose(receiptCid -> { ... });
```

**Comment ça marche ?** (StaleAcsRetry.java):

```java
public static <T> CompletableFuture<T> run(
    Supplier<CompletableFuture<T>> operation,
    Runnable refreshAcs,
    String operationName
) {
    return operation.get().exceptionally(ex -> {
        if (ex.getMessage().contains("CONTRACT_NOT_FOUND")) {
            logger.warn("⚠️  Stale ACS detected for {} - retrying once", operationName);
            refreshAcs.run();  // Re-fetch ACS snapshot
            return operation.get().join();  // Retry ONCE
        }
        throw new CompletionException(ex);  // Other errors propagate
    });
}
```

**Pourquoi c'est nécessaire ?**
- Pool CID peut changer entre PrepareSwap et ExecuteSwap (concurrent swap)
- Archive-and-recreate pattern (pool archivé, nouveau pool créé)
- Retry automatique évite erreur 409 CONFLICT exposée au frontend

---

## 3. LIQUIDITYCONTROLLER - ENDPOINTS DE LIQUIDITÉ

### 3.1 Endpoint POST /api/liquidity/add

**Fichier**: `LiquidityController.java` (250 lignes)

**Flow complet** (lines 66-248):

```
POST /api/liquidity/add
{
  "poolId": "ETH-USDC-pool-0.3%",
  "amountA": "100.0",      // ETH
  "amountB": "200000.0",   // USDC
  "minLPTokens": "14000.0" // Slippage protection
}

Authorization: Bearer <JWT>  ← Must be liquidityProvider

↓

ÉTAPE 1: Extract liquidityProvider from JWT (lines 73-80)
  jwtSubject = "alice@example.com"
  liquidityProvider = partyMapping.mapJwtSubjectToParty(jwtSubject)
                    = "Alice::1220abc..."

ÉTAPE 2: Find Pool (lines 91-111)
  pools = ledger.getActiveContracts(Pool.class)
  maybePool = pools.stream()
    .filter(p -> p.poolId == "ETH-USDC-pool-0.3%")
    .findFirst()
  
  if (maybePool.isEmpty()) {
    throw NOT_FOUND("Pool not found")
  }
  
  pool = maybePool.get()
  poolParty = pool.poolParty.getParty
  lpIssuer = pool.lpIssuer.getParty

ÉTAPE 3: Validate Provider's Tokens (lines 114-154)
  tokens = ledger.getActiveContracts(Token.class)
  
  // Find Token A owned by liquidityProvider
  maybeTokenA = tokens.stream()
    .filter(t -> t.symbol == pool.symbolA)
    .filter(t -> t.owner == liquidityProvider)
    .findFirst()
  
  if (maybeTokenA.isEmpty()) {
    throw NOT_FOUND("Token ETH not found for provider")
  }
  
  // Check sufficient balance
  if (tokenA.amount < amountA) {
    throw UNPROCESSABLE_ENTITY("Insufficient ETH: have 50, need 100")
  }
  
  // Same for Token B (USDC)
  ...

ÉTAPE 4: Create AddLiquidity Choice (lines 165-173)
  Pool.AddLiquidity choice = new Pool.AddLiquidity(
    new Party(liquidityProvider),
    new ContractId(tokenA.contractId),
    new ContractId(tokenB.contractId),
    amountA,        // 100.0 ETH
    amountB,        // 200000.0 USDC
    minLPTokens,    // 14000.0 LP tokens min
    deadline        // now + 10 minutes
  );

ÉTAPE 5: Exercise with Multi-Party Authorization (lines 179-193)
  // CRITICAL: AddLiquidity needs 3 parties to authorize!
  actAs = [liquidityProvider, poolParty, lpIssuer]
  
  StaleAcsRetry.run(
    () -> ledger.exerciseAndGetResultWithParties(
      pool.contractId,
      choice,
      commandId,
      List.of(liquidityProvider, poolParty, lpIssuer),  // actAs
      List.of(poolParty),  // readAs
      List.of()            // disclosed
    ),
    () -> logger.info("Refreshing ACS for retry"),
    "AddLiquidity"
  ).thenApply(result -> { ... });

ÉTAPE 6: Parse Result (lines 194-212)
  // DAML returns Tuple2<ContractId<LPToken>, ContractId<Pool>>
  ContractId<LPToken> lpTokenCid = result.get_1;
  ContractId<Pool> newPoolCid = result.get_2;
  
  // Calculate new reserves
  newReserveA = pool.reserveA + amountA  // 1000 + 100 = 1100 ETH
  newReserveB = pool.reserveB + amountB  // 2M + 200K = 2.2M USDC
  
  logger.info("METRIC: add_liquidity_success{{provider=\"{}\", pool=\"{}\"}}",
    liquidityProvider, poolId);

↓

Response:
{
  "lpTokenCid": "00pqr678...",
  "newPoolCid": "00stu901...",
  "newReserveA": "1100.0",
  "newReserveB": "2200000.0"
}
```

**Pourquoi Multi-Party Authorization ?** (lines 175-177):

```java
// AddLiquidity DAML choice requires:
// 1. liquidityProvider authorizes (owns tokenA, tokenB)
// 2. poolParty authorizes (receives tokens, updates pool)
// 3. lpIssuer authorizes (creates new LPToken)

// If ANY party missing from actAs → ERROR:
// "Interpretation error: Missing authorization for party X"

logger.info("Exercising AddLiquidity with actAs=[{}, {}, {}]",
    liquidityProvider, poolParty, lpIssuer);

ledger.exerciseAndGetResultWithParties(
    pool.contractId,
    choice,
    commandId,
    List.of(liquidityProvider, poolParty, lpIssuer),  // MUST include all 3!
    List.of(poolParty),  // readAs for visibility
    List.of()  // disclosed contracts (none)
);
```

**Gestion d'erreurs** (lines 216-247):

```java
.exceptionally(ex -> {
    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
    String errorMessage = cause.getMessage();

    if (errorMessage.contains("CONTRACT_NOT_FOUND")) {
        // Pool or token archived between validation and exercise
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Pool or token changed; refresh and retry");
    }

    if (errorMessage.contains("Deadline passed")) {
        throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, 
            "Deadline expired");
    }

    if (errorMessage.contains("Slippage")) {
        // minLPTokens not met (price changed)
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "Slippage protection triggered: " + errorMessage);
    }

    logger.error("METRIC: add_liquidity_failure{{reason=\"{}\", pool=\"{}\"}}",
        errorMessage, poolId);

    if (cause instanceof ResponseStatusException) {
        throw (ResponseStatusException) cause;
    }

    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage, cause);
});
```

---

## 4. LEDGERHEALTHCONTROLLER - DIAGNOSTICS SYSTÈME

### 4.1 Endpoint GET /api/health/ledger

**Fichier**: `service/LedgerHealthService.java` (253 lignes)

**Rôle** :
- Diagnostiquer sync Canton Ledger ↔ PQS
- Détecter package allowlist issues
- Monitorer nombre de contracts actifs
- Vérifier disponibilité AtomicSwap template

**Flow complet** (lines 50-153):

```
GET /api/health/ledger

↓

ÉTAPE 1: Query PQS Offset (lines 56-58)
  SELECT COALESCE(MAX(pk), 0) as max_offset FROM __events
  
  pqsOffset = 123456  ← Current PQS watermark
  
  ← Indique jusqu'où PQS a indexé le ledger

ÉTAPE 2: Query Package Names (lines 61-63)
  SELECT DISTINCT package_name FROM __contract_tpe LIMIT 20
  
  packageNames = [
    "clearportx-amm",
    "daml-prim",
    "daml-stdlib",
    ...
  ]
  
  ← Liste tous les packages que PQS a indexé

ÉTAPE 3: Count ClearportX Contracts (lines 67-76)
  SELECT COUNT(*) FROM __contracts c
  JOIN __contract_tpe ct ON c.tpe_pk = ct.pk
  WHERE c.archived_at_ix IS NULL            ← Active contracts only
  AND (ct.module_name LIKE '%AMM%'
       OR ct.module_name LIKE '%Token%'
       OR ct.module_name LIKE '%LPToken%'
       OR ct.package_name LIKE '%clearportx%')
  
  clearportxCount = 42  ← Number of active ClearportX contracts

ÉTAPE 4: Determine Sync Status (lines 80-105)
  hasClearportxPackage = packageNames.contains("clearportx-amm")
  hasClearportxTemplates = clearportxCount > 0
  
  if (!hasClearportxPackage) {
    // Package not indexed at all
    status = "PACKAGE_NOT_INDEXED"
    diagnostic = "ClearportX package not found in PQS. Check allowlist."
    logger.warn("⚠️  PACKAGE_MISMATCH: ClearportX not in PQS")
    logMetric("pqs_package_mismatch", 1)
  } else if (!hasClearportxTemplates) {
    // Package exists but no contracts
    status = "SYNCING"
    diagnostic = "Package indexed but no contracts yet (PQS catching up)"
  } else {
    // All good
    status = "OK"
    logger.info("✅ Health check OK: pqsOffset={}, contracts={}", 
        pqsOffset, clearportxCount)
  }

ÉTAPE 5: Check AtomicSwap Availability (lines 116)
  atomicSwapAvailable = checkAtomicSwapAvailable()
  
  // Try to load class from generated bindings
  try {
    Class.forName("clearportx_amm.amm.atomicswap.AtomicSwapProposal");
    return true;  ← AtomicSwap template available
  } catch (ClassNotFoundException) {
    return false; ← Old DAR without AtomicSwap
  }

ÉTAPE 6: Get Active Pools Count (lines 119-125)
  poolsActive = ledgerReader.pools().join().size()
  
  ← Query Canton Ledger API (ACS) for active Pool contracts

ÉTAPE 7: Get Package ID (lines 128-138)
  SELECT DISTINCT package_id FROM __contract_tpe
  WHERE package_name LIKE 'clearportx%'
  LIMIT 1
  
  clearportxPackageId = "abc123def456..."  ← SHA256 hash of DAR

ÉTAPE 8: Emit Metrics (lines 141-142)
  logMetric("pqs_offset", pqsOffset)
  logMetric("clearportx_contract_count", clearportxCount)
  
  ← Logs in Prometheus format:
     METRIC: pqs_pqs_offset=123456
     METRIC: pqs_clearportx_contract_count=42

↓

Response:
{
  "status": "OK",                         // OK | SYNCING | PACKAGE_NOT_INDEXED | ERROR
  "synced": true,                         // PQS caught up with Canton
  "pqsOffset": 123456,                    // PQS watermark
  "pqsPackageNames": ["clearportx-amm", ...],
  "clearportxContractCount": 42,          // Active contracts
  "hasClearportxPackage": true,
  "poolsActive": 3,                       // Pools from Canton ACS
  "clearportxPackageId": "abc123def456...",
  "darVersion": "1.0.1",
  "atomicSwapAvailable": true,            // AtomicSwap template exists
  "diagnostic": null                      // Only present if error
}
```

**Scénarios de diagnostic** :

```
SCÉNARIO 1: Package Allowlist Issue
Response:
{
  "status": "PACKAGE_NOT_INDEXED",
  "synced": false,
  "pqsPackageNames": ["daml-prim", "daml-stdlib"],  ← No "clearportx-amm"!
  "clearportxContractCount": 0,
  "hasClearportxPackage": false,
  "diagnostic": "ClearportX package not found in PQS. Check PQS allowlist configuration."
}

ACTION: Update compose.yaml PQS allowlist:
  PQS_ALLOWLIST_PACKAGES: |
    {
      "clearportx-amm": {
        "name": "clearportx-amm",
        "version": "1.0.1"
      }
    }


SCÉNARIO 2: PQS Syncing (Catching Up)
Response:
{
  "status": "SYNCING",
  "synced": false,
  "pqsPackageNames": ["clearportx-amm", ...],
  "clearportxContractCount": 0,           ← Package indexed, but no contracts yet
  "hasClearportxPackage": true,
  "diagnostic": "Package indexed but no contracts yet (PQS catching up)"
}

ACTION: Wait for PQS to index. Check:
  docker logs clearportx-pqs-1
  → Should see: "Indexed event 12345 / 50000"


SCÉNARIO 3: All Healthy
Response:
{
  "status": "OK",
  "synced": true,
  "clearportxContractCount": 42,
  "poolsActive": 3,
  "atomicSwapAvailable": true
}

ACTION: System ready! Frontend can enable swap UI.
```

### 4.2 Endpoint GET /api/health/package-info

**Flow** (lines 212-243):

```
GET /api/health/package-info

↓

Query PQS:
  SELECT
    ct.package_name,
    COUNT(*) as active_contract_count,
    COUNT(CASE WHEN c.archived_at_ix IS NOT NULL THEN 1 END) as archived_count
  FROM __contracts c
  JOIN __contract_tpe ct ON c.tpe_pk = ct.pk
  GROUP BY ct.package_name
  ORDER BY active_contract_count DESC
  LIMIT 50

↓

Response:
{
  "packages": [
    {
      "package_name": "clearportx-amm",
      "active_contract_count": 42,
      "archived_contract_count": 158
    },
    {
      "package_name": "daml-prim",
      "active_contract_count": 5,
      "archived_contract_count": 0
    },
    ...
  ],
  "totalPackages": 8
}
```

**Usage** : Debug package version mismatches, vérifier DAR upload réussi.

---

## 5. SÉCURITÉ ET AUTHENTIFICATION

### 5.1 WebSecurityConfig - Spring Security Setup

**Fichier**: `security/WebSecurityConfig.java`

**Configuration OAuth2 JWT** :

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // Enable @PreAuthorize
public class WebSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())  // JWT-based auth, no CSRF needed
            .authorizeHttpRequests(auth -> auth
                // Public endpoints (health checks, actuator)
                .requestMatchers("/api/health/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                
                // All other /api/* endpoints require authentication
                .requestMatchers("/api/**").authenticated()
                
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())  // Validate JWT signature with Keycloak public key
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            );
        
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        // Keycloak public key endpoint for JWT signature validation
        String jwkSetUri = keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/certs";
        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            // Extract roles from JWT claims
            // Canton Network uses "party" claim
            String party = jwt.getClaimAsString("party");
            if (party != null) {
                return List.of(new SimpleGrantedAuthority("ROLE_TRADER"));
            }
            return List.of();
        });
        return converter;
    }
}
```

### 5.2 PartyGuard - Authorization Checks

**Fichier**: `security/PartyGuard.java`

**Méthodes de vérification** :

```java
@Component("partyGuard")
public class PartyGuard {

    /**
     * Check if JWT is present and valid.
     * Used for endpoints that any authenticated user can access.
     */
    public boolean isAuthenticated(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            logger.warn("⚠️  Authentication failed: JWT or subject is null");
            return false;
        }
        
        logger.debug("✓ Authentication passed for JWT subject: {}", jwt.getSubject());
        return true;
    }

    /**
     * Check if the authenticated party is the pool operator.
     * Used for privileged endpoints like /api/swap/execute.
     */
    public boolean isPoolParty(Jwt jwt) {
        if (!isAuthenticated(jwt)) {
            return false;
        }
        
        String party = partyMappingService.mapJwtSubjectToParty(jwt.getSubject());
        String appProviderParty = authUtils.getAppProviderPartyId();
        
        boolean isPoolParty = party.equals(appProviderParty);
        
        if (!isPoolParty) {
            logger.warn("⚠️  Authorization failed: {} is not pool party (expected: {})",
                party, appProviderParty);
        }
        
        return isPoolParty;
    }

    /**
     * Check if the authenticated party can provide liquidity.
     * For now, same as isAuthenticated (all authenticated users can add liquidity).
     */
    public boolean isLiquidityProvider(Jwt jwt) {
        return isAuthenticated(jwt);
    }
}
```

**Usage dans controllers** :

```java
// SwapController.java
@PostMapping("/atomic")
@PreAuthorize("@partyGuard.isAuthenticated(#jwt)")  // ← Any authenticated user
public CompletableFuture<AtomicSwapResponse> atomicSwap(
    @AuthenticationPrincipal Jwt jwt,
    ...
) { ... }

// SwapController.java
@PostMapping("/execute")
@PreAuthorize("@partyGuard.isPoolParty(#jwt)")  // ← ONLY pool operator
public CompletableFuture<ExecuteSwapResponse> executeSwap(
    @AuthenticationPrincipal Jwt jwt,
    ...
) { ... }

// LiquidityController.java
@PostMapping("/add")
@PreAuthorize("@partyGuard.isLiquidityProvider(#jwt)")  // ← Any LP
public CompletableFuture<AddLiquidityResponse> addLiquidity(
    @AuthenticationPrincipal Jwt jwt,
    ...
) { ... }
```

### 5.3 PartyMappingService - JWT Subject → Canton Party

**Fichier**: `security/PartyMappingService.java`

**Mapping logic** :

```java
@Service
public class PartyMappingService {

    /**
     * Map JWT subject (user@example.com) to Canton party ID.
     * 
     * Examples:
     * - "alice@example.com" → "Alice::1220abc123..."
     * - "bob-trader" → "Bob::1220def456..."
     * 
     * In production, this queries a database mapping table.
     * For localnet testing, uses simple username extraction.
     */
    public String mapJwtSubjectToParty(String jwtSubject) {
        if (jwtSubject == null || jwtSubject.isEmpty()) {
            throw new IllegalArgumentException("JWT subject cannot be null or empty");
        }

        // LOCALNET MODE: Extract username from email
        if (environment.equals("localnet")) {
            String username = jwtSubject.contains("@") 
                ? jwtSubject.substring(0, jwtSubject.indexOf("@"))
                : jwtSubject;
            
            // Lookup in static mapping
            String party = staticMappings.get(username.toLowerCase());
            if (party != null) {
                logger.debug("Mapped JWT subject '{}' → Canton party '{}'", jwtSubject, party);
                return party;
            }
        }

        // DEVNET/PRODUCTION MODE: Query Canton Network identity service
        // or use party claim directly from JWT
        String partyClaim = extractPartyFromJwt(jwtSubject);
        if (partyClaim != null) {
            return partyClaim;
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "No Canton party mapping found for JWT subject: " + jwtSubject);
    }

    private String extractPartyFromJwt(String jwtSubject) {
        // Canton Network JWT includes "party" claim
        // Example JWT payload:
        // {
        //   "sub": "alice@example.com",
        //   "party": "Alice::1220abc123...",
        //   "iss": "https://keycloak.canton.network/realms/AppProvider"
        // }
        
        // Spring Security parses JWT into Jwt object
        // Access via @AuthenticationPrincipal Jwt jwt
        // party = jwt.getClaimAsString("party")
        
        // For now, return null (let caller handle mapping)
        return null;
    }
}
```

---

## 6. GESTION D'ERREURS ET RETRY LOGIC

### 6.1 StaleAcsRetry - Automatic CONTRACT_NOT_FOUND Retry

**Fichier**: `ledger/StaleAcsRetry.java`

**Problème** :
- Canton ACS (Active Contract Set) cached côté client
- Pool CID peut changer entre query et exercise (concurrent swap)
- Archive-and-recreate pattern = old CID invalide
- Erreur: `CONTRACT_NOT_FOUND`

**Solution** :

```java
public class StaleAcsRetry {

    /**
     * Run a ledger operation with automatic retry on CONTRACT_NOT_FOUND.
     * 
     * @param operation The operation to run (e.g., exerciseChoice)
     * @param refreshAcs Callback to refresh ACS snapshot before retry
     * @param operationName Name for logging (e.g., "ExecuteSwap")
     * @return CompletableFuture with result or propagated exception
     */
    public static <T> CompletableFuture<T> run(
        Supplier<CompletableFuture<T>> operation,
        Runnable refreshAcs,
        String operationName
    ) {
        return operation.get()
            .exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                String errorMessage = cause.getMessage();

                // Check if error is CONTRACT_NOT_FOUND (stale CID)
                if (errorMessage != null && errorMessage.contains("CONTRACT_NOT_FOUND")) {
                    logger.warn("⚠️  Stale ACS detected for {} - retrying once", operationName);
                    logger.warn("Error details: {}", errorMessage);

                    // Refresh ACS snapshot (optional callback)
                    refreshAcs.run();

                    // Retry ONCE (avoid infinite loop)
                    try {
                        T result = operation.get().join();  // Blocking retry
                        logger.info("✅ Retry succeeded for {}", operationName);
                        return result;
                    } catch (Exception retryEx) {
                        logger.error("❌ Retry failed for {}: {}", operationName, retryEx.getMessage());
                        throw new CompletionException(retryEx);
                    }
                }

                // Other errors: propagate immediately
                throw new CompletionException(cause);
            });
    }
}
```

**Usage** :

```java
// LiquidityController.java - AddLiquidity with retry
return StaleAcsRetry.run(
    () -> ledger.exerciseAndGetResultWithParties(
        pool.contractId,
        addLiquidityChoice,
        commandId,
        List.of(liquidityProvider, poolParty, lpIssuer),
        List.of(poolParty),
        List.of()
    ),
    () -> logger.info("Refreshing ACS for AddLiquidity retry"),
    "AddLiquidity"  // Operation name for logging
);

// SwapController.java - ExecuteSwap with retry
return StaleAcsRetry.run(
    () -> ledger.exerciseAndGetResult(
        swapReadyCid,
        executeChoice,
        commandId
    ),
    () -> logger.info("Refreshing ACS for ExecuteSwap retry (stale pool CID)"),
    "ExecuteSwap"
);
```

**Logs exemple** :

```
2025-01-15 10:30:45.123 WARN  StaleAcsRetry - ⚠️  Stale ACS detected for ExecuteSwap - retrying once
2025-01-15 10:30:45.124 WARN  StaleAcsRetry - Error details: CONTRACT_NOT_FOUND: Pool contract 00abc123... not found
2025-01-15 10:30:45.125 INFO  LiquidityController - Refreshing ACS for ExecuteSwap retry (stale pool CID)
2025-01-15 10:30:45.456 INFO  StaleAcsRetry - ✅ Retry succeeded for ExecuteSwap
```

### 6.2 ValidationExceptionHandler - Global Exception Handling

**Fichier**: `controller/ValidationExceptionHandler.java`

**Catch validation errors globalement** :

```java
@RestControllerAdvice
public class ValidationExceptionHandler {

    /**
     * Handle @Valid validation errors (MethodArgumentNotValidException).
     * Returns 400 BAD_REQUEST with detailed field errors.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        
        ex.getBindingResult().getFieldErrors().forEach(error -> {
            errors.put(error.getField(), error.getDefaultMessage());
        });

        return Map.of(
            "status", "VALIDATION_ERROR",
            "errors", errors,
            "timestamp", Instant.now().toString()
        );
    }

    /**
     * Handle ResponseStatusException (custom HTTP errors).
     * Returns appropriate status code with error message.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        return ResponseEntity
            .status(ex.getStatusCode())
            .body(Map.of(
                "status", "ERROR",
                "message", ex.getReason() != null ? ex.getReason() : "Unknown error",
                "timestamp", Instant.now().toString()
            ));
    }

    /**
     * Handle generic exceptions (500 INTERNAL_SERVER_ERROR).
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleGenericException(Exception ex) {
        logger.error("Unhandled exception: {}", ex.getMessage(), ex);
        
        return Map.of(
            "status", "INTERNAL_ERROR",
            "message", "An unexpected error occurred",
            "timestamp", Instant.now().toString()
        );
    }
}
```

**Exemple de validation errors** :

```
POST /api/swap/atomic
{
  "poolId": "",                    ← INVALID: blank
  "inputSymbol": "ETH",
  "outputSymbol": "ETH",           ← INVALID: same as inputSymbol
  "inputAmount": "-10.0",          ← INVALID: negative
  "minOutput": "NaN",              ← INVALID: not a number
  "maxPriceImpactBps": 10000       ← INVALID: > 5000
}

↓

Response: 400 BAD_REQUEST
{
  "status": "VALIDATION_ERROR",
  "errors": {
    "poolId": "must not be blank",
    "outputSymbol": "must be different from inputSymbol",
    "inputAmount": "must be greater than or equal to 0.0000000001",
    "minOutput": "must be a valid decimal number",
    "maxPriceImpactBps": "must be less than or equal to 5000"
  },
  "timestamp": "2025-01-15T10:30:45.123Z"
}
```

---

## 7. MÉTRIQUES ET OBSERVABILITÉ

### 7.1 SwapMetrics - Prometheus Metrics

**Fichier**: `metrics/SwapMetrics.java`

**Métriques exposées** :

```java
@Component
public class SwapMetrics {

    // Counter: Total swaps prepared
    private final Counter swapPreparedCounter;
    
    // Counter: Total swaps executed
    private final Counter swapExecutedCounter;
    
    // Counter: Total swaps failed
    private final Counter swapFailedCounter;
    
    // Histogram: Swap execution time
    private final Histogram swapExecutionTime;
    
    // Gauge: Active pools count
    private final AtomicInteger activePoolsCount;
    
    // Counter: Protocol fees collected (by token)
    private final Counter protocolFeeCounter;
    
    // Counter: LP fees collected (by token)
    private final Counter lpFeeCounter;
    
    // Counter: Total volume traded (by pair)
    private final Counter volumeCounter;

    public void recordSwapPrepared(String inputSymbol, String outputSymbol) {
        swapPreparedCounter.labels(inputSymbol, outputSymbol).inc();
        logger.info("METRIC: swap_prepared_total{{pair=\"{}-{}\"}} +1", 
            inputSymbol, outputSymbol);
    }

    public void recordSwapExecuted(
        String inputSymbol,
        String outputSymbol,
        BigDecimal amountIn,
        BigDecimal amountOut,
        int priceImpactBps,
        long executionTimeMs
    ) {
        swapExecutedCounter.labels(inputSymbol, outputSymbol).inc();
        volumeCounter.labels(inputSymbol, outputSymbol).inc(amountOut.doubleValue());
        swapExecutionTime.observe(executionTimeMs / 1000.0);  // Convert to seconds
        
        logger.info("METRIC: swap_executed_total{{pair=\"{}-{}\"}} +1", 
            inputSymbol, outputSymbol);
        logger.info("METRIC: swap_volume_total{{pair=\"{}-{}\"}} +{}", 
            inputSymbol, outputSymbol, amountOut);
        logger.info("METRIC: swap_execution_time_seconds {}", executionTimeMs / 1000.0);
    }

    public void recordSwapFailed(String inputSymbol, String outputSymbol, String reason) {
        swapFailedCounter.labels(inputSymbol, outputSymbol, reason).inc();
        logger.info("METRIC: swap_failed_total{{pair=\"{}-{}\", reason=\"{}\"}} +1",
            inputSymbol, outputSymbol, reason);
    }

    public void recordProtocolFee(String symbol, BigDecimal feeAmount) {
        protocolFeeCounter.labels(symbol).inc(feeAmount.doubleValue());
        logger.info("METRIC: protocol_fee_collected_total{{token=\"{}\"}} +{}", 
            symbol, feeAmount);
    }

    public void recordLpFee(String symbol, BigDecimal feeAmount) {
        lpFeeCounter.labels(symbol).inc(feeAmount.doubleValue());
        logger.info("METRIC: lp_fee_collected_total{{token=\"{}\"}} +{}", 
            symbol, feeAmount);
    }

    public void setActivePoolsCount(int count) {
        activePoolsCount.set(count);
        logger.info("METRIC: active_pools_count {}", count);
    }
}
```

### 7.2 Grafana Dashboard Queries

**Prometheus queries utilisées par Grafana** :

```promql
# Total Swaps (all time)
sum(swap_executed_total)

# Swap Rate (per minute)
rate(swap_executed_total[5m]) * 60

# Total Volume USD (ETH-USDC pair)
sum(swap_volume_total{pair="ETH-USDC"})

# Protocol Fees Collected (ETH)
sum(protocol_fee_collected_total{token="ETH"})

# LP Fees Collected (ETH)
sum(lp_fee_collected_total{token="ETH"})

# Average Swap Execution Time
histogram_quantile(0.5, rate(swap_execution_time_seconds_bucket[5m]))

# Swap Failure Rate
rate(swap_failed_total[5m]) / rate(swap_executed_total[5m])

# Active Pools Count
active_pools_count

# Swaps by Pair (top 5)
topk(5, sum by (pair) (swap_executed_total))
```

**Panel Grafana exemple** :

```
┌────────────────────────────────────────────────────────────────┐
│ ClearportX DEX Metrics                                          │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│ Total Swaps (24h): 1,234                                       │
│ Total Volume USD: $5,432,100                                   │
│ Protocol Fees Collected: $4,074 (25% of 0.3%)                 │
│ LP Fees Collected: $12,222 (75% of 0.3%)                      │
│ Active Pools: 3                                                 │
│                                                                 │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ Swaps per Hour                                            │   │
│ │ ▁▂▃▅▇█▇▅▃▂▁                                              │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ Top Trading Pairs                                         │   │
│ │ 1. ETH-USDC: 850 swaps, $3.2M volume                     │   │
│ │ 2. ETH-USDT: 234 swaps, $1.5M volume                     │   │
│ │ 3. BTC-USDC: 150 swaps, $700K volume                     │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

---

## RÉSUMÉ MODULE 04

Ce module couvre l'**architecture backend REST API** de ClearportX :

1. **SwapController** (666 lignes) :
   - `/api/swap/atomic` : Swap atomique 1-step (production)
   - `/api/swap/prepare` : Two-step swap part 1 (tests)
   - `/api/swap/execute` : Two-step swap part 2 (tests)
   - Idempotency (prevent duplicate swaps)
   - Automatic retry on CONTRACT_NOT_FOUND

2. **LiquidityController** (250 lignes) :
   - `/api/liquidity/add` : Add liquidity (multi-party authorization)
   - Slippage protection (minLPTokens)
   - StaleAcsRetry pattern

3. **LedgerHealthService** (253 lignes) :
   - `/api/health/ledger` : Canton ↔ PQS sync diagnostics
   - Package allowlist detection
   - AtomicSwap availability check

4. **Sécurité** :
   - OAuth2 JWT authentication (Keycloak)
   - PartyGuard authorization (@PreAuthorize)
   - PartyMappingService (JWT → Canton party)

5. **Observabilité** :
   - SwapMetrics (Prometheus counters/histograms)
   - OpenTelemetry distributed tracing (@WithSpan)
   - Grafana dashboards

**Next Steps** : Module 05 (Backend Services - LedgerApi, IdempotencyService).

