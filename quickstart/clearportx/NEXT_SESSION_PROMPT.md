# ClearportX Development Session - Production Readiness

## Context

We are continuing the development of ClearportX, an AMM DEX built on Canton Network DevNet. The legacy Token.Token implementation with Drain+Credit pattern is fully operational. We now need to integrate with real Canton Network assets (CBTC, Canton Coin) and prepare for production.

---

## Current State

### ✅ Working
- **Drain+Credit AMM**: No "contract consumed twice" errors
- **CIP-0056 Holdings Display**: Backend queries real holdings via `splice_api_token_holding_v1`
- **JWT Authentication**: Challenge-response flow operational
- **Pool Operations**: Create, add liquidity, swap with legacy tokens

### 🔴 Blocking Issue
**CBTC Transfer Acceptance**: `TransferOffer` is visible but `TransferInstruction` (which carries the `Accept` choice) is NOT disclosed to ClearportX-DEX-1. This is a ledger visibility/disclosure issue, not a coding problem.

---

## Key Highlights of the Plan

### Phase A: Loop Wallet Integration (Unblock Development)
- Bypass CBTC disclosure issue by delegating transfer acceptance to Loop wallet
- Add `acceptTransfer()` method to LoopWalletConnector
- UI shows pending TransferOffers with "Accept via Loop" button
- **Deliverable**: Users can accept CBTC via Loop wallet

### Phase B: CBTC Disclosure Resolution (Parallel Track)
- Work with DA/CBTC team to get TransferInstruction disclosed
- Options: Grant request, App install update, or fork cbtc-lib (last resort)

### Phase 4.3: Holdings-Based AMM Pools
- Replace legacy Token.Token with real CIP-0056 holdings
- Update Pool.daml to use Holding interface
- Add `TransferToPool` / `TransferFromPool` choices to TokenStandardAdapter
- Run both pool types in parallel during migration

### Phase 5: Production Hardening
- **Security**: Rate limiting, replay protection, challenge expiry, audit logging
- **Performance**: Holdings cache (done), pool metrics cache, connection pooling
- **Observability**: Prometheus metrics, error dashboards, visibility alerts
- **Splice Upgrade**: 0.5.1 → 0.5.4 (requires `HOST_BIND_IP=0.0.0.0`)

---

## Clear Execution Path

### Step 1: Stabilize Today's Flow
- Frontend resolves fresh pool CIDs before every swap/add-liquidity
- Backend auto-mints fresh tokens when Token.Token CID is consumed
- Verify two consecutive liquidity adds succeed

### Step 2: Enable Read-Only CIP-0056 Visibility
- Expose `/api/holdings/{party}` (already done via HoldingsController)
- Frontend shows holdings badge (CBTC, Amulet totals)
- Prove validator sees Canton-standard assets before bridge logic

### Step 3: Receive DevNet CBTC and Canton Coin
- Use Canton wallet SDK tap flow or faucet REST
- Configure validator auto-accept + TransferMonitorService
- Get on-ledger Holdings to feed later pools

### Step 4: Introduce Holdings-Based Pools
- Add HoldingPool templates with UTXO set reserves
- Backend helpers for UTXO selection (start greedy)
- Seed initial CBTC/CC pool
- Run both pool types in parallel

### Step 5: Wallet-First Authentication + Result<T> Pipelines
- Implement PartyValidationService
- Challenge-based sessions
- Result<T>/flatMap helpers (no blanket try/catch)
- Every controller composes Result-returning steps

### Step 6: Progressive Migration to Full CIP-0056
- Bridge choices (Token ↔ Holding)
- Transfer/Allocation interfaces
- UTXO consolidation jobs
- Performance tests (fragmentation, multi-UTXO swaps)

---

## Architecture Principles

1. **No Blanket Try/Catch**: Use Result<T> pattern for explicit error handling
2. **Composable Pipelines**: Each controller step returns Result, making failures testable
3. **Hybrid First**: Keep legacy AMM working while exercising new Holdings flow
4. **Controlled Increments**: Each capability benchmarked before promotion

---

## Files to Know

| File | Purpose |
|------|---------|
| `backend/.../service/HoldingsService.java` | CIP-0056 holdings queries |
| `backend/.../controller/HoldingsController.java` | REST endpoint for holdings |
| `clearportx/daml/AMM/Pool.daml` | Drain+Credit AtomicSwap |
| `clearportx/daml/Token/Token.daml` | Legacy token with Drain/Credit choices |
| `clearportx/daml/TokenStandardAdapter.daml` | Read-only CIP-0056 adapter |
| `frontend/src/wallet/LoopWalletConnector.ts` | Loop wallet integration |
| `frontend/src/wallet/useWalletAuth.ts` | JWT auth hook |

---

## Success Criteria

1. Users can accept CBTC via Loop wallet
2. TransferInstruction disclosed to ClearportX-DEX-1 (or documented workaround)
3. AMM pools use real CIP-0056 holdings
4. All security/performance/observability tasks complete
5. Splice 0.5.4, full E2E test suite passing

---

## Your Take (Mentor Perspective)

The plan is solid and well-structured. Here's my assessment:

**Strengths:**
- Pragmatic approach: Loop wallet workaround unblocks development while CBTC disclosure is resolved
- Hybrid strategy: Keeping legacy pools operational during migration reduces risk
- Result<T> pattern: This is the right way to handle Canton's async/multi-party nature
- Phased execution: Each step builds on the previous, with clear validation gates

**Watch Points:**
1. **CBTC disclosure** may take longer than expected - prioritize Phase A to unblock
2. **UTXO selection** in Holdings pools needs careful design for fragmentation
3. **Performance testing** should start early - don't wait for Phase 5

**Recommendation:**
Start with Step 1 (stabilize today's flow) and Step 2 (CIP-0056 visibility) in parallel. These are independent and will give immediate value while we investigate the CBTC disclosure issue.

Let me know when you want to kick off Step 1 and I'll start implementing.
