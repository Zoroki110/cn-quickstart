# ClearportX Production Readiness Plan

## État des Lieux (Current State Assessment)

### ✅ What Works
1. **Legacy Token.Token DEX** - Drain+Credit pattern fully operational, no "contract consumed twice" errors
2. **CIP-0056 Holdings Display** (Phase 4.1) - HoldingsService queries real Canton holdings via `splice_api_token_holding_v1` interface
3. **JWT Authentication** - Challenge-response flow with HMAC-signed tokens
4. **Pool Operations** - Create, add liquidity, swap all functional with legacy tokens
5. **Backend Infrastructure** - Canton 3.4.7, Splice 0.5.1, Spring Boot, Redis caching

### 🔴 Blocking Issue: CBTC Transfer Acceptance
**Root Cause**: TransferOffer is visible to ClearportX-DEX-1 but `TransferInstruction` (which carries the `Accept` choice) is NOT disclosed.

- This is a **ledger visibility/disclosure/authorizers** problem, not a coding issue
- The `cbtc-lib` package controls disclosure of TransferInstruction contracts
- ClearportX-DEX-1 party cannot exercise `TransferInstruction_Accept` because the contract isn't visible

### 📦 Current Codebase Structure
| Component | Status | Location |
|-----------|--------|----------|
| HoldingsService | ✅ Complete | `backend/.../service/HoldingsService.java` (445 lines) |
| HoldingsController | ✅ Complete | `backend/.../controller/HoldingsController.java` (70 lines) |
| Pool.daml (Drain+Credit) | ✅ Complete | `clearportx/daml/AMM/Pool.daml` (463 lines) |
| Token.daml | ✅ Complete | `clearportx/daml/Token/Token.daml` (164 lines) |
| TokenStandardAdapter | ⚠️ Read-only | `clearportx/daml/TokenStandardAdapter.daml` |
| Wallet Connectors | ⚠️ Partial | `frontend/src/wallet/` (Dev, Loop, Zoro) |

---

## Implementation Plan

### Phase A: Loop Wallet Integration (Unblock Development)

**Objective**: Bypass CBTC disclosure issue by delegating transfer acceptance to Loop wallet (which has proper visibility).

#### A.1 Loop Wallet Connector Enhancement
- **File**: `frontend/src/wallet/LoopWalletConnector.ts`
- **Current**: Basic OAuth flow, no transfer acceptance
- **Changes**:
  1. Add `acceptTransfer(offerId: string)` method that calls Loop's accept endpoint
  2. Implement polling for pending TransferOffers from `/api/holdings` or Loop API
  3. Bridge the acceptance result back to ClearportX UI

#### A.2 Backend Loop Proxy (Optional)
- **File**: New `backend/.../controller/LoopProxyController.java`
- **Purpose**: If Loop requires server-to-server calls for acceptance
- **Endpoints**:
  - `POST /api/loop/transfers/accept` - Proxy to Loop's acceptance API
  - `GET /api/loop/transfers/pending` - List pending offers for party

#### A.3 UI Flow Update
- **Files**: `frontend/src/pages/Wallet.tsx`, `frontend/src/components/TransferCard.tsx`
- **Changes**:
  1. Show pending TransferOffers with "Accept via Loop" button
  2. On click → redirect to Loop wallet or call LoopWalletConnector.acceptTransfer()
  3. Refresh holdings after acceptance confirmation

**Deliverable**: Users can accept CBTC transfers through Loop wallet, unblocking real token usage.

---

### Phase B: CBTC Disclosure Resolution (Parallel Track)

**Objective**: Work with Digital Asset/CBTC team to get TransferInstruction disclosed to ClearportX-DEX-1.

#### B.1 Investigation Steps
1. Identify which party controls TransferInstruction disclosure in `cbtc-lib`
2. Determine if disclosure requires:
   - Grant from CBTC issuer/operator
   - App install with specific readAs rights
   - Modification to cbtc-lib DAR

#### B.2 Possible Solutions
| Option | Effort | Description |
|--------|--------|-------------|
| Grant Request | Low | Ask CBTC operator to add ClearportX as observer |
| App Install Update | Medium | Modify app install template to include TransferInstruction visibility |
| Fork cbtc-lib | High | Add disclosure logic to TransferInstruction template (last resort) |

