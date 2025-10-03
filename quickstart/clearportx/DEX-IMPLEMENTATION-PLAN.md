# Complete DEX AMM Implementation Plan

**Project:** ClearPortX - Full-Featured DEX on DAML/Canton
**Current Status:** ✅ Core swaps working, 12/12 tests passing
**Goal:** Production-ready DEX with all Uniswap v2 features

---

## 🎯 Vision: What We're Building

A complete decentralized exchange featuring:
- ✅ **Token swaps** (DONE)
- 🔨 **Liquidity provision** (add/remove liquidity)
- 🔨 **LP tokens** (track liquidity provider shares)
- 🔨 **Multi-pool support** (multiple trading pairs)
- 🔨 **Multi-hop routing** (swap through multiple pools)
- 🔨 **Price discovery** (TWAP oracles)
- 🔨 **Governance** (fee voting, parameter updates)

---

## 📋 Implementation Phases

### ✅ Phase 0: Foundation (COMPLETE)
**Status:** 100% Complete
**Tests:** 12/12 passing

- [x] Token with issuer-as-signatory pattern
- [x] Basic AMM pool
- [x] Swap execution (3-transaction flow)
- [x] Fee accumulation (0.3%)
- [x] Slippage protection
- [x] Deadline enforcement
- [x] Constant product formula (x*y=k)

---

### 🔨 Phase 1: Liquidity Provision (NEXT - 3-4 days)
**Priority:** HIGH - Required for functional DEX
**Complexity:** Medium

#### Features to Implement

##### 1.1 Add Liquidity
**Contract:** `LiquidityRequest.daml`

```daml
template AddLiquidityRequest
  with
    provider : Party
    poolCid : ContractId Pool
    tokenACid : ContractId Token
    tokenBCid : ContractId Token
    amountA : Numeric 10
    amountB : Numeric 10
    minLPTokens : Numeric 10  -- Slippage protection
    deadline : Time
```

**Logic:**
1. User proposes liquidity addition
2. Pool calculates LP tokens to mint
3. User transfers both tokens to pool
4. Pool mints LP tokens for user

**Formula:**
```
If first LP:
  lpTokens = sqrt(amountA * amountB)

If existing liquidity:
  lpTokens = min(
    (amountA / reserveA) * totalSupply,
    (amountB / reserveB) * totalSupply
  )
```

##### 1.2 Remove Liquidity
**Contract:** `LiquidityRequest.daml`

```daml
template RemoveLiquidityRequest
  with
    provider : Party
    poolCid : ContractId Pool
    lpTokenCid : ContractId LPToken
    lpAmount : Numeric 10
    minAmountA : Numeric 10  -- Slippage protection
    minAmountB : Numeric 10
    deadline : Time
```

**Logic:**
1. User proposes liquidity removal
2. Pool calculates tokens to return
3. User burns LP tokens
4. Pool transfers tokens back to user

**Formula:**
```
amountA = (lpAmount / totalSupply) * reserveA
amountB = (lpAmount / totalSupply) * reserveB
```

##### 1.3 LP Token Template
**Contract:** `LPToken.daml`

```daml
template LPToken
  with
    issuer : Party      -- Pool
    owner : Party       -- LP provider
    poolId : Text       -- Which pool
    amount : Numeric 10
  where
    signatory issuer
    observer owner
```

**Choices:**
- `Transfer` - Transfer LP tokens to another party
- `Burn` - Destroy LP tokens (for removing liquidity)

#### Tests to Add
- `testAddFirstLiquidity` - First LP in pool
- `testAddSubsequentLiquidity` - Add to existing pool
- `testRemoveLiquidity` - Withdraw liquidity
- `testRemovePartialLiquidity` - Withdraw only portion
- `testLiquiditySlippage` - Reject if LP tokens < minLPTokens
- `testLPTokenTransfer` - Transfer LP tokens between users
- `testProportionalRemoval` - Verify proportional token return

**Estimated Time:** 2-3 days
**Files to Create:**
- `daml/AMM/LiquidityRequest.daml`
- `daml/LPToken/LPToken.daml`
- `daml/TestLiquidity.daml`

---

### 🔨 Phase 2: Multi-Pool Architecture (1-2 weeks)
**Priority:** HIGH - Required for multiple trading pairs
**Complexity:** Medium

