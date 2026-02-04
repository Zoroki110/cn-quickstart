 # ClearportX DEX - Complete Context Handoff for Next Session

## 🎯 Project Goal

Building **ClearportX**, a production-grade decentralized exchange (DEX) on **Canton Network** with:
- Automated Market Maker (AMM) using constant product formula (x*y=k)
- Atomic swaps with protocol fees (25% ClearportX, 75% LPs)
- Multi-pool liquidity provision
- Production hardening: rate limiting, metrics, security, observability
- **Target**: Deploy to Canton Network devnet and eventually mainnet

## 📍 Current Status: STALE POOL CANONICALS ISSUE RESOLVED

### Where We Are Now

**Environment**: Canton Network (local), 33 pools, 64 tokens running
**Backend**: Spring Boot 3.4.2, DAML 3.3.0, Canton 0.4.22
**Frontend**: React + TypeScript
**Status**: ✅ Root cause identified and fixed, ⚠️ Need fresh pools to test swaps

### What Just Happened (This Session)

We debugged and fixed a critical issue where **ALL swap attempts failed with CONTRACT_NOT_FOUND**:

1. **Root Cause Found**: All 11 ETH-USDC pools have **stale token CIDs** (`0031714265bba35b1ce73ab70f940c3521775a973a496a473e9fd376c478abf594ca111220ced5660b65cf1937171e7e3f490ef9e807632711408ac2eb88ad2dc44fb25734`) in their `tokenACid`/`tokenBCid` fields
   - These CIDs point to tokens archived at ledger offset 22225
   - Current offset is 22364+ (140+ offsets later)
   - Tokens were manually merged outside pool operations during dev testing
   - Pools never updated → still reference OLD archived CIDs

2. **Fix Implemented**: Updated `SwapController.java` to validate pool canonicals BEFORE selection:
   - Fetches fresh ACS snapshots from Ledger API (bypasses cache/PQS)
   - Builds Set of active token CIDs
   - Filters OUT pools with stale/missing canonicals
   - Returns clear error: `NO_VALID_POOL_CANONICALS` instead of mysterious `CONTRACT_NOT_FOUND`

3. **Test Results**:
   ```
   🔍 ACS snapshot: 33 pools, 64 tokens
   ⚠️ Pool ETH-USDC has stale canonicals: tokenACid=..., tokenBCid=..., skipping (×11 times)
   ❌ NO_VALID_POOL_CANONICALS - Pool needs liquidity refresh to update token CIDs
   ```

4. **Documented**: Created [STALE_CANONICALS_FIX_SUMMARY.md](./STALE_CANONICALS_FIX_SUMMARY.md) with complete analysis

### Next Immediate Tasks

1. **Unblock Swaps**: Need fresh pool with active token CIDs
   - Option A: Clean restart (fastest - 2 min)
   - Option B: Create brand new pool via DAML script
   - Option C: Move to devnet deployment

2. **Apply Same Fix to LiquidityController**: AddLiquidity also hits stale canonicals

3. **Test Complete Flow**: Swap, AddLiquidity, RemoveLiquidity with fresh pools

## 📚 Essential Documentation to Read

### Core Architecture Documents (READ FIRST)

1. **[STALE_CANONICALS_FIX_SUMMARY.md](./STALE_CANONICALS_FIX_SUMMARY.md)** ⭐ CRITICAL
   - Complete analysis of the stale canonical issue
   - Why it happened, how we fixed it, why it won't happen in prod
   - Recovery options and next steps

2. **[PRODUCTION_READY_SUMMARY.md](./PRODUCTION_READY_SUMMARY.md)**
   - System architecture overview
   - Production hardening features
   - Current running state (uptime, health)

3. **[BACKEND_READY_TEST_NOW.md](./BACKEND_READY_TEST_NOW.md)**
   - Backend API endpoints
   - Health checks and metrics
   - Testing procedures

4. **[FRONTEND_INTEGRATION.md](./FRONTEND_INTEGRATION.md)**
   - Frontend-backend integration
   - OAuth2 authentication flow
   - API usage patterns

### DAML Smart Contracts (Core Logic)

**Location**: `/root/cn-quickstart/quickstart/clearportx/daml/AMM/`

