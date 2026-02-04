# MODULE 05 - BACKEND SERVICES & LEDGER API

**Auteur**: Documentation technique ClearportX  
**Date**: 2025-10-21  
**Version**: 1.0.0  
**Prérequis**: Module 01 (Architecture), Module 04 (Controllers)

---

## TABLE DES MATIÈRES

1. [Vue d'ensemble des services](#1-vue-densemble-des-services)
2. [LedgerApi - DAML Ledger API Wrapper](#2-ledgerapi---daml-ledger-api-wrapper)
3. [IdempotencyService - Duplicate Prevention](#3-idempotencyservice---duplicate-prevention)
4. [LedgerReader - Authoritative Contract Reads](#4-ledgerreader---authoritative-contract-reads)
5. [PqsSyncUtil - PQS Synchronization](#5-pqssyncutil---pqs-synchronization)
6. [Patterns et best practices](#6-patterns-et-best-practices)

---

## 1. VUE D'ENSEMBLE DES SERVICES

### 1.1 Architecture des services

```
┌────────────────────────────────────────────────────────────────┐
│ BACKEND SERVICES ARCHITECTURE                                   │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│ Layer 1: Core Ledger Services                                  │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ LedgerApi.java (450+ lines)                              │   │
│ │ • create() - Create DAML contracts                       │   │
│ │ • exercise() - Execute choices                           │   │
│ │ • exerciseWithParties() - Multi-party authorization      │   │
│ │ • createAndGetCid() - Deterministic CID extraction       │   │
│ │ • getActiveContracts() - Query ACS                       │   │
│ │                                                           │   │
│ │ Canton Ledger API (gRPC):                                │   │
│ │ • CommandSubmissionService - Submit transactions         │   │
│ │ • CommandService - Submit + wait for result              │   │
│ │ • StateService - Query active contracts (ACS)            │   │
│ │ • TransactionService - Stream transaction updates        │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│ Layer 2: Business Logic Services                               │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ LedgerReader.java (102 lines)                            │   │
│ │ • tokensForParty() - Get user's token balances           │   │
│ │ • pools() - Get all active pools                         │   │
│ │ • Authoritative reads (no PQS lag)                       │   │
│ │                                                           │   │
│ │ IdempotencyService.java (158 lines)                      │   │
│ │ • checkIdempotency() - Detect duplicate requests         │   │
│ │ • registerSuccess() - Cache successful operations        │   │
│ │ • validateIdempotencyKey() - Format validation           │   │
│ │ • TTL: 15 minutes (SwapConstants.IDEMPOTENCY_CACHE_DURATION)│
│ │                                                           │   │
│ │ LedgerHealthService.java (253 lines) [See Module 04]     │   │
│ │ • getHealthStatus() - Canton ↔ PQS sync diagnostics      │   │
│ │ • isPackageIndexed() - Detect allowlist issues           │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│ Layer 3: Infrastructure Services                               │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ PqsSyncUtil.java                                         │   │
│ │ • waitForPqsSync() - Block until PQS catches up          │   │
│ │ • Used for test reliability                              │   │
│ │                                                           │   │
│ │ SwapMetrics.java [See Module 04]                         │   │
│ │ • recordSwapExecuted() - Prometheus counters             │   │
│ │ • recordProtocolFee() - Fee collection metrics           │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### 1.2 Responsabilités des services

| Service              | Responsabilité                              | Utilisé par              |
|----------------------|---------------------------------------------|--------------------------|
| **LedgerApi**        | Communication avec Canton Ledger API (gRPC) | Tous les controllers     |
| **LedgerReader**     | Query authoritative contracts (ACS)         | Frontend data fetching   |
| **IdempotencyService** | Prevent duplicate swaps (15 min cache)    | SwapController           |
| **LedgerHealthService** | PQS sync monitoring, diagnostics        | Health endpoints         |
| **SwapMetrics**      | Prometheus metrics collection               | SwapController, Grafana  |
| **PqsSyncUtil**      | Test synchronization with PQS               | Integration tests        |

---

## 2. LEDGERAPI - DAML LEDGER API WRAPPER

### 2.1 Responsabilité et architecture

**Fichier**: `ledger/LedgerApi.java` (450+ lignes)

**Rôle** :
- Wrapper autour du Canton Ledger API (gRPC)
- Conversion Java ↔ Protobuf (DAML values)
- Multi-party authorization support
- Transaction tree parsing (extract CIDs)
- OpenTelemetry tracing integration

**Architecture gRPC** :

```
┌────────────────────────────────────────────────────────────────┐
│ CANTON LEDGER API (gRPC)                                        │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│ CommandSubmissionService (Fire-and-forget)                     │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ submitCommands()                                          │   │
│ │ → Submit transaction to Canton                            │   │
│ │ → Returns immediately (no wait for consensus)             │   │
│ │ → Use for async operations                                │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│ CommandService (Submit-and-wait)                               │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ submitAndWaitForTransactionTree()                         │   │
│ │ → Submit + wait for consensus + parse result              │   │
│ │ → Returns TransactionTree (with created contract CIDs)    │   │
│ │ → Use for sync operations (REST API)                      │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│ StateService (Query ACS)                                       │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ getActiveContracts()                                      │   │
│ │ → Stream all active contracts for template                │   │
│ │ → Filtered by stakeholder (party authorization)           │   │
│ │ → Returns: List<ActiveContract<T>>                        │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│ TransactionService (Stream updates)                            │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ getTransactions()                                         │   │
│ │ → Stream transaction updates (WebSocket-like)             │   │
│ │ → Real-time contract creations/archivals                  │   │
│ │ → Not used in ClearportX (polling ACS instead)            │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### 2.2 Méthode create() - Create Contract

**Signature** (lines 84-100):

```java
@WithSpan  // OpenTelemetry tracing
public <T extends Template> CompletableFuture<Void> create(
    T entity,        // DAML template instance (e.g., new Pool(...))
    String commandId // Unique UUID for idempotency
)
```

**Flow complet** :

```
create(new Pool(...), commandId)
↓
ÉTAPE 1: Convert Java → Protobuf (lines 96-97)
  ValueOuterClass.Value payload = dto2Proto
    .template(entity.templateId())
    .convert(entity);
  
  ← Convert DAML template to Protobuf Value
  ← Example: Pool{reserveA=1000, ...} → Value{record={fields=[...]}}

ÉTAPE 2: Build CreateCommand (line 97)
  CommandsOuterClass.Command command = Command.newBuilder()
    .getCreateBuilder()
    .setTemplateId(toIdentifier(entity.templateId()))
    .setCreateArguments(payload.getRecord())
    .build();
  
  ← CreateCommand = {templateId, createArguments}

ÉTAPE 3: Submit to Canton (line 98)
  submitCommands(List.of(command), commandId)
    .thenApply(submitResponse -> null);
  
  ← Fire-and-forget submission (no result parsing)
  ← Returns: CompletableFuture<Void>
```

**Usage exemple** :

```java
// Create a new Pool contract
Pool poolTemplate = new Pool(
    new Party(poolParty),
    new Party(operator),
    new Party(issuerA),
    new Party(issuerB),
    "ETH",
    "USDC",
    30L,  // 0.3% fee
    new BigDecimal("1000"),   // reserveA
    new BigDecimal("2000000"), // reserveB
    // ... other fields
);

String commandId = UUID.randomUUID().toString();
CompletableFuture<Void> result = ledgerApi.create(poolTemplate, commandId);

result.join();  // Wait for completion
logger.info("Pool created successfully!");
```

### 2.3 Méthode exerciseAndGetResult() - Execute Choice

**Signature** (lines 103-110):

```java
@WithSpan
public <T extends Template, Result, C extends Choice<T, Result>>
CompletableFuture<Result> exerciseAndGetResult(
    ContractId<T> contractId,  // Contract to exercise choice on
    C choice,                  // Choice instance (e.g., new Pool.AtomicSwap(...))
    String commandId           // Unique UUID
)
```

**Flow complet** :

```
exerciseAndGetResult(swapReadyCid, new SwapReady.ExecuteSwap(), commandId)
↓
ÉTAPE 1: Convert Choice Argument → Protobuf (lines 160-161)
  ValueOuterClass.Value payload = dto2Proto
    .choiceArgument(choice.templateId(), choice.choiceName())
    .convert(choice);
  
  ← Convert choice arguments to Protobuf
  ← Example: ExecuteSwap{} → Value{record={fields=[]}} (no args)

ÉTAPE 2: Build ExerciseCommand (lines 163-167)
  CommandsOuterClass.Command command = Command.newBuilder()
    .getExerciseBuilder()
    .setTemplateId(toIdentifier(choice.templateId()))
    .setContractId(contractId.getContractId)
    .setChoice(choice.choiceName())
    .setChoiceArgument(payload)
    .build();
  
  ← ExerciseCommand = {templateId, contractId, choice, choiceArgument}

ÉTAPE 3: Submit + Wait for TransactionTree (line 191)
  commands.submitAndWaitForTransactionTree(request)
    .thenApply(response -> {
      TransactionTree txTree = response.getTransaction();
      // ... parse result from transaction tree
    });
  
  ← Block until consensus reached
  ← Returns: TransactionTree (created contracts + choice result)

ÉTAPE 4: Parse Result from TransactionTree (lines 193-230)
  TransactionTree txTree = response.getTransaction();
  Map<Integer, TreeEvent> eventsById = txTree.getEventsByIdMap();
  
  // Find root event (lowest eventId)
  Integer rootEventId = Collections.min(eventsById.keySet());
  TreeEvent rootEvent = eventsById.get(rootEventId);
  
  if (rootEvent.hasExercised()) {
    ExercisedEvent exercisedEvent = rootEvent.getExercised();
    Value exerciseResult = exercisedEvent.getExerciseResult();
    
    // Convert Protobuf → Java
    Result result = proto2Dto
      .choiceResult(choice.templateId(), choice.choiceName())
      .convert(exerciseResult);
    
    return result;  // ContractId<Receipt>, Tuple2, etc.
  }
```

**Usage exemple** :

```java
// Execute ExecuteSwap choice
ContractId<SwapReady> swapReadyCid = new ContractId<>("00abc123...");
SwapReady.ExecuteSwap choice = new SwapReady.ExecuteSwap();

CompletableFuture<ContractId<Receipt>> result = ledgerApi.exerciseAndGetResult(
    swapReadyCid,
    choice,
    UUID.randomUUID().toString()
);

ContractId<Receipt> receiptCid = result.join();
logger.info("Swap executed! Receipt CID: {}", receiptCid.getContractId);
```

### 2.4 Méthode exerciseAndGetResultWithParties() - Multi-Party Authorization

**Signature** (lines 124-134):

```java
@WithSpan
public <T extends Template, Result, C extends Choice<T, Result>>
CompletableFuture<Result> exerciseAndGetResultWithParties(
    ContractId<T> contractId,
    C choice,
    String commandId,
    List<String> actAsParties,   // ← Multi-party authorization!
    List<String> readAsParties,  // ← Visibility for observers
    List<CommandsOuterClass.DisclosedContract> disclosedContracts  // ← Privacy
)
```

**Pourquoi multi-party ?** (lines 112-122 comments):

```java
/**
 * Exercise a choice with multiple actAs parties (for multi-party workflows)
 * This is essential for AddLiquidity (liquidityProvider + poolParty + lpIssuer)
 * and other multi-party operations.
 *
 * DAML controllers vs actAs parties:
 * - controller in DAML: Who CAN execute the choice (defined in template)
 * - actAs in Ledger API: Who IS executing (must match controllers)
 * 
 * Example: AddLiquidity choice
 *   choice AddLiquidity : ...
 *     controller liquidityProvider, poolParty, lpIssuer
 *   
 *   Ledger API call MUST include:
 *   actAs = [liquidityProvider, poolParty, lpIssuer]
 */
```

**Flow avec multi-party** (lines 169-189):

```java
// Build Commands with multi-party actAs and readAs
CommandsOuterClass.Commands.Builder commandsBuilder = 
    CommandsOuterClass.Commands.newBuilder()
        .setCommandId(commandId)
        .addAllActAs(actAsParties)      // ← ALL controllers must authorize!
        .addAllReadAs(readAsParties)    // ← Observers for visibility
        .addCommands(cmdBuilder.build());

if (disclosedContracts != null && !disclosedContracts.isEmpty()) {
    commandsBuilder.addAllDisclosedContracts(disclosedContracts);
}

CommandServiceOuterClass.SubmitAndWaitRequest request =
    CommandServiceOuterClass.SubmitAndWaitRequest.newBuilder()
        .setCommands(commandsBuilder.build())
        .build();

logger.info("Submitting multi-party ledger command: actAs={}, readAs={}", 
    actAsParties, readAsParties);

return toCompletableFuture(commands.submitAndWaitForTransactionTree(request))
    .thenApply(response -> { /* parse result */ });
```

**Usage exemple - AddLiquidity** :

```java
// AddLiquidity requires 3 parties to authorize
Pool.AddLiquidity choice = new Pool.AddLiquidity(
    new Party(liquidityProvider),
    tokenACid,
    tokenBCid,
    amountA,
    amountB,
    minLPTokens,
    deadline
);

CompletableFuture<Tuple2<ContractId<LPToken>, ContractId<Pool>>> result = 
    ledgerApi.exerciseAndGetResultWithParties(
        poolCid,
        choice,
        commandId,
        List.of(liquidityProvider, poolParty, lpIssuer),  // actAs
        List.of(poolParty),  // readAs
        List.of()  // disclosed contracts
    );

// If ANY party missing from actAs → ERROR:
// "Interpretation error: Missing authorization for party X"
```

### 2.5 Méthode createAndGetCid() - Deterministic CID Extraction

**Problème avec create() classique** :

```
OLD APPROACH (race condition):
1. ledgerApi.create(swapRequest, commandId)
2. Wait for ACS sync (PQS indexing lag)
3. ledgerApi.getActiveContracts(SwapRequest.class)
4. Find swapRequest by filter (commandId or trader)
5. Extract swapRequestCid

PROBLEMS:
❌ ACS lag = CID not found immediately (PQS indexing delay)
❌ Race condition if multiple requests concurrent
❌ Filter match ambiguity (which contract is ours?)
```

**Solution createAndGetCid()** (lines 300-350):

```java
@WithSpan
public <T extends Template> CompletableFuture<ContractId<T>> createAndGetCid(
    T entity,
    List<String> actAsParties,
    List<String> readAsParties,
    String commandId,
    Identifier templateId  // Package ID + module + entity
) {
    // Build CreateCommand
    CommandsOuterClass.Command.Builder command = CommandsOuterClass.Command.newBuilder();
    ValueOuterClass.Value payload = dto2Proto.template(entity.templateId()).convert(entity);
    command.getCreateBuilder()
        .setTemplateId(toIdentifier(templateId))
        .setCreateArguments(payload.getRecord());

    // Submit + wait for TransactionTree
    return submitAndWaitForTree(command.build(), actAsParties, readAsParties, commandId)
        .thenApply(txTree -> {
            // Extract CID from transaction tree (IMMEDIATE, no query!)
            Map<Integer, TransactionOuterClass.TreeEvent> eventsById = txTree.getEventsByIdMap();
            Integer rootEventId = Collections.min(eventsById.keySet());
            TransactionOuterClass.TreeEvent rootEvent = eventsById.get(rootEventId);
            
            if (rootEvent.hasCreated()) {
                CreatedEvent createdEvent = rootEvent.getCreated();
                String contractId = createdEvent.getContractId();
                
                logger.info("✅ Contract created with CID extracted from transaction tree: {}", 
                    contractId);
                
                return new ContractId<T>(contractId);
            }
            
            throw new IllegalStateException("No created event in transaction tree");
        });
}
```

**Avantages** :

```
NEW APPROACH (deterministic, race-free):
1. ledgerApi.createAndGetCid(swapRequest, actAs, readAs, commandId, templateId)
2. Submit to Canton → Wait for consensus
3. Parse TransactionTree → Extract CID from CreatedEvent
4. Return swapRequestCid (IMMEDIATE, no ACS query!)

BENEFITS:
✅ No ACS lag (CID from transaction tree, not query)
✅ No race condition (deterministic CID extraction)
✅ No filter ambiguity (exact CID from created event)
✅ Faster (no additional query roundtrip)
```

**Usage dans SwapController** (lines 186-196):

```java
// Create SwapRequest with deterministic CID extraction
return ledger.createAndGetCid(
    swapRequest,
    List.of(trader),        // actAs: trader creates the SwapRequest
    List.of(poolParty),     // readAs: poolParty can see it
    commandId + "-create",
    swapRequest.templateId() // Use instance template ID (has correct package ID)
)
.thenCompose(swapRequestCid -> {
    logger.info("✅ SwapRequest created with CID: {} (via transaction tree)", 
        swapRequestCid.getContractId);
    
    // Now exercise PrepareSwap choice on the swapRequestCid
    return ledger.exerciseAndGetResult(swapRequestCid, prepareChoice, commandId + "-prepare");
});
```

### 2.6 Méthode getActiveContracts() - Query ACS

**Signature** :

```java
@WithSpan
public <T extends Template> CompletableFuture<List<ActiveContract<T>>> getActiveContracts(
    Class<T> templateClass  // e.g., Pool.class, Token.class
)
```

**Flow complet** :

```
getActiveContracts(Pool.class)
↓
ÉTAPE 1: Get Template Identifier (package ID + module + entity)
  Identifier templateId = getTemplateIdForClass(Pool.class);
  // → Identifier{packageId="abc123...", moduleName="AMM.Pool", entityName="Pool"}

ÉTAPE 2: Build GetActiveContractsRequest
  StateServiceOuterClass.GetActiveContractsRequest request = 
    GetActiveContractsRequest.newBuilder()
      .setFilter(TransactionFilter.newBuilder()
        .putFiltersByParty(appProviderParty, Filters.newBuilder()
          .addInclusiveFilters(InclusiveFilters.newBuilder()
            .addTemplateFilters(TemplateFilter.newBuilder()
              .setTemplateId(toIdentifier(templateId))
              .build())
            .build())
          .build())
        .build())
      .build();
  
  ← Filter by template + stakeholder party

ÉTAPE 3: Stream Active Contracts (gRPC streaming)
  List<ActiveContract<Pool>> contracts = new ArrayList<>();
  
  stateService.getActiveContracts(request, new StreamObserver<>() {
    @Override
    public void onNext(GetActiveContractsResponse response) {
      for (CreatedEvent createdEvent : response.getCreatedEventsList()) {
        // Convert Protobuf → Java
        Pool poolPayload = proto2Dto.template(templateId)
          .convert(createdEvent.getCreateArguments());
        
        contracts.add(new ActiveContract<>(
          new ContractId<>(createdEvent.getContractId()),
          poolPayload
        ));
      }
    }
    
    @Override
    public void onCompleted() {
      logger.info("Fetched {} active contracts for {}", 
        contracts.size(), templateClass.getSimpleName());
    }
  });
  
  ← Returns: List<ActiveContract<Pool>>

ÉTAPE 4: Return CompletableFuture
  return CompletableFuture.completedFuture(contracts);
```

**ActiveContract structure** :

```java
public static class ActiveContract<T extends Template> {
    public final ContractId<T> contractId;  // e.g., "00abc123..."
    public final T payload;                 // DAML template fields
    
    public ActiveContract(ContractId<T> contractId, T payload) {
        this.contractId = contractId;
        this.payload = payload;
    }
}
```

**Usage exemple** :

```java
// Get all active pools
CompletableFuture<List<LedgerApi.ActiveContract<Pool>>> poolsFuture = 
    ledgerApi.getActiveContracts(Pool.class);

List<LedgerApi.ActiveContract<Pool>> pools = poolsFuture.join();

// Filter by poolId
Optional<LedgerApi.ActiveContract<Pool>> maybePool = pools.stream()
    .filter(p -> p.payload.getPoolId.equals("ETH-USDC-pool-0.3%"))
    .filter(p -> p.payload.getReserveA.compareTo(BigDecimal.ZERO) > 0)
    .filter(p -> p.payload.getReserveB.compareTo(BigDecimal.ZERO) > 0)
    .findFirst();

if (maybePool.isEmpty()) {
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, 
        "Pool not found or has no liquidity");
}

LedgerApi.ActiveContract<Pool> pool = maybePool.get();
logger.info("Found pool: CID={}, reserveA={}, reserveB={}", 
    pool.contractId.getContractId, 
    pool.payload.getReserveA, 
    pool.payload.getReserveB);
```

---

## 3. IDEMPOTENCYSERVICE - DUPLICATE PREVENTION

### 3.1 Problème : Duplicate Swaps

**Scénario sans idempotency** :

```
Frontend sends POST /api/swap/atomic (network glitch, user clicks twice)
↓
Request 1: Create AtomicSwapProposal → ExecuteAtomicSwap → Receipt 1
Request 2: Create AtomicSwapProposal → ExecuteAtomicSwap → Receipt 2

RESULT: User swapped TWICE! (lost money on second swap)
```

**Solution : Idempotency Keys** :

```
Frontend generates unique key: X-Idempotency-Key: swap-12345-abc
↓
Request 1: 
  POST /api/swap/atomic
  X-Idempotency-Key: swap-12345-abc
  → Execute swap → Cache response (15 min TTL)

Request 2 (duplicate):
  POST /api/swap/atomic
  X-Idempotency-Key: swap-12345-abc
  → Check cache → Return cached response (NO new swap!)

RESULT: User swapped ONCE ✓
```

### 3.2 Implementation

**Fichier**: `service/IdempotencyService.java` (158 lignes)

**Cache structure** (lines 29-50):

```java
/**
 * Cache entry for idempotency tracking.
 */
private static class IdempotencyEntry {
    final String commandId;          // Canton command ID (UUID)
    final String transactionId;      // Canton transaction ID (if available)
    final Instant expiresAt;         // TTL expiration time
    final Object response;           // Cached response object

    IdempotencyEntry(String commandId, String transactionId, Object response) {
        this.commandId = commandId;
        this.transactionId = transactionId;
        this.response = response;
        this.expiresAt = Instant.now().plusSeconds(
            SwapConstants.IDEMPOTENCY_CACHE_DURATION_SECONDS  // 900 seconds (15 min)
        );
    }

    boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}

// In-memory cache: idempotencyKey → entry
private final Map<String, IdempotencyEntry> cache = new ConcurrentHashMap<>();
```

**Pourquoi ConcurrentHashMap ?**
- Thread-safe (multiple concurrent requests)
- No blocking (high performance)
- Safe for production (100+ RPS)

### 3.3 Méthode checkIdempotency() - Detect Duplicates

**Flow** (lines 58-77):

```java
public Object checkIdempotency(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
        return null;  // No idempotency key provided (idempotency optional)
    }

    IdempotencyEntry entry = cache.get(idempotencyKey);

    if (entry == null) {
        return null;  // First request with this key
    }

    if (entry.isExpired()) {
        cache.remove(idempotencyKey);
        logger.debug("Idempotency key expired and removed: {}", idempotencyKey);
        return null;  // Expired entry = allow new request
    }

    logger.info("Idempotent request detected - returning cached response for key: {}", 
        idempotencyKey);
    return entry.response;  // Return cached response (duplicate detected!)
}
```

**Usage dans SwapController** (lines 439-446):

```java
// Check idempotency before executing swap
if (idempotencyKey != null) {
    idempotencyService.validateIdempotencyKey(idempotencyKey);
    Object cachedResponse = idempotencyService.checkIdempotency(idempotencyKey);
    if (cachedResponse != null) {
        logger.info("Returning cached response for idempotency key: {}", idempotencyKey);
        return CompletableFuture.completedFuture((AtomicSwapResponse) cachedResponse);
        // ← Early return! No duplicate swap executed
    }
}
```

### 3.4 Méthode registerSuccess() - Cache Response

**Flow** (lines 87-97):

```java
public void registerSuccess(
    String idempotencyKey, 
    String commandId, 
    String transactionId, 
    Object response
) {
    if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
        return;  // No idempotency tracking without key
    }

    IdempotencyEntry entry = new IdempotencyEntry(commandId, transactionId, response);
    cache.put(idempotencyKey, entry);

    logger.info("Registered idempotency key: {} → commandId: {}, txId: {}",
        idempotencyKey, commandId, transactionId != null ? transactionId : "N/A");
}
```

**Usage dans SwapController** (lines 632-639):

```java
// After successful swap, cache the response
if (idempotencyKey != null) {
    idempotencyService.registerSuccess(
        idempotencyKey,                // "swap-12345-abc"
        commandId,                     // UUID
        receiptCid.getContractId,      // "00abc123..." (Canton transaction ID)
        response                       // Full AtomicSwapResponse object
    );
}
```

### 3.5 Méthode validateIdempotencyKey() - Format Validation

**Règles de validation** (lines 105-125):

```java
public void validateIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null) {
        return;  // Optional header
    }

    // Rule 1: Not empty
    if (idempotencyKey.trim().isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Idempotency key cannot be empty");
    }

    // Rule 2: Max length 255 characters
    if (idempotencyKey.length() > 255) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Idempotency key too long (max 255 characters)");
    }

    // Rule 3: Only alphanumeric + hyphens + underscores
    if (!idempotencyKey.matches("^[a-zA-Z0-9\\-_]+$")) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Idempotency key contains invalid characters (only alphanumeric, -, _ allowed)");
    }
}
```

**Valid keys** :
- ✅ `swap-12345-abc`
- ✅ `user_alice_swap_20250115_103045`
- ✅ `a1b2c3d4-e5f6-7890-abcd-ef1234567890` (UUID)

**Invalid keys** :
- ❌ `` (empty)
- ❌ `swap#12345` (invalid character #)
- ❌ `swap 12345` (space not allowed)
- ❌ `key-with-émoji-😀` (non-ASCII)

### 3.6 Méthode cleanupExpired() - Background Cleanup

**Flow** (lines 130-142):

```java
public void cleanupExpired() {
    int removed = 0;
    for (Map.Entry<String, IdempotencyEntry> entry : cache.entrySet()) {
        if (entry.getValue().isExpired()) {
            cache.remove(entry.getKey());
            removed++;
        }
    }

    if (removed > 0) {
        logger.info("Cleaned up {} expired idempotency entries", removed);
    }
}
```

**Scheduled execution** (Spring Boot @Scheduled):

```java
// In application configuration
@Configuration
public class SchedulingConfig {
    
    @Autowired
    private IdempotencyService idempotencyService;
    
    // Run cleanup every 5 minutes
    @Scheduled(fixedRate = 300000)  // 5 minutes in milliseconds
    public void cleanupIdempotencyCache() {
        idempotencyService.cleanupExpired();
    }
}
```

### 3.7 Production Considerations

**Current implementation : In-Memory Cache**

```
PROS:
✅ Fast (no network latency)
✅ Simple (no external dependencies)
✅ Works for single-instance deployment

CONS:
❌ Not distributed (multi-instance = separate caches)
❌ Lost on restart (cache cleared)
❌ Memory pressure (10,000 keys ≈ 10MB RAM)
```

**Production recommendation : Redis**

```java
// Replace ConcurrentHashMap with Redis
@Service
public class IdempotencyService {
    
    @Autowired
    private RedisTemplate<String, IdempotencyEntry> redisTemplate;
    
    public Object checkIdempotency(String idempotencyKey) {
        ValueOperations<String, IdempotencyEntry> ops = redisTemplate.opsForValue();
        IdempotencyEntry entry = ops.get(idempotencyKey);
        
        if (entry == null) {
            return null;  // First request
        }
        
        logger.info("Idempotent request detected (Redis): {}", idempotencyKey);
        return entry.response;
    }
    
    public void registerSuccess(
        String idempotencyKey, 
        String commandId, 
        String transactionId, 
        Object response
    ) {
        IdempotencyEntry entry = new IdempotencyEntry(commandId, transactionId, response);
        
        // Set with TTL (auto-expires after 15 minutes)
        redisTemplate.opsForValue().set(
            idempotencyKey, 
            entry, 
            SwapConstants.IDEMPOTENCY_CACHE_DURATION_SECONDS, 
            TimeUnit.SECONDS
        );
    }
}
```

**Redis benefits** :
- ✅ Distributed (shared cache across multiple backend instances)
- ✅ Persistent (survives restarts)
- ✅ Auto-expiration (built-in TTL, no manual cleanup)
- ✅ Scalable (millions of keys)

---

## 4. LEDGERREADER - AUTHORITATIVE CONTRACT READS

### 4.1 Problème : PQS Lag vs Ledger API

**PQS (Participant Query Service)** :

```
PROS:
✅ SQL queries (complex filters, joins, aggregations)
✅ Indexed (fast for large datasets)
✅ Analytics-friendly (TVL, volume, fees)

CONS:
❌ Lag (1-5 seconds behind Canton Ledger)
❌ Package allowlist issues (contract types must be registered)
❌ Index corruption (rare but possible)
```

**Canton Ledger API (ACS)** :

```
PROS:
✅ Authoritative (source of truth)
✅ No lag (immediate consistency)
✅ No allowlist issues (all contract types visible)

CONS:
❌ No SQL (simple filters only)
❌ Not indexed (slow for complex queries)
❌ gRPC streaming (more complex than SQL)
```

**ClearportX Strategy** :

```
USE LEDGER API FOR:
• User token balances (tokensForParty)
• Active pools list (pools)
• Contract validation before swap
• Critical reads (authentication, authorization)

USE PQS FOR:
• Analytics dashboards (Grafana)
• Historical data (archived contracts)
• TVL calculation (sum reserves)
• Fee metrics (aggregate protocol fees)
```

### 4.2 Implementation

**Fichier**: `service/LedgerReader.java` (102 lignes)

**Méthode tokensForParty()** (lines 43-65):

```java
@WithSpan
public CompletableFuture<List<TokenDTO>> tokensForParty(String party) {
    logger.info("Fetching tokens for party: {}", party);
    return ledger.getActiveContracts(Token.class)
        .thenApply(contracts -> contracts.stream()
            .map(c -> c.payload)  // Extract Token payload
            .filter(t -> t.getOwner.getParty.equals(party))  // Filter by owner
            .map(t -> new TokenDTO(
                t.getSymbol,              // "ETH"
                t.getSymbol + " Token",   // "ETH Token" (frontend expects name)
                10,                       // decimals (standard for demo tokens)
                t.getAmount.toPlainString(),  // "1000.0"
                t.getOwner.getParty       // "Alice::1220..."
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
```

**TokenDTO structure** :

```java
public class TokenDTO {
    private String symbol;      // "ETH"
    private String name;        // "ETH Token"
    private int decimals;       // 10
    private String balance;     // "1000.0" (String for precision)
    private String owner;       // "Alice::1220..."
}
```

**Usage dans frontend** :

```typescript
// Frontend fetches user's token balances
async function fetchUserTokens(jwt: string): Promise<TokenDTO[]> {
  const response = await fetch('/api/tokens/me', {
    headers: {
      'Authorization': `Bearer ${jwt}`
    }
  });
  
  const tokens = await response.json();
  // tokens = [
  //   { symbol: "ETH", balance: "1000.0", decimals: 10, ... },
  //   { symbol: "USDC", balance: "5000.0", decimals: 10, ... }
  // ]
  
  return tokens;
}
```

### 4.3 Méthode pools() - Get All Active Pools

**Flow** (lines 71-93):

```java
@WithSpan
public CompletableFuture<List<PoolDTO>> pools() {
    logger.info("Fetching all active pools");
    return ledger.getActiveContracts(Pool.class)
        .thenApply(contracts -> contracts.stream()
            .map(c -> c.payload)  // Extract Pool payload
            .map(p -> new PoolDTO(
                p.getSymbolA,                          // "ETH"
                p.getSymbolB,                          // "USDC"
                p.getReserveA.toPlainString(),         // "1000.0"
                p.getReserveB.toPlainString(),         // "2000000.0"
                p.getTotalLPSupply.toPlainString(),    // "44721.35"
                convertFeeBpsToRate(p.getFeeBps)       // "0.003" (0.3%)
            ))
            .toList())
        .whenComplete((result, ex) -> {
            if (ex != null) {
                logger.error("Failed to fetch pools: {}", ex.getMessage());
            } else {
                logger.info("Fetched {} active pools", result.size());
            }
        });
}

private String convertFeeBpsToRate(Long feeBps) {
    return String.valueOf(feeBps / 10000.0);  // 30 → "0.003"
}
```

**PoolDTO structure** :

```java
public class PoolDTO {
    private String symbolA;        // "ETH"
    private String symbolB;        // "USDC"
    private String reserveA;       // "1000.0"
    private String reserveB;       // "2000000.0"
    private String totalLPSupply;  // "44721.35"
    private String feeRate;        // "0.003" (0.3%)
}
```

**Usage dans frontend** :

```typescript
// Frontend fetches all pools for pool selection
async function fetchPools(): Promise<PoolDTO[]> {
  const response = await fetch('/api/pools');
  const pools = await response.json();
  
  // pools = [
  //   { symbolA: "ETH", symbolB: "USDC", reserveA: "1000.0", reserveB: "2000000.0", ... },
  //   { symbolA: "ETH", symbolB: "USDT", reserveA: "500.0", reserveB: "1000000.0", ... }
  // ]
  
  return pools;
}
```

---

## 5. PQSSYNCUTIL - PQS SYNCHRONIZATION

### 5.1 Problème : Test Flakiness

**Scénario sans sync** :

```
TEST: Create pool → Query pool → Verify reserves
↓
1. Create Pool (Canton Ledger) → Pool created at offset 12345
2. Query Pool (PQS) → PQS at offset 12340 (LAG!) → Pool not found ❌
3. Test FAILS (flaky test)

PROBLEM: PQS indexing lag causes test failures
```

**Solution : waitForPqsSync()** :

```
TEST: Create pool → WAIT for PQS sync → Query pool → Verify reserves
↓
1. Create Pool (Canton Ledger) → Pool created at offset 12345
2. WAIT for PQS to reach offset 12345 (poll every 100ms, max 10s)
3. Query Pool (PQS) → PQS at offset 12345 → Pool found ✓
4. Test PASSES (reliable test)
```

### 5.2 Implementation

**Fichier**: `utility/PqsSyncUtil.java`

**Méthode waitForPqsSync()** :

```java
public class PqsSyncUtil {
    
    private final JdbcTemplate jdbcTemplate;
    private static final Logger logger = LoggerFactory.getLogger(PqsSyncUtil.class);
    
    /**
     * Wait for PQS to sync up to a minimum offset.
     * 
     * @param minOffset Minimum PQS offset to wait for
     * @param timeoutMs Timeout in milliseconds (default 10000)
     * @throws TimeoutException if PQS doesn't sync within timeout
     */
    public void waitForPqsSync(long minOffset, long timeoutMs) throws TimeoutException {
        long startTime = System.currentTimeMillis();
        long currentOffset = 0;
        
        while (currentOffset < minOffset) {
            // Check timeout
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new TimeoutException(
                    "PQS did not sync to offset " + minOffset + 
                    " within " + timeoutMs + "ms (current: " + currentOffset + ")"
                );
            }
            
            // Query PQS current offset
            String query = "SELECT COALESCE(MAX(pk), 0) FROM __events";
            currentOffset = jdbcTemplate.queryForObject(query, Long.class);
            
            logger.debug("PQS sync check: current={}, target={}", currentOffset, minOffset);
            
            if (currentOffset >= minOffset) {
                logger.info("✅ PQS synced to offset {} (waited {}ms)", 
                    currentOffset, System.currentTimeMillis() - startTime);
                return;
            }
            
            // Wait 100ms before next poll
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("PQS sync wait interrupted", e);
            }
        }
    }
    
    /**
     * Wait for PQS to sync (default 10 second timeout)
     */
    public void waitForPqsSync(long minOffset) throws TimeoutException {
        waitForPqsSync(minOffset, 10000);  // 10 seconds default
    }
}
```

**Usage dans tests** :

```java
@SpringBootTest
class SwapIntegrationTest {
    
    @Autowired
    private LedgerApi ledgerApi;
    
    @Autowired
    private PqsSyncUtil pqsSyncUtil;
    
    @Test
    void testAtomicSwap() throws Exception {
        // Create pool on Canton Ledger
        CompletableFuture<Void> createFuture = ledgerApi.create(poolTemplate, commandId);
        createFuture.join();
        
        // Get Canton offset from transaction
        long cantonOffset = getCantonOffset();  // e.g., 12345
        
        // WAIT for PQS to sync
        pqsSyncUtil.waitForPqsSync(cantonOffset);
        
        // Now query PQS (guaranteed to have pool!)
        List<PoolDTO> pools = ledgerReader.pools().join();
        assertThat(pools).hasSize(1);
        assertThat(pools.get(0).getSymbolA()).isEqualTo("ETH");
    }
}
```

---

## 6. PATTERNS ET BEST PRACTICES

### 6.1 Pattern : Deterministic CID Extraction

```java
// ❌ BAD: Query-based CID extraction (race condition)
ledgerApi.create(swapRequest, commandId).join();
Thread.sleep(1000);  // Hope PQS catches up...
List<SwapRequest> swapRequests = ledgerApi.getActiveContracts(SwapRequest.class).join();
SwapRequest mySwapRequest = swapRequests.stream()
    .filter(sr -> sr.getTrader.equals(trader))  // Which one is mine?
    .findFirst()
    .get();

// ✅ GOOD: Transaction tree CID extraction (deterministic)
CompletableFuture<ContractId<SwapRequest>> swapRequestCidFuture = 
    ledgerApi.createAndGetCid(
        swapRequest,
        List.of(trader),
        List.of(poolParty),
        commandId,
        swapRequest.templateId()
    );

ContractId<SwapRequest> swapRequestCid = swapRequestCidFuture.join();
// ← CID immediate, no query, no race condition!
```

### 6.2 Pattern : Multi-Party Authorization

```java
// ❌ BAD: Single actAs for multi-party choice
ledgerApi.exerciseAndGetResult(
    poolCid,
    addLiquidityChoice,
    commandId
);
// ERROR: "Missing authorization for party poolParty"

// ✅ GOOD: All controllers in actAs
ledgerApi.exerciseAndGetResultWithParties(
    poolCid,
    addLiquidityChoice,
    commandId,
    List.of(liquidityProvider, poolParty, lpIssuer),  // ALL controllers
    List.of(poolParty),
    List.of()
);
// ← Success! All required parties authorize
```

### 6.3 Pattern : Idempotency Keys

```java
// ❌ BAD: No idempotency (duplicate swaps possible)
@PostMapping("/swap/atomic")
public CompletableFuture<AtomicSwapResponse> atomicSwap(@RequestBody SwapRequest req) {
    return executeSwap(req);  // No duplicate detection!
}

// ✅ GOOD: Idempotency header + caching
@PostMapping("/swap/atomic")
public CompletableFuture<AtomicSwapResponse> atomicSwap(
    @RequestBody SwapRequest req,
    @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey
) {
    // Check cache
    if (idempotencyKey != null) {
        Object cached = idempotencyService.checkIdempotency(idempotencyKey);
        if (cached != null) {
            return CompletableFuture.completedFuture((AtomicSwapResponse) cached);
        }
    }
    
    // Execute swap
    return executeSwap(req).thenApply(response -> {
        // Cache response
        if (idempotencyKey != null) {
            idempotencyService.registerSuccess(idempotencyKey, commandId, txId, response);
        }
        return response;
    });
}
```

### 6.4 Pattern : Ledger API vs PQS

```java
// ❌ BAD: PQS for critical reads (lag issues)
@PostMapping("/swap/atomic")
public CompletableFuture<AtomicSwapResponse> atomicSwap(@RequestBody SwapRequest req) {
    // Query PQS for pool
    Pool pool = pqs.queryPool(req.getPoolId());  // May be stale!
    // Execute swap with stale pool CID → CONTRACT_NOT_FOUND
}

// ✅ GOOD: Ledger API for critical reads (authoritative)
@PostMapping("/swap/atomic")
public CompletableFuture<AtomicSwapResponse> atomicSwap(@RequestBody SwapRequest req) {
    // Query Canton Ledger API (ACS)
    return ledgerApi.getActiveContracts(Pool.class)
        .thenCompose(pools -> {
            Pool pool = pools.stream()
                .filter(p -> p.getPoolId.equals(req.getPoolId()))
                .findFirst()
                .orElseThrow();
            // Execute swap with fresh pool CID → Success!
        });
}
```

---

## RÉSUMÉ MODULE 05

Ce module couvre les **services backend core** de ClearportX :

1. **LedgerApi** (450+ lignes) :
   - Wrapper Canton Ledger API (gRPC)
   - create(), exercise(), createAndGetCid()
   - Multi-party authorization (exerciseWithParties)
   - Transaction tree parsing (deterministic CID extraction)
   - OpenTelemetry tracing

2. **IdempotencyService** (158 lignes) :
   - Prevent duplicate swaps
   - In-memory cache (ConcurrentHashMap)
   - 15-minute TTL
   - Validation (alphanumeric + hyphens + underscores)
   - Production: Redis recommended

3. **LedgerReader** (102 lignes) :
   - Authoritative contract reads (no PQS lag)
   - tokensForParty() - User balances
   - pools() - Active pools
   - DTO conversion (frontend-friendly)

4. **PqsSyncUtil** :
   - Test synchronization (wait for PQS)
   - Poll-based offset check (100ms interval)
   - Timeout protection (10s default)

5. **Patterns** :
   - Deterministic CID extraction (avoid race conditions)
   - Multi-party authorization (match DAML controllers)
   - Idempotency keys (duplicate prevention)
   - Ledger API vs PQS (choose right tool)

**Next Steps** : Module 06 (Sécurité & Infrastructure).

