# Stale Pool Canonicals - Root Cause, Fix & Recovery

## 🔴 Problem Summary

**Symptom**: All swaps and addLiquidity operations fail with `CONTRACT_NOT_FOUND` error for contract `0031714265...`

**Root Cause**: ALL 11 ETH-USDC pools have **stale token CIDs** in their `tokenACid`/`tokenBCid` fields:
- These CIDs point to tokens that were archived days ago (offset 22225, current offset 22364+)
- Tokens were manually merged outside pool operations (dev testing)
- Pools were never updated → still reference OLD archived CIDs
- When DAML tries to use `pool.tokenACid` for swaps, it gets `CONTRACT_NOT_FOUND`

**Proof from Logs**:
```
🔍 ACS snapshot: 33 pools, 64 tokens
⚠️ Pool ETH-USDC has stale canonicals: tokenACid=..., tokenBCid=..., skipping (×11)
❌ NO_VALID_POOL_CANONICALS - Pool needs liquidity refresh to update token CIDs
```

## ✅ Fix Implemented

### [SwapController.java](../backend/src/main/java/com/digitalasset/quickstart/controller/SwapController.java) - Pool Canonical Validation

**What it does**:
1. Fetches FRESH snapshots from Ledger API (no cache/PQS)
2. Builds `Set<ContractId>` of all active token CIDs
3. **Validates pool canonicals**: Only selects pools where BOTH `tokenACid` AND `tokenBCid` are in the active set
4. Skips pools with stale/missing canonicals with warning logs
5. Returns clear error: `NO_VALID_POOL_CANONICALS` instead of mysterious `CONTRACT_NOT_FOUND`

**Code Changes** (lines 513-595):
```java
// Fetch FRESH snapshots (Ledger API gRPC; no app cache, no PQS)
CompletableFuture<List<LedgerApi.ActiveContract<Pool>>> poolsFuture = ledger.getActiveContracts(Pool.class);
CompletableFuture<List<LedgerApi.ActiveContract<Token>>> tokensFuture = ledger.getActiveContracts(Token.class);

return poolsFuture.thenCombine(tokensFuture, (pools, tokens) -> {
    // Build Set of active token CIDs (to validate pool canonicals are alive)
    Set<ContractId<Token>> activeTokenCids = tokens.stream()
        .map(t -> t.contractId)
        .collect(Collectors.toSet());

    // Find pool with ACTIVE canonicals
    Optional<LedgerApi.ActiveContract<Pool>> maybePool = pools.stream()
        .filter(p -> matchesSymbols(p, req.inputSymbol, req.outputSymbol))
        .filter(p -> p.payload.getReserveA.compareTo(BigDecimal.ZERO) > 0)
        .filter(p -> p.payload.getReserveB.compareTo(BigDecimal.ZERO) > 0)
        .filter(p -> {
            // CRITICAL FIX: Validate pool canonicals are present AND active
            boolean hasTokenA = p.payload.getTokenACid.isPresent()
                && activeTokenCids.contains(p.payload.getTokenACid.get());
            boolean hasTokenB = p.payload.getTokenBCid.isPresent()
                && activeTokenCids.contains(p.payload.getTokenBCid.get());
            if (!hasTokenA || !hasTokenB) {
                logger.warn("Pool {} has stale canonicals, skipping", p.payload.getPoolId);
            }
            return hasTokenA && hasTokenB;
        })
        .findFirst();

    if (maybePool.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "NO_VALID_POOL_CANONICALS - Pool needs liquidity refresh to update token CIDs");
    }
    // ... continue with fresh pool
});
```

**Benefits**:
- ✅ Prevents swaps from starting with stale pools
- ✅ Clear, actionable error message
- ✅ No more mystery `CONTRACT_NOT_FOUND` on archived CIDs
- ✅ Safety net for production edge cases

## 🔧 Recovery Options

### Option A: Clean Restart (Fastest - 2 minutes)

**Pros**: Fresh start, guaranteed to work
**Cons**: Loses existing dev data

```bash
# In /root/cn-quickstart/quickstart
docker compose down -v  # Stop and remove volumes
docker compose up -d     # Start fresh
cd clearportx
make build              # Rebuild DARs
make upload-dar         # Upload to Canton
# Initialize pools via frontend or make init (once fixed)
```