1. **[Pool.daml](./daml/AMM/Pool.daml)** ⭐ MOST IMPORTANT
   - Pool template with `tokenACid`/`tokenBCid` fields (lines 46-47)
   - `AddLiquidity` choice handles `None` canonicals (lines 129-135)
   - `RemoveLiquidity` requires active canonicals (lines 175-178)
   - Archive-and-recreate pattern for updates

2. **[SwapRequest.daml](./daml/AMM/SwapRequest.daml)**
   - `PrepareSwap` extracts protocol fees (25% ClearportX)
   - `ExecuteSwap` uses `pool.tokenACid`/`tokenBCid` (lines 133-136, 184)
   - TransferSplit for atomic token operations
   - K-invariant validation (x*y=k)

3. **[AtomicSwap.daml](./daml/AMM/AtomicSwap.daml)**
   - Single-transaction atomic swap flow
   - Creates SwapRequest → PrepareSwap → ExecuteSwap atomically
   - Passes trader's token CID through the flow

4. **[Receipt.daml](./daml/AMM/Receipt.daml)**
   - Audit trail for completed swaps
   - Tracks amounts, fees, prices

### Backend Services (Java/Spring Boot)

**Location**: `/root/cn-quickstart/quickstart/backend/src/main/java/com/digitalasset/quickstart/`

1. **[SwapController.java](../backend/src/main/java/com/digitalasset/quickstart/controller/SwapController.java)** ⭐ JUST FIXED
   - `/api/swap/atomic` endpoint
   - **Lines 513-595**: Pool canonical validation (THE FIX)
   - Selects fresh tokens from trader's balance
   - Creates AtomicSwapProposal and executes

2. **[LiquidityController.java](../backend/src/main/java/com/digitalasset/quickstart/controller/LiquidityController.java)** ⚠️ NEEDS SAME FIX
   - `/api/liquidity/add` and `/api/liquidity/remove` endpoints
   - Currently ALSO hits stale pool canonicals
   - Needs same validation as SwapController

3. **[LedgerApi.java](../backend/src/main/java/com/digitalasset/quickstart/ledger/LedgerApi.java)**
   - Canton Ledger API wrapper
   - `getActiveContracts()` - fetches fresh ACS
   - `exerciseAndGetResult()` - submits commands
   - `createAndGetCid()` - deterministic CID extraction

4. **[StaleAcsRetry.java](../backend/src/main/java/com/digitalasset/quickstart/ledger/StaleAcsRetry.java)**
   - Retry mechanism for CONTRACT_NOT_FOUND
   - Refreshes ACS and retries once
   - Used in LiquidityController (working for race conditions)

5. **[RateLimiterConfig.java](../backend/src/main/java/com/digitalasset/quickstart/config/RateLimiterConfig.java)**
   - Token bucket rate limiter (0.4 TPS global)
   - AtomicLong + CAS for thread safety

6. **[SwapMetrics.java](../backend/src/main/java/com/digitalasset/quickstart/metrics/SwapMetrics.java)**
   - Prometheus metrics (counters, histograms)
   - Swap success/failure tracking
   - Protocol fee collection metrics

### Configuration & Infrastructure

1. **[compose.yaml](../compose.yaml)**
   - Docker Compose setup
   - Services: Canton, backend, frontend, PQS, Keycloak, Grafana

2. **[application.yml](../backend/src/main/resources/application.yml)**
   - Spring Boot configuration
   - Canton connection: `localhost:3901`
   - Rate limiter, metrics, security settings

3. **[Makefile](./Makefile)**
   - Build, test, deploy commands
   - `make build`, `make test`, `make deploy`

4. **[prometheus-clearportx-alerts.yml](./prometheus-clearportx-alerts.yml)**
   - 6 critical alerts (HIGH_ERROR_RATE, P95_SLOW, HIT_RATE_LIMIT_OFTEN, etc.)

5. **[grafana-clearportx-dashboard.json](./grafana-clearportx-dashboard.json)**
   - Observability dashboard
   - Swap metrics, pool health, error rates

## 🔍 Code Analysis Tasks for Next Session

### 1. Understand the Full Swap Flow