#### Features to Implement

##### 2.1 Pool Factory
**Contract:** `PoolFactory.daml`

```daml
template PoolFactory
  with
    operator : Party
    feeRecipient : Party
    defaultFeeBps : Int
  where
    signatory operator

    choice CreatePool : ContractId Pool
      with
        symbolA : Text
        symbolB : Text
        issuerA : Party
        issuerB : Party
        feeBps : Int
      controller operator
```

**Features:**
- Create new pools for any token pair
- Enforce unique pool per pair
- Set default fee structure
- Track all created pools

##### 2.2 Pool Registry
**Contract:** `PoolRegistry.daml`

```daml
template PoolRegistry
  with
    operator : Party
    pools : [(Text, Text, ContractId Pool)]  -- (symbolA, symbolB, poolCid)
  where
    signatory operator

    nonconsuming choice RegisterPool
    nonconsuming choice GetPool
    nonconsuming choice ListPools
```

**Features:**
- Central registry of all pools
- Query pool by token pair
- List all available pairs

##### 2.3 Enhanced Pool
**Update:** `AMM/Pool.daml`

Add fields:
```daml
template Pool
  with
    -- Existing fields...
    lpTokenSupply : Numeric 10      -- NEW: Total LP tokens
    createdAt : Time                 -- NEW: Pool creation time
    accumulatedFees : (Numeric 10, Numeric 10)  -- NEW: Fee tracking
```

#### Tests to Add
- `testCreateMultiplePools` - Create ETH/USDC, ETH/DAI, etc.
- `testPoolUniqueness` - Prevent duplicate pools
- `testPoolDiscovery` - Query pools by token pair
- `testPoolListing` - List all available pairs

**Estimated Time:** 1-2 weeks
**Files to Create:**
- `daml/AMM/PoolFactory.daml`
- `daml/AMM/PoolRegistry.daml`
- `daml/TestMultiPool.daml`

---

### 🔨 Phase 3: Multi-Hop Routing (2-3 weeks)
**Priority:** MEDIUM - Improves user experience
**Complexity:** HIGH

#### Features to Implement

##### 3.1 Router Contract
**Contract:** `Router.daml`

```daml
template SwapRoute
  with
    trader : Party
    path : [ContractId Pool]        -- List of pools to route through
    inputToken : Text
    outputToken : Text
    amountIn : Numeric 10
    minAmountOut : Numeric 10
    deadline : Time
```

**Example:**
```
Alice wants: USDC → BTC
No direct pool exists
Router finds: USDC → ETH → BTC
  Pool 1: USDC/ETH
  Pool 2: ETH/BTC
```