### Option B: Create Brand New Pool (Preserves data)

**Pros**: Keeps existing data, surgical fix
**Cons**: More complex, needs Canton authentication

**Steps**:
1. Mint fresh ETH + USDC tokens (owned by app_provider)
2. Create EMPTY pool: `tokenACid=None, tokenBCid=None, reserves=0`
3. Add liquidity → sets fresh canonicals
4. Pool validation will find this fresh pool and use it

**Manual via Backend** (once you have OAuth token):
```bash
# Add liquidity to trigger pool creation/refresh
curl -X POST "http://localhost:8080/api/liquidity/add" \
  -H "Authorization: Bearer $YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "poolId": "ETH-USDC-FRESH",
    "amountA": "10.0",
    "amountB": "30000.0",
    "minLPTokens": "1",
    "slippageToleranceBps": 500
  }'
```

### Option C: Wait for Devnet Deployment

Deploy to Canton Network devnet where you'll have clean pools from initialization scripts.

## 🛡️ Why This Won't Happen in Production

### Automatic Canonical Refresh

**Every operation updates pool canonicals**:

1. **Swap** (SwapRequest.daml:202-212):
   ```daml
   -- Update pool with fresh token CIDs
   newPool <- exercise poolCid P.ArchiveAndUpdateReserves with
     updatedTokenACid = Some finalTokenACid  -- Fresh CID from swap
     updatedTokenBCid = Some finalTokenBCid
   ```

2. **AddLiquidity** (Pool.daml:129-135):
   ```daml
   finalTokenACid <- case this.tokenACid of
     None -> return newTokenAFromProvider  -- First liquidity: fresh CID
     Some existingCid -> exercise existingCid T.Merge with otherTokenCid = newTokenAFromProvider
   ```

3. **RemoveLiquidity**: Same pattern - pool archived and recreated with current token CIDs

### Safety Guarantees

1. **Atomic transactions**: If a canonical is stale, the ENTIRE tx fails (no partial effects)
2. **LP tokens track ownership**: Even if pool tx fails, LP tokens remain valid
3. **Pool party controls canonicals**: Users can't accidentally archive reserve tokens
4. **Our validation is a safety net**: Detects and prevents stale pool usage before submission

### Why This Was a Dev Artifact

**What happened**:
- Pools created with tokens → stored CIDs
- Tokens manually merged OUTSIDE pool context (testing)
- Pools never updated → kept old CIDs
- **This sequence can't happen in production** where users only interact via pool choices

**In production**:
- Pools self-heal through normal operations
- Every swap/add/remove refreshes canonicals
- No manual token merges outside pool control

## 📊 Current State

**Environment**: Canton Network (local), 33 pools, 64 tokens
**Status**: ALL 11 ETH-USDC pools have stale canonicals
**Validation**: ✅ Working (detects and skips stale pools)
**Swaps**: ❌ Blocked (no valid pools available)
**Recovery**: Requires fresh pool creation (Option A or B)

## 🚀 Next Steps

**Immediate (to unblock swaps)**:
1. Choose recovery option (A recommended for speed)
2. Create/initialize fresh pool
3. Test swap in frontend
4. Verify validation finds fresh pool

**Before Devnet Deployment**:
1. ✅ Pool canonical validation (DONE)
2. Add same validation to LiquidityController
3. Test full flow on clean devnet environment
4. Monitor for `stale_pool_canonical` alerts

**Production Hardening** (nice-to-have):
1. Prefer latest pool instance if multiple exist
2. Add pool "RefreshCanonicals" choice (uses token keys)
3. Emit `clearportx.pool.canonical.stale` metric
4. Alert if any stale pool detected

## 📝 Key Takeaways

1. **Root cause**: Stale token CIDs in pool contracts (dev artifact from manual merges)
2. **Fix**: Validate pool canonicals are active before selection
3. **Recovery**: Need fresh pool with active token CIDs
4. **Production**: Pools self-heal through normal operations
5. **Safety**: Our validation prevents silent failures

---

**Date**: October 23, 2025
**Status**: Fix implemented and tested, awaiting fresh pool creation
**Team**: Claude Code + User