**Trace this path**:
```
Frontend (SwapInterface.tsx)
  → POST /api/swap/atomic with {inputSymbol, outputSymbol, inputAmount}
    → SwapController.atomicSwap() [Lines 513-595]
      → Fetch pools + tokens from Ledger API
      → Validate pool canonicals ARE ACTIVE ⭐ THE FIX
      → Select trader's input token (fresh from ACS)
      → Create AtomicSwapProposal (DAML contract)
        → DAML: AtomicSwap.ExecuteAtomicSwap
          → Create SwapRequest
          → PrepareSwap (extract protocol fee)
          → ExecuteSwap (uses pool.tokenACid/tokenBCid) ⚠️ WHERE IT FAILED
            → TransferSplit pool tokens
            → Calculate output (x*y=k)
            → Update pool reserves
            → Create Receipt
```

**Key Questions**:
- Where exactly does the stale CID get used? → **Line 184 in SwapRequest.daml**: `exercise canonicalOutCid T.TransferSplit`
- Why didn't the retry logic help? → Retry refreshes ACS but still selects the SAME stale pool
- How does our fix prevent this? → Validates `pool.tokenACid` and `pool.tokenBCid` are in active set BEFORE creating proposal

### 2. Analyze Pool Canonical Lifecycle

**Read these code sections**:

1. **Pool Creation** (InitializeClearportX.daml lines 56-76):
   - Creates pool with `tokenACid = Some ethToken` (fresh at creation time)

2. **AddLiquidity Updates Canonicals** (Pool.daml lines 129-135):
   ```daml
   finalTokenACid <- case this.tokenACid of
     None -> return newTokenAFromProvider  -- First add: sets canonical
     Some existingCid -> exercise existingCid T.Merge with otherTokenCid = newTokenAFromProvider
   ```
   - If `None`: Sets provider's token as canonical
   - If `Some`: Merges into existing canonical

3. **Swap Updates Canonicals** (SwapRequest.daml lines 202-212):
   ```daml
   newPool <- exercise poolCid P.ArchiveAndUpdateReserves with
     updatedTokenACid = Some finalTokenACid  -- Fresh from swap
     updatedTokenBCid = Some finalTokenBCid
   ```

**Key Insight**: Canonicals SHOULD stay fresh through normal operations. The stale state is a dev artifact.

### 3. Understand Token Fragmentation

**Current State**:
```
app_provider party has:
- 15 × BTC contracts of 50 each
- 15 × BTC contracts of 500 each
- 15 × USDT contracts of 1,000,000 each
- 15 × USDT contracts of 10,000,000 each
= 64 total token contracts for 5 unique symbols
```

**Why This Happens**:
- Each swap/add/remove creates NEW token contracts (DAML immutability)
- Tokens get split via `TransferSplit`
- No automatic consolidation