**Logic:**
1. User specifies input/output tokens
2. Router finds best path (Dijkstra's algorithm)
3. Execute sequential swaps
4. Verify final output >= minAmountOut

##### 3.2 Path Finding
**Module:** `Router.PathFinder.daml`

```daml
-- Off-ledger computation
findBestPath :
  [(Text, Text, ContractId Pool)]  -- Available pools
  -> Text                           -- From token
  -> Text                           -- To token
  -> Optional [ContractId Pool]     -- Best path
```

**Algorithm:**
- Build graph of all token pairs
- Use Dijkstra's to find shortest path
- Consider fees in path cost
- Return optimal route

##### 3.3 Multi-Hop Execution
**Contract:** `Router.daml`

```daml
choice ExecuteMultiHop : ContractId Token
  with
    pools : [ContractId Pool]
    tokenCids : [ContractId Token]  -- Intermediate tokens
  controller trader
  do
    -- Execute swap 1: USDC → ETH
    eth <- swapInPool pools[0] ...
    -- Execute swap 2: ETH → BTC
    btc <- swapInPool pools[1] ...
    return btc
```

#### Tests to Add
- `testTwoHopRoute` - USDC → ETH → BTC
- `testThreeHopRoute` - USDC → ETH → WBTC → BTC
- `testRouteFailure` - No path exists
- `testOptimalRoute` - Choose cheapest path
- `testMultiHopSlippage` - Cumulative slippage protection

**Estimated Time:** 2-3 weeks
**Files to Create:**
- `daml/Router/Router.daml`
- `daml/Router/PathFinder.daml`
- `daml/TestRouter.daml`

---

### 🔨 Phase 4: Price Oracles (1-2 weeks)
**Priority:** MEDIUM - Required for advanced features
**Complexity:** MEDIUM

#### Features to Implement

##### 4.1 TWAP (Time-Weighted Average Price)
**Contract:** `Oracle/PriceOracle.daml`

```daml
template PriceOracle
  with
    poolCid : ContractId Pool
    observations : [(Time, Numeric 10, Numeric 10)]  -- (time, priceA, priceB)
    windowSize : RelTime  -- e.g., 1 hour
  where
    signatory pool.poolOperator

    choice RecordPrice : ContractId PriceOracle
      controller pool.poolOperator
      do
        now <- getTime
        currentPrice <- getCurrentPrice poolCid
        -- Add observation, remove old ones outside window
```

**TWAP Formula:**
```
TWAP = Σ(price_i * time_i) / Σ(time_i)

where:
  price_i = price at observation i
  time_i = duration of observation i
```

##### 4.2 Price Feed
**Contract:** `Oracle/PriceFeed.daml`

```daml
template PriceFeed
  with
    operator : Party
    symbol : Text
    price : Numeric 10
    updatedAt : Time
    source : Text  -- "POOL" or "EXTERNAL"
```

**Features:**
- Pool-based prices (from reserves)
- External oracle integration (optional)
- Price staleness checks
- Multiple price sources

##### 4.3 Price Impact Protection
**Enhancement:** Update `SwapRequest.daml`

```daml
template SwapRequest
  with
    -- Existing fields...
    maxPriceImpactBps : Int         -- Already exists!
    oracleCid : Optional (ContractId PriceOracle)  -- NEW

  choice ExecuteSwap
    do
      -- If oracle exists, verify price is within range
      case oracleCid of
        Some oracle -> do
          oraclePrice <- getOraclePrice oracle
          poolPrice <- getCurrentPoolPrice
          assertMsg "Price deviation too high"
            (abs(poolPrice - oraclePrice) / oraclePrice < 0.05)  -- 5%
        None -> return ()
```

#### Tests to Add
- `testTWAPCalculation` - Verify TWAP over time window
- `testPriceDeviation` - Detect manipulation
- `testStalePriceRejection` - Reject outdated prices
- `testOracleIntegration` - Use oracle in swap

**Estimated Time:** 1-2 weeks
**Files to Create:**
- `daml/Oracle/PriceOracle.daml`
- `daml/Oracle/PriceFeed.daml`
- `daml/TestOracle.daml`

---

### 🔨 Phase 5: Advanced Features (2-3 weeks)
**Priority:** LOW - Nice to have
**Complexity:** VARIES

#### 5.1 Flash Swaps
**Feature:** Borrow tokens, use them, repay in same transaction

```daml
template FlashSwapRequest
  with
    borrower : Party
    poolCid : ContractId Pool
    borrowAmount : Numeric 10
    borrowToken : Text
    repaymentCid : ContractId Token  -- Must include fee
```

**Use Cases:**
- Arbitrage
- Liquidations
- Collateral swaps

#### 5.2 Limit Orders
**Feature:** Execute swap only at specific price

```daml
template LimitOrder
  with
    trader : Party
    tokenIn : Text
    tokenOut : Text
    amountIn : Numeric 10
    limitPrice : Numeric 10  -- Execute only if price <= this
    expiry : Time
```

**Logic:**
- Order sits waiting
- Anyone can execute if price condition met
- Executor gets small fee

#### 5.3 Governance
**Feature:** LP token holders vote on parameters

```daml
template GovernanceProposal
  with
    proposer : Party
    description : Text
    newFeeBps : Int
    votingPower : [(Party, Numeric 10)]  -- LP token amounts
    votesFor : Numeric 10
    votesAgainst : Numeric 10
    deadline : Time
```

**Parameters to Govern:**
- Fee percentage
- Fee recipient
- Max price impact
- Oracle parameters

#### 5.4 Fee Distribution
**Feature:** Share fees with LP token holders

```daml
choice ClaimFees : ContractId Token
  with
    lpTokenCid : ContractId LPToken
  controller lpToken.owner
  do
    -- Calculate fees earned
    fees = (lpAmount / totalSupply) * accumulatedFees
    -- Transfer fees to LP
```

**Estimated Time:** 2-3 weeks total
**Files to Create:**
- `daml/Advanced/FlashSwap.daml`
- `daml/Advanced/LimitOrder.daml`
- `daml/Governance/Proposal.daml`
- `daml/TestAdvanced.daml`

---

## 📊 Complete Implementation Timeline

### Sprint 1 (Week 1-2): Liquidity Provision
- Day 1-2: Design LP token system
- Day 3-5: Implement AddLiquidity
- Day 6-8: Implement RemoveLiquidity
- Day 9-10: Testing & fixes
- **Deliverable:** Users can add/remove liquidity, earn fees

### Sprint 2 (Week 3-4): Multi-Pool
- Day 1-3: PoolFactory & Registry
- Day 4-6: Enhanced Pool with LP tracking
- Day 7-10: Testing multiple pools
- **Deliverable:** Support ETH/USDC, ETH/DAI, USDC/DAI, etc.

### Sprint 3 (Week 5-7): Multi-Hop Routing
- Day 1-5: Path finding algorithm
- Day 6-10: Router implementation
- Day 11-15: Testing complex routes
- **Deliverable:** Swap any token to any token

### Sprint 4 (Week 8-9): Price Oracles
- Day 1-5: TWAP implementation
- Day 6-10: Oracle integration
- **Deliverable:** Price manipulation protection

### Sprint 5 (Week 10-12): Advanced Features
- Day 1-5: Flash swaps
- Day 6-10: Limit orders
- Day 11-15: Governance
- **Deliverable:** Feature-complete DEX

**Total Timeline:** ~12 weeks (3 months) for complete DEX

---

## 🎯 Recommended Approach: Start with Phase 1

### Why Start with Liquidity Provision?

1. **Most Critical Feature**
   - DEX is useless without liquidity
   - Current implementation assumes pre-funded pools
   - Real users need to add/remove liquidity

2. **Foundation for Everything Else**
   - LP tokens needed for governance
   - Fee distribution requires LP tracking
   - Multi-pool needs liquidity management

3. **Testable Independently**
   - Can test without other features
   - Clear success criteria
   - Relatively simple scope

### Phase 1 Detailed Plan

#### Step 1: LP Token Implementation (Day 1-2)

**File:** `daml/LPToken/LPToken.daml`

```daml
module LPToken.LPToken where

template LPToken
  with
    poolId : Text       -- "ETH-USDC"
    issuer : Party      -- Pool party
    owner : Party       -- LP provider
    amount : Numeric 10
  where
    signatory issuer
    observer owner

    nonconsuming choice Transfer : ContractId LPToken
      with
        recipient : Party
        qty : Numeric 10
      controller owner
      do
        -- Similar to Token.Transfer
        when (qty < amount) $
          void $ create this with owner = owner, amount = amount - qty
        newToken <- create this with owner = recipient, amount = qty
        archive self
        return newToken

    choice Burn : ()
      with qty : Numeric 10
      controller owner
      do
        assertMsg "Positive burn" (qty > 0.0)
        assertMsg "Sufficient balance" (qty <= amount)

        when (qty < amount) $
          void $ create this with amount = amount - qty

        archive self
```

**Test:** `testLPTokenTransfer`, `testLPTokenBurn`

#### Step 2: Update Pool (Day 2-3)

**File:** `daml/AMM/Pool.daml`

Add fields:
```daml
template Pool
  with
    -- Existing...
    totalLPSupply : Numeric 10
    lpTokenIssuer : Party  -- Who can mint LP tokens (poolParty)
```

Add choices:
```daml
    nonconsuming choice GetLPSupply : Numeric 10
      controller poolParty
      do return totalLPSupply
```

#### Step 3: Add Liquidity (Day 3-6)

**File:** `daml/AMM/LiquidityRequest.daml`

```daml
-- Full implementation with tests
```

**Test Cases:**
- First liquidity provider (sqrt formula)
- Subsequent providers (proportional formula)
- Slippage protection
- Minimum liquidity requirement
- Unbalanced deposits

#### Step 4: Remove Liquidity (Day 6-8)

**File:** `daml/AMM/LiquidityRequest.daml`

```daml
template RemoveLiquidityRequest
  -- Full implementation
```

**Test Cases:**
- Remove all liquidity
- Remove partial liquidity
- Verify proportional token return
- Slippage protection on removal

#### Step 5: Integration Tests (Day 9-10)

**File:** `daml/TestLiquidityFlow.daml`

Test full lifecycle:
1. User adds liquidity → gets LP tokens
2. Another user swaps → generates fees
3. Original user removes liquidity → gets more tokens (fees!)

---

## 📁 Final File Structure

```
clearportx/
├── daml/
│   ├── Token/
│   │   └── Token.daml              ✅ DONE
│   ├── LPToken/
│   │   └── LPToken.daml            🔨 Phase 1
│   ├── AMM/
│   │   ├── Pool.daml               ✅ DONE (will enhance)
│   │   ├── SwapRequest.daml        ✅ DONE
│   │   ├── LiquidityRequest.daml   🔨 Phase 1
│   │   ├── PoolFactory.daml        🔨 Phase 2
│   │   └── PoolRegistry.daml       🔨 Phase 2
│   ├── Router/
│   │   ├── Router.daml             🔨 Phase 3
│   │   └── PathFinder.daml         🔨 Phase 3
│   ├── Oracle/
│   │   ├── PriceOracle.daml        🔨 Phase 4
│   │   └── PriceFeed.daml          🔨 Phase 4
│   ├── Advanced/
│   │   ├── FlashSwap.daml          🔨 Phase 5
│   │   └── LimitOrder.daml         🔨 Phase 5
│   ├── Governance/
│   │   └── Proposal.daml           🔨 Phase 5
│   └── Tests/
│       ├── TestSwapProposal.daml   ✅ DONE
│       ├── TestAMMEdgeCases.daml   ✅ DONE
│       ├── TestAMMMath.daml        ✅ DONE
│       ├── TestLiquidity.daml      🔨 Phase 1
│       ├── TestMultiPool.daml      🔨 Phase 2
│       ├── TestRouter.daml         🔨 Phase 3
│       ├── TestOracle.daml         🔨 Phase 4
│       └── TestAdvanced.daml       🔨 Phase 5
├── AMM-ARCHITECTURE.md             ✅ DONE
├── DEBUGGING-JOURNEY.md            ✅ DONE
├── QUICK-START.md                  ✅ DONE
├── TEST-REPORT.md                  ✅ DONE
└── DEX-IMPLEMENTATION-PLAN.md      ✅ THIS FILE
```

---

## 🚀 Next Steps (Your Decision)

### Option A: Start Phase 1 NOW
**Pros:**
- Build momentum
- Most critical feature
- Relatively straightforward
- Clear deliverable

**Timeline:** 10 days to working liquidity provision

### Option B: Deep Dive on Architecture First
**Pros:**
- Ensure all phases work together
- Identify dependencies early
- Better overall design

**Timeline:** 2-3 days planning, then start Phase 1

### Option C: Minimal Testnet Deploy
**Pros:**
- Validate current architecture on real network
- Get Canton Network experience
- Identify infrastructure issues

**Timeline:** 1-2 days setup, current features only

---

## 💡 My Recommendation

**Start Phase 1 (Liquidity Provision) immediately because:**

1. Current implementation is complete and tested
2. Liquidity is THE most critical missing feature
3. You can test it locally before testnet
4. It's independent - doesn't require other phases
5. Building momentum keeps motivation high
6. You'll learn DAML patterns that help with later phases

**After Phase 1:** Deploy to testnet with:
- ✅ Token swaps (working)
- ✅ Add/remove liquidity (Phase 1)
- ✅ LP tokens (Phase 1)

This gives you a **minimal viable DEX** for real-world testing!

---

## 📝 Summary

- ✅ **Phase 0:** Complete (swaps working, 12/12 tests)
- 🔨 **Phase 1:** Liquidity provision (NEXT - 10 days)
- 🔨 **Phase 2:** Multi-pool (1-2 weeks)
- 🔨 **Phase 3:** Multi-hop routing (2-3 weeks)
- 🔨 **Phase 4:** Price oracles (1-2 weeks)
- 🔨 **Phase 5:** Advanced features (2-3 weeks)

**Total:** ~12 weeks to feature-complete DEX

**What do you want to do?**
- Start Phase 1 (liquidity provision)?
- Review the plan first?
- Deploy current version to testnet?
- Something else?