#### B.3 Escalation Path
1. Document exact error/visibility state with contract IDs
2. Submit to DA support or CBTC team Slack channel
3. Provide test scenario for reproduction

---

### Phase 4.3: Holdings-Based AMM Pools (After Phase A)

**Objective**: Replace legacy Token.Token with real CIP-0056/CIP-0089 holdings in AMM pools.

#### 4.3.1 Pool.daml Refactor
- **Current**: `TokenCid` references Token.Token contracts
- **Target**: `HoldingCid` references splice_api_token_holding_v1 Holding contracts
- **Changes**:
  1. Update Pool template to use Holding interface instead of Token.Token
  2. Modify AtomicSwap to call Holding transfer choices (not Drain/Credit)
  3. Preserve TVL/reserve calculations with proper decimal handling

#### 4.3.2 TokenStandardAdapter Enhancement
- **Current**: Read-only queries (getBalance, getHoldings)
- **Target**: Write operations for pool interactions
- **New Choices**:
  - `TransferToPool` - Move holding into pool reserves
  - `TransferFromPool` - Move holding out of pool to trader

#### 4.3.3 Backend Service Updates
- **Files**: `SwapService.java`, `AddLiquidityService.java`, `DamlRepository.java`
- **Changes**:
  1. Query holdings via HoldingsService instead of Token.Token queries
  2. Execute Holding transfers instead of Drain/Credit
  3. Handle CIP-0089 metadata for display

#### 4.3.4 Migration Strategy
1. Keep legacy pools operational during transition
2. Create new pool type `PoolV2` with Holdings-based reserves
3. Provide UI toggle between legacy/V2 pools
4. Deprecate legacy pools after V2 validation

---

### Phase 5: Production Hardening

#### 5.1 Security
| Task | Priority |
|------|----------|
| Rate limiting on all endpoints | High |
| Replay protection (nonce/timestamp) | High |
| Challenge expiry (5 min TTL) | High |
| JWT token rotation | Medium |
| Audit logging for all ledger ops | High |

#### 5.2 Performance
| Task | Priority |
|------|----------|
| Holdings cache (60s TTL already exists) | ✅ Done |
| Pool metrics cache | High |
| Connection pooling audit | Medium |
| Ledger batching for reads | Medium |

#### 5.3 Observability
| Task | Priority |
|------|----------|
| Prometheus metrics for swap latency | High |
| Error rate dashboards | High |
| Ledger visibility alerts | Medium |
| Health endpoint enhancement | Medium |

#### 5.4 Splice Upgrade (0.5.1 → 0.5.4)
- **Breaking Change**: Docker binds to 127.0.0.1 by default
- **Fix**: Add `HOST_BIND_IP=0.0.0.0` to `.env`
- **Steps**: See `SPLICE_UPGRADE_0.5.1_TO_0.5.4.md`

---

## Recommended Execution Order

```
Week 1:
├── Phase A.1: Loop Wallet Connector (acceptTransfer)
├── Phase A.3: UI for pending transfers
└── Phase B.1: Investigation (parallel)

Week 2:
├── Phase A.2: Backend Loop Proxy (if needed)
├── Phase B.2-B.3: Escalation to DA/CBTC team
└── Phase 5.1: Security hardening (rate limiting, replay protection)

Week 3:
├── Phase 4.3.1-4.3.2: Pool.daml refactor for Holdings
└── Phase 5.2: Performance optimization

Week 4:
├── Phase 4.3.3-4.3.4: Backend + Migration
├── Phase 5.3: Observability
└── Phase 5.4: Splice upgrade

Week 5:
└── E2E testing, documentation, production deployment
```

---

## Success Criteria

1. **Phase A Complete**: Users can accept CBTC via Loop wallet, balances reflect in ClearportX
2. **Phase B Resolved**: TransferInstruction disclosed to ClearportX-DEX-1 (or documented workaround)
3. **Phase 4.3 Complete**: AMM pools use real CIP-0056 holdings, legacy pools deprecated
4. **Phase 5 Complete**: All security/performance/observability tasks green
5. **Production Ready**: Splice 0.5.4, full E2E test suite passing, monitoring in place