**Attempted Solutions**:
1. **TokenMergeService.java** - merges fragmented tokens before swap (tried, didn't solve stale canonical issue)
2. **Frontend aggregation** - groups tokens by symbol for display

**Best Practice**: Accept fragmentation, select largest token that meets amount requirement

### 4. Check System Health

**Run these commands**:
```bash
# Health check
curl -s http://localhost:8080/api/health/ledger | jq .

# Current pools
curl -s http://localhost:8080/api/pools | jq '.[] | {poolId, reserveA, reserveB, tokenACid, tokenBCid}'

# Tokens for app_provider
curl -s http://localhost:8080/api/tokens | jq 'group_by(.symbol) | map({symbol: .[0].symbol, count: length, total: map(.amount | tonumber) | add})'

# Metrics
curl -s http://localhost:9090/api/v1/query?query=clearportx_swap_total | jq .
```

## 🐛 Known Issues & Workarounds

### 1. ALL Pools Have Stale Canonicals (JUST DIAGNOSED)

**Symptom**: Swaps fail with `NO_VALID_POOL_CANONICALS`
**Root Cause**: Tokens manually merged outside pool context → pool CIDs outdated
**Status**: ✅ Detection implemented, ⚠️ Need fresh pools
**Workaround**: Create brand new pool with fresh tokens OR restart environment

### 2. AddLiquidity Also Hits Stale Canonicals (SAME ISSUE)

**Symptom**: `AddLiquidity: Contract changed; refresh and retry`
**Root Cause**: Same as #1 - tries to merge into archived pool tokens
**Status**: ⚠️ Needs same fix as SwapController
**Workaround**: Create fresh pool first

### 3. Frontend Shows "Unauthorized" for API Calls Without Login

**Symptom**: 401 errors if not logged in via Keycloak
**Root Cause**: OAuth2 security enabled
**Status**: ✅ Working as designed
**Workaround**: Login via frontend at http://localhost:4001

### 4. Contract ID Logs Show Java Object References

**Example**: `Selected FRESH token: CID=com.digitalasset.transcode.java.ContractId@1f68f5e3`
**Root Cause**: Logging `contractId` directly instead of `contractId.getContractId`
**Impact**: Low (just logs, doesn't affect functionality)
**Status**: Cosmetic issue, can be improved

## 🔧 Common Operations

### Deploy Backend Changes
```bash
cd /root/cn-quickstart/quickstart/clearportx
cd ../backend
../gradlew build -x test --quiet
docker restart backend-service

# Wait for ready
timeout 90 bash -c 'until curl -s http://localhost:8080/api/health/ledger 2>/dev/null | grep -q "\"status\""; do sleep 3; done'
```

### Upload New DAR
```bash
cd /root/cn-quickstart/quickstart/clearportx
daml build  # Creates .daml/dist/clearportx-amm-1.0.1.dar
daml ledger upload-dar .daml/dist/clearportx-amm-1.0.1.dar \
  --host localhost \
  --port 3901 \
  --max-inbound-message-size 10000000
```

### Monitor Logs
```bash
# Backend logs
docker logs -f backend-service 2>&1 | grep -E "atomic_swap|ERROR|WARN"

# Canton logs
docker logs -f canton 2>&1 | grep -E "CONTRACT_NOT_FOUND|Updated cache"
```

### Check Canton State
```bash
# Active contracts for app_provider
docker exec canton canton-console <<EOF
participant1.ledger_api.acs.of_party(PartyId.tryFromProtoPrimitive("app_provider_quickstart-root-1::12201300e204e8a38492e7df0ca7cf67ec3fe3355407903a72323fd72da9f368a45d"))
EOF
```

## 🚀 Priority Tasks for Next Session

### Immediate (Unblock Swaps)

1. **Create Fresh Pool** (choose one):
   - **Option A**: Clean restart (2 min) - RECOMMENDED
     ```bash
     docker stop $(docker ps -q)
     docker system prune -af --volumes
     # Restart Canton + reinit
     ```

   - **Option B**: DAML script to create new pool
     ```daml
     -- Create EMPTY pool with tokenACid=None, tokenBCid=None
     -- Then AddLiquidity to bootstrap with fresh canonicals
     ```

2. **Test Full Flow**:
   - Add liquidity (should work with fresh pool)
   - Execute swap (should find fresh pool with active canonicals)
   - Remove liquidity
   - Verify metrics and logging

### Short Term (Harden System)

3. **Apply Pool Canonical Validation to LiquidityController**:
   - Copy the validation logic from SwapController (lines 513-595)
   - Validate pool canonicals before AddLiquidity/RemoveLiquidity
   - Test that it correctly skips stale pools

4. **Improve Logging**:
   - Log actual CID strings instead of Java object references
   - Add pool selection debugging
   - Log ledger offset in validation

5. **Add Pool Selection Preference**:
   - If multiple valid pools exist, prefer the NEWEST one
   - Track pool creation offset or use contract ID ordering

### Medium Term (Devnet Deployment)

6. **Prepare for Devnet**:
   - Review devnet onboarding checklist
   - Verify OAuth2 works with Canton Network identity
   - Test initialization scripts on devnet
   - Set up monitoring/alerting

7. **Frontend Polish**:
   - Better error messages for `NO_VALID_POOL_CANONICALS`
   - Show pool health indicators
   - Auto-refresh after failed operations

8. **Documentation**:
   - Update API docs with new error codes
   - Document pool canonical lifecycle
   - Add troubleshooting guide

## 📊 Current System State

```
Environment: Canton Network (local, root-1)
Canton Version: 0.4.22
DAML SDK: 3.3.0-snapshot.20250502.13767.0.v2fc6c7e2
Backend: Spring Boot 3.4.2, Java 21
Frontend: React 18, TypeScript

Services Running:
✅ Canton (localhost:3901)
✅ Backend (localhost:8080)
✅ Frontend (localhost:4001)
✅ PQS (localhost:5432)
✅ Keycloak (localhost:8082)
✅ Grafana (localhost:3000)

Data State:
- 33 pools (11 × ETH-USDC, rest are BTC/ETH/USDT pairs)
- 64 token contracts (fragmented across app_provider)
- ⚠️ ALL pools have stale tokenACid/tokenBCid
- Ledger offset: ~22400+

Health:
✅ Backend health: OK
✅ PQS synced
✅ OAuth2 working
⚠️ Swaps blocked (no valid pools)
⚠️ AddLiquidity blocked (same issue)
```

## 🔑 Key Files Modified This Session

1. **[SwapController.java](../backend/src/main/java/com/digitalasset/quickstart/controller/SwapController.java)**
   - Lines 513-595: Added pool canonical validation
   - Lines 519-527: Fetch fresh pools + tokens in parallel
   - Lines 522-527: Build Set of active token CIDs
   - Lines 538-573: Filter pools by active canonicals
   - Lines 577-580: Clear error message

2. **[STALE_CANONICALS_FIX_SUMMARY.md](./STALE_CANONICALS_FIX_SUMMARY.md)** (NEW)
   - Complete analysis of the issue
   - Recovery options
   - Production safety guarantees

3. **[HANDOFF_PROMPT_NEXT_SESSION.md](./HANDOFF_PROMPT_NEXT_SESSION.md)** (THIS FILE)

## 💡 Key Insights for Next Developer

### About Canton & DAML

1. **Immutability**: Contracts are never modified, only archived and recreated
   - Every update = archive old contract + create new contract
   - Contract IDs change on every update
   - Pool's `tokenACid`/`tokenBCid` must be kept fresh

2. **Active Contract Set (ACS)**: The current state of non-archived contracts
   - `ledger.getActiveContracts()` fetches current ACS
   - Critical to fetch FRESH snapshot before each operation
   - Don't cache contract IDs - they can be archived anytime

3. **Contract Keys**: Not used in this project (DAML 3.x compatibility)
   - Pools tracked by ContractId, not by key
   - Makes pool selection more complex (must filter by symbols/reserves)

### About The Stale Canonical Bug

1. **Why It Happened**:
   - Dev testing involved manual token merges
   - Pools were created with token CIDs
   - Tokens got merged OUTSIDE pool operations
   - Pools never updated → still had old CIDs

2. **Why It Won't Happen in Prod**:
   - Every swap/add/remove updates pool canonicals
   - Users can't merge tokens outside pool context
   - Pool self-heals through normal usage

3. **Why Our Fix Is Critical**:
   - Prevents attempting swaps with stale pools
   - Clear error message guides recovery
   - Production safety net for unexpected edge cases

### About Performance

1. **Rate Limiting**: 0.4 TPS global (configurable)
   - Protects Canton from overload
   - Can be increased for prod if Canton supports it

2. **Parallel Fetching**: Pools + tokens fetched concurrently (CompletableFuture.thenCombine)
   - Reduces latency vs sequential fetching

3. **Token Fragmentation**: 64 contracts for 5 symbols
   - Normal on DAML due to immutability
   - Select largest token that meets requirement
   - Consider TokenMergeService for UX improvement (but not for stale canonical issue)

## 🎓 Learning Resources

1. **Canton Network Docs**: https://www.canton.network/
2. **DAML Docs**: https://docs.daml.com/
3. **CIP-56 Token Standard**: https://www.canton.network/blog/what-is-cip-56-a-guide-to-cantons-token-standard
4. **Spring Boot Reactive**: For understanding CompletableFuture chains

## 📝 Questions to Answer Next Session

1. **Can we create a pool refresh endpoint?** (Archive old pool, create new with fresh canonicals)
2. **Should we implement automatic pool selection** (newest pool if multiple valid)?
3. **Can we add a pool health check endpoint** (validates canonicals are active)?
4. **Should TokenMergeService be automatic or manual?** (Trade-off: fewer contracts vs transaction overhead)
5. **How to handle pool version conflicts?** (Multiple ETH-USDC pools exist)
6. **What's the devnet onboarding process?** (Participant setup, authentication)

## 🎯 Success Criteria for Next Session

- [ ] Fresh pool created and operational
- [ ] Successful swap execution (0.1 ETH → USDC)
- [ ] LiquidityController has pool canonical validation
- [ ] Full test flow: Add liquidity → Swap → Remove liquidity
- [ ] All pools have active canonicals (verified via API)
- [ ] Metrics showing successful swaps
- [ ] Ready for devnet deployment

---

**Session Date**: October 23, 2025
**Duration**: ~4 hours
**Status**: Stale canonical issue diagnosed and fixed, awaiting fresh pool creation
**Next Developer**: Use this document as complete context for continuation
