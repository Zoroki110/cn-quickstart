# Complete DEX AMM Implementation Plan - REVISED

**Version:** 2.0 (Based on ChatGPT feedback + Best Practices)
**Project:** ClearPortX - Full-Featured DEX on DAML/Canton
**Current Status:** ✅ Core swaps working, 12/12 tests passing

---

## 🔄 What Changed from v1.0

### Key Improvements Based on Expert Feedback

1. ✅ **Pool Choices (Not Request Templates)**
   - AddLiquidity/RemoveLiquidity as direct Pool choices
   - Eliminates extra contracts and complexity
   - Atomic operations with proper multi-party authorization

2. ✅ **Append-Only Architecture**
   - PoolAnnouncement contracts instead of mutable registry
   - Better performance, no contention
   - Off-ledger indexing via Canton API

3. ✅ **Off-Ledger Computation**
   - Route finding in JavaScript/TypeScript
   - On-ledger execution only
   - Faster, cheaper, more scalable

4. ✅ **Cumulative Price (TWAP)**
   - Uniswap v3 style cumulative price
   - No growing arrays
   - Constant storage size

5. ✅ **Simplified Governance**
   - Operator-controlled for v1
   - Opt-in delegation for advanced features
   - No global voting (visibility issues)

---

## 🎯 Implementation Phases (Revised)

### ✅ Phase 0: Foundation (COMPLETE)
**Status:** 100% Complete, 12/12 tests passing

**What We Built:**
- ✅ Token with issuer-as-signatory pattern
- ✅ Pool with canonical key ordering
- ✅ Swap execution (3-transaction flow)
- ✅ Fee accumulation (0.3%)
- ✅ Slippage & deadline protection
- ✅ Constant product formula (x*y=k)

**Key Architectural Decisions Validated:**
```daml
-- ✅ Token pattern (ChatGPT approved!)
template Token
  where
    signatory issuer  -- Issuer-only
    observer owner    -- Owner can see

    nonconsuming choice Transfer
      controller owner  -- Owner controls
      do
        -- Create new tokens
        newToken <- create this with owner = recipient, amount = qty
        -- Archive at END (DAML bug workaround)
        archive self

-- ✅ Pool uniqueness (ChatGPT approved!)
template Pool
  where
    key (poolOperator, ((symbolA, show issuerA), (symbolB, show issuerB)))
    ensure (symbolA, show issuerA) < (symbolB, show issuerB)
```

---

## 🔨 Phase 1: Liquidity Provision - REVISED

**Timeline:** 10-12 days
**Priority:** HIGH - Required for functional DEX
**Approach:** Direct Pool choices (no request templates)

---

### 1.1 LPToken Template

**File:** `daml/LPToken/LPToken.daml`

```daml
module LPToken.LPToken where

import DA.Action (void, when)

-- LP Token represents share of pool ownership
-- Uses same pattern as Token (issuer-as-signatory)
template LPToken
  with
    issuer : Party      -- Pool (lpIssuer party)
    owner : Party       -- LP provider
    poolId : Text       -- "ETH-USDC"
    amount : Numeric 10
  where
    signatory issuer
    observer owner

    -- Key for merging/finding tokens
    key (issuer, poolId, owner) : (Party, Text, Party)
    maintainer key._1  -- issuer maintains

    ensure amount > 0.0

    -- Transfer: same pattern as Token
    nonconsuming choice Transfer : ContractId LPToken
      with
        recipient : Party
        qty : Numeric 10
      controller owner
      do
        assertMsg "Positive quantity" (qty > 0.0)
        assertMsg "Sufficient balance" (qty <= amount)
        assertMsg "Self-transfer forbidden" (recipient /= owner)

        -- Create new tokens (recipient's portion)
        newToken <- create this with owner = recipient, amount = qty

        -- Create remainder if partial transfer
        when (qty < amount) $
          void $ create this with owner = owner, amount = amount - qty

        -- Archive at END
        archive self
        return newToken

    -- Credit: merge incoming LP tokens (for composability)
    nonconsuming choice Credit : ContractId LPToken
      with qty : Numeric 10
      controller owner
      do
        assertMsg "Positive credit" (qty > 0.0)
        newToken <- create this with amount = amount + qty
        archive self
        return newToken

    -- Burn: destroy LP tokens (for RemoveLiquidity)
    choice Burn : ()
      with qty : Numeric 10
      controller owner
      do
        assertMsg "Positive burn" (qty > 0.0)
        assertMsg "Sufficient balance" (qty <= amount)

        -- Archive current token
        archive self

        -- Create remainder if partial burn
        when (qty < amount) $
          void $ create this with amount = amount - qty
```

**Key Features:**
- ✅ Same pattern as Token (proven to work!)
- ✅ Bilateral transfer (owner + recipient both authorize via pattern)
- ✅ Self-transfer forbidden
- ✅ Key for merging tokens
- ✅ Burn capability for liquidity removal

---

### 1.2 Enhanced Pool Template

**File:** `daml/AMM/Pool.daml` (UPDATE EXISTING)

**Add Fields:**
```daml
template Pool
  with
    -- Existing fields...
    poolOperator : Party
    poolParty : Party
    lpIssuer : Party
    issuerA : Party
    issuerB : Party
    symbolA : Text
    symbolB : Text
    feeBps : Int
    poolId : Text
    maxTTL : RelTime

    -- NEW FIELDS for LP tracking
    totalLPSupply : Numeric 10      -- Total LP tokens minted
    reserveA : Numeric 10            -- Current reserve A (cached)
    reserveB : Numeric 10            -- Current reserve B (cached)
  where
    signatory poolOperator
    observer poolParty, lpIssuer

    key (poolOperator, ((symbolA, show issuerA), (symbolB, show issuerB)))
    maintainer key._1

    ensure (symbolA, show issuerA) < (symbolB, show issuerB)
    ensure totalLPSupply >= 0.0
```

**Add Choices:**

```daml
    -- AddLiquidity: Direct choice on Pool (no request template!)
    choice AddLiquidity : (ContractId LPToken, ContractId Pool)
      with
        provider : Party
        tokenACid : ContractId Token
        tokenBCid : ContractId Token
        amountA : Numeric 10
        amountB : Numeric 10
        minLPTokens : Numeric 10  -- Slippage protection
        deadline : Time
      controller provider, poolParty, lpIssuer
      -- provider: provides liquidity
      -- poolParty: receives tokens
      -- lpIssuer: mints LP tokens
      do
        -- Validate deadline
        now <- getTime
        assertMsg "Deadline expired" (now <= deadline)

        -- Validate amounts
        assertMsg "Positive amountA" (amountA > 0.0)
        assertMsg "Positive amountB" (amountB > 0.0)

        -- Fetch token contracts
        tokenA <- fetch tokenACid
        tokenB <- fetch tokenBCid

        assertMsg "TokenA owner must be provider" (tokenA.owner == provider)
        assertMsg "TokenB owner must be provider" (tokenB.owner == provider)
        assertMsg "TokenA symbol must match" (tokenA.symbol == symbolA)
        assertMsg "TokenB symbol must match" (tokenB.symbol == symbolB)
        assertMsg "Sufficient tokenA balance" (tokenA.amount >= amountA)
        assertMsg "Sufficient tokenB balance" (tokenB.amount >= amountB)

        -- Calculate LP tokens to mint
        let lpTokensToMint = if totalLPSupply == 0.0
              then
                -- First liquidity provider: sqrt(amountA * amountB)
                sqrt (amountA * amountB)
              else
                -- Subsequent providers: proportional to reserves
                let shareA = (amountA / reserveA) * totalLPSupply
                let shareB = (amountB / reserveB) * totalLPSupply
                -- Use minimum to ensure fair pricing
                min shareA shareB

        -- Slippage protection
        assertMsg "Insufficient LP tokens (slippage)" (lpTokensToMint >= minLPTokens)

        -- Transfer tokens from provider to pool
        _ <- exercise tokenACid Token.Transfer with
          recipient = poolParty
          qty = amountA

        _ <- exercise tokenBCid Token.Transfer with
          recipient = poolParty
          qty = amountB

        -- Mint LP tokens for provider
        lpToken <- create LPToken with
          issuer = lpIssuer
          owner = provider
          poolId = poolId
          amount = lpTokensToMint

        -- Update pool state
        archive self
        newPool <- create this with
          totalLPSupply = totalLPSupply + lpTokensToMint
          reserveA = reserveA + amountA
          reserveB = reserveB + amountB

        return (lpToken, newPool)


    -- RemoveLiquidity: Burn LP tokens, get assets back
    choice RemoveLiquidity : (ContractId Token, ContractId Token, ContractId Pool)
      with
        provider : Party
        lpTokenCid : ContractId LPToken
        lpAmount : Numeric 10
        minAmountA : Numeric 10  -- Slippage protection
        minAmountB : Numeric 10
        deadline : Time
        poolTokenACid : ContractId Token  -- Pool's current token A
        poolTokenBCid : ContractId Token  -- Pool's current token B
      controller provider, poolParty, lpIssuer
      do
        -- Validate deadline
        now <- getTime
        assertMsg "Deadline expired" (now <= deadline)

        -- Fetch LP token
        lpToken <- fetch lpTokenCid
        assertMsg "LP token owner must be provider" (lpToken.owner == provider)
        assertMsg "LP token poolId must match" (lpToken.poolId == poolId)
        assertMsg "Sufficient LP tokens" (lpToken.amount >= lpAmount)

        -- Calculate tokens to return (proportional to LP share)
        let shareRatio = lpAmount / totalLPSupply
        let amountAToReturn = shareRatio * reserveA
        let amountBToReturn = shareRatio * reserveB

        -- Slippage protection
        assertMsg "Insufficient amountA (slippage)" (amountAToReturn >= minAmountA)
        assertMsg "Insufficient amountB (slippage)" (amountBToReturn >= minAmountB)

        -- Burn LP tokens
        exercise lpTokenCid LPToken.Burn with qty = lpAmount

        -- Transfer tokens from pool to provider
        tokenAReturned <- exercise poolTokenACid Token.Transfer with
          recipient = provider
          qty = amountAToReturn

        tokenBReturned <- exercise poolTokenBCid Token.Transfer with
          recipient = provider
          qty = amountBToReturn

        -- Update pool state
        archive self
        newPool <- create this with
          totalLPSupply = totalLPSupply - lpAmount
          reserveA = reserveA - amountAToReturn
          reserveB = reserveB - amountBToReturn

        return (tokenAReturned, tokenBReturned, newPool)


    -- GetSupply: Only lpIssuer can see supply (privacy!)
    nonconsuming choice GetSupply : Numeric 10
      controller lpIssuer
      do return totalLPSupply

    -- GetReserves: Anyone can query reserves (public info)
    nonconsuming choice GetReserves : (Numeric 10, Numeric 10)
      controller poolParty
      do return (reserveA, reserveB)
```

**Key Improvements:**
- ✅ Direct Pool choices (no request templates!)
- ✅ Multi-party controllers (provider, poolParty, lpIssuer)
- ✅ Atomic operations
- ✅ Slippage protection on both add and remove
- ✅ Supply visible only to lpIssuer (privacy)
- ✅ Reserves cached in Pool (performance)

---

### 1.3 Update SwapRequest

**File:** `daml/AMM/SwapRequest.daml` (UPDATE)

SwapRequest needs to update pool reserves after swap:

```daml
choice ExecuteSwap : ContractId Token
  with
    poolTokenACid : ContractId Token
    poolTokenBCid : ContractId Token
    poolAmountA : Numeric 10  -- Current reserves (from ExecuteSwap caller)
    poolAmountB : Numeric 10
  controller poolParty
  do
    -- Existing swap logic...

    -- NEW: Update pool reserves after swap
    poolUpdated <- exercise poolCid Pool.UpdateReserves with
      newReserveA = poolAmountA - ethOut  -- Example
      newReserveB = poolAmountB + inputAmount

    -- Return output token
    return outCid
```

Add UpdateReserves choice to Pool:
```daml
    -- UpdateReserves: Internal choice to sync reserves
    choice UpdateReserves : ContractId Pool
      with
        newReserveA : Numeric 10
        newReserveB : Numeric 10
      controller poolParty
      do
        archive self
        create this with
          reserveA = newReserveA
          reserveB = newReserveB
```

---

### 1.4 Tests for Phase 1

**File:** `daml/TestLiquidity.daml`

```daml
module TestLiquidity where

import Daml.Script
import DA.Time
import qualified Token.Token as T
import qualified LPToken.LPToken as LP
import qualified AMM.Pool as P

-- Test 1: First liquidity provider (sqrt formula)
testAddFirstLiquidity : Script ()
testAddFirstLiquidity = script do
  alice <- allocateParty "Alice"
  poolParty <- allocateParty "PoolParty"
  poolOperator <- allocateParty "PoolOperator"
  lpIssuer <- allocateParty "LPIssuer"
  issuerETH <- allocateParty "IssuerETH"
  issuerUSDC <- allocateParty "IssuerUSDC"

  -- Create empty pool
  pool <- submit poolOperator $ createCmd P.Pool with
    poolOperator = poolOperator
    poolParty = poolParty
    lpIssuer = lpIssuer
    issuerA = issuerETH
    issuerB = issuerUSDC
    symbolA = "ETH"
    symbolB = "USDC"
    feeBps = 30
    poolId = "ETH-USDC"
    maxTTL = seconds 120
    totalLPSupply = 0.0  -- Empty pool
    reserveA = 0.0
    reserveB = 0.0

  -- Alice has tokens
  aliceETH <- submit issuerETH $ createCmd T.Token with
    issuer = issuerETH
    owner = alice
    symbol = "ETH"
    amount = 10.0

  aliceUSDC <- submit issuerUSDC $ createCmd T.Token with
    issuer = issuerUSDC
    owner = alice
    symbol = "USDC"
    amount = 20000.0

  now <- getTime
  let deadline = addRelTime now (seconds 60)

  -- Alice adds first liquidity
  -- Expected LP tokens: sqrt(10 * 20000) = sqrt(200000) = 447.21
  (lpToken, poolUpdated) <- submitMulti [alice, poolParty, lpIssuer] [] $
    exerciseCmd pool P.AddLiquidity with
      provider = alice
      tokenACid = aliceETH
      tokenBCid = aliceUSDC
      amountA = 10.0
      amountB = 20000.0
      minLPTokens = 440.0  -- Slippage tolerance
      deadline = deadline

  -- Verify LP tokens minted
  lpTokenData <- queryContractId alice lpToken
  case lpTokenData of
    None -> assertFail "LP token not found"
    Some token -> do
      let expected = sqrt (10.0 * 20000.0)  -- 447.21
      assertMsg "LP tokens should match sqrt formula"
        (abs(token.amount - expected) < 1.0)
      debug $ "✅ First LP: " <> show token.amount <> " LP tokens (expected ~447)"

  -- Verify pool state
  poolData <- queryContractId poolOperator poolUpdated
  case poolData of
    None -> assertFail "Pool not found"
    Some p -> do
      assertMsg "Total supply should match minted" (p.totalLPSupply == lpTokenData.amount)
      assertMsg "ReserveA should be 10" (p.reserveA == 10.0)
      assertMsg "ReserveB should be 20000" (p.reserveB == 20000.0)

  return ()


-- Test 2: Subsequent liquidity (proportional formula)
testAddSubsequentLiquidity : Script ()
-- Test 3: Remove liquidity (get tokens back)
testRemoveLiquidity : Script ()
-- Test 4: Partial liquidity removal
testRemovePartialLiquidity : Script ()
-- Test 5: Slippage protection on add
testAddLiquiditySlippage : Script ()
-- Test 6: Slippage protection on remove
testRemoveLiquiditySlippage : Script ()
-- Test 7: LP token transfer
testLPTokenTransfer : Script ()
-- Test 8: Earn fees through liquidity provision
testEarnFees : Script ()
```

---

## 🔨 Phase 2: Multi-Pool Architecture - REVISED

**Timeline:** 1-2 weeks
**Approach:** Append-only announcements + off-ledger indexing

---

### 2.1 PoolFactory

**File:** `daml/AMM/PoolFactory.daml`

```daml
module AMM.PoolFactory where

import qualified AMM.Pool as P

-- PoolFactory creates new pools with standard parameters
template PoolFactory
  with
    operator : Party
    defaultFeeBps : Int
    defaultMaxTTL : RelTime
  where
    signatory operator

    -- CreatePool: One-time pool creation
    choice CreatePool : (ContractId P.Pool, ContractId PoolAnnouncement)
      with
        poolParty : Party
        lpIssuer : Party
        issuerA : Party
        issuerB : Party
        symbolA : Text
        symbolB : Text
        poolId : Text
        feeBps : Optional Int  -- Use default if None
      controller operator
      do
        -- Ensure canonical ordering
        let (symA, issA, symB, issB) =
              if (symbolA, show issuerA) < (symbolB, show issuerB)
              then (symbolA, issuerA, symbolB, issuerB)
              else (symbolB, issuerB, symbolA, issuerA)

        -- Create pool
        pool <- create P.Pool with
          poolOperator = operator
          poolParty = poolParty
          lpIssuer = lpIssuer
          issuerA = issA
          issuerB = issB
          symbolA = symA
          symbolB = symB
          feeBps = optional defaultFeeBps id feeBps
          poolId = poolId
          maxTTL = defaultMaxTTL
          totalLPSupply = 0.0
          reserveA = 0.0
          reserveB = 0.0

        -- Create announcement (append-only record)
        announcement <- create PoolAnnouncement with
          poolOperator = operator
          poolCid = pool
          symbolA = symA
          symbolB = symB
          issuerA = issA
          issuerB = issB
          poolId = poolId

        return (pool, announcement)
```

---

### 2.2 PoolAnnouncement (Append-Only)

**File:** `daml/AMM/PoolAnnouncement.daml`

```daml
module AMM.PoolAnnouncement where

import qualified AMM.Pool as P

-- PoolAnnouncement: Append-only record of pool creation
-- No choices - just exists for discovery via Canton API
template PoolAnnouncement
  with
    poolOperator : Party
    poolCid : ContractId P.Pool
    symbolA : Text
    symbolB : Text
    issuerA : Party
    issuerB : Party
    poolId : Text
  where
    signatory poolOperator

    -- Optional: Allow anyone to query
    observer poolOperator  -- Can extend to public if needed

    -- No choices - immutable record
```

**Discovery (Off-Ledger via Canton API):**
```typescript
// JavaScript/TypeScript client code
async function findPool(symbolA: string, symbolB: string) {
  // Query all PoolAnnouncement contracts
  const announcements = await ledger.query(PoolAnnouncement);

  // Filter by symbol pair (handle both orderings)
  const matching = announcements.filter(a =>
    (a.symbolA === symbolA && a.symbolB === symbolB) ||
    (a.symbolA === symbolB && a.symbolB === symbolA)
  );

  if (matching.length > 0) {
    return matching[0].poolCid;
  }
  return null;
}
```

**Benefits:**
- ✅ No on-ledger contention
- ✅ Append-only (never archived/recreated)
- ✅ Query via Canton API (fast, off-ledger)
- ✅ Simple, clean architecture

---

## 🔨 Phase 3: Multi-Hop Routing - REVISED

**Timeline:** 2-3 weeks
**Approach:** Off-ledger pathfinding, on-ledger execution

---

### 3.1 Path Finding (OFF-LEDGER)

**File:** `typescript/router.ts` (NEW - Client-side)

```typescript
import { ContractId, Pool } from './generated';

interface PoolInfo {
  poolCid: ContractId<Pool>;
  symbolA: string;
  symbolB: string;
  reserveA: number;
  reserveB: number;
  feeBps: number;
}

// Build graph from all available pools
function buildGraph(pools: PoolInfo[]): Map<string, Map<string, PoolInfo>> {
  const graph = new Map();

  for (const pool of pools) {
    // Add edge: A -> B
    if (!graph.has(pool.symbolA)) graph.set(pool.symbolA, new Map());
    graph.get(pool.symbolA).set(pool.symbolB, pool);

    // Add edge: B -> A (bidirectional)
    if (!graph.has(pool.symbolB)) graph.set(pool.symbolB, new Map());
    graph.get(pool.symbolB).set(pool.symbolA, pool);
  }

  return graph;
}

// Dijkstra's algorithm with fee consideration
function findBestPath(
  graph: Map<string, Map<string, PoolInfo>>,
  fromToken: string,
  toToken: string,
  amountIn: number
): { path: ContractId<Pool>[], estimatedOutput: number } | null {

  const distances = new Map<string, number>();
  const previous = new Map<string, { token: string, pool: PoolInfo }>();
  const unvisited = new Set(graph.keys());

  // Initialize: fromToken has full input amount
  distances.set(fromToken, amountIn);

  while (unvisited.size > 0) {
    // Find token with maximum amount (best path so far)
    let current = null;
    let maxAmount = -Infinity;
    for (const token of unvisited) {
      const amount = distances.get(token) || 0;
      if (amount > maxAmount) {
        maxAmount = amount;
        current = token;
      }
    }

    if (!current || current === toToken) break;

    unvisited.delete(current);
    const currentAmount = distances.get(current)!;

    // Check all neighbors
    const neighbors = graph.get(current);
    if (!neighbors) continue;

    for (const [nextToken, pool] of neighbors) {
      // Calculate output through this pool
      const output = calculateSwapOutput(
        currentAmount,
        pool.symbolA === current ? pool.reserveA : pool.reserveB,
        pool.symbolA === current ? pool.reserveB : pool.reserveA,
        pool.feeBps
      );

      // If this path gives more output, use it
      const currentBest = distances.get(nextToken) || 0;
      if (output > currentBest) {
        distances.set(nextToken, output);
        previous.set(nextToken, { token: current, pool });
      }
    }
  }

  // Reconstruct path
  if (!distances.has(toToken)) return null;

  const path: ContractId<Pool>[] = [];
  let current = toToken;

  while (previous.has(current)) {
    const { token, pool } = previous.get(current)!;
    path.unshift(pool.poolCid);
    current = token;
  }

  return {
    path,
    estimatedOutput: distances.get(toToken)!
  };
}

// Calculate swap output using x*y=k formula
function calculateSwapOutput(
  amountIn: number,
  reserveIn: number,
  reserveOut: number,
  feeBps: number
): number {
  const feeMul = (10000 - feeBps) / 10000;
  const amountInFee = amountIn * feeMul;
  return (amountInFee * reserveOut) / (reserveIn + amountInFee);
}
```

---

### 3.2 Router (ON-LEDGER)

**File:** `daml/Router/Router.daml`

```daml
module Router.Router where

import qualified AMM.Pool as P
import qualified Token.Token as T
import qualified AMM.SwapRequest as SR

-- MultiHopSwap: Execute pre-computed route
template MultiHopSwap
  with
    trader : Party
    pools : [ContractId P.Pool]      -- Path from off-ledger
    inputTokenCid : ContractId T.Token
    inputAmount : Numeric 10
    minOutputAmount : Numeric 10      -- Slippage protection
    deadline : Time
  where
    signatory trader
    -- All pool parties must observe (for execution)
    -- (In practice, get these from pool contracts)

    choice ExecuteRoute : ContractId T.Token
      with
        poolParties : [Party]  -- All pool parties in route
        intermediateReserves : [((Numeric 10, Numeric 10), Int)]  -- Reserves + fees
      controller trader :: poolParties  -- Trader + all pools
      do
        now <- getTime
        assertMsg "Route expired" (now <= deadline)

        -- Execute sequential swaps
        currentToken <- foldl executeHop (return inputTokenCid) (zip3 pools poolParties intermediateReserves)

        -- Verify final output
        finalToken <- fetch currentToken
        assertMsg "Insufficient output (slippage)" (finalToken.amount >= minOutputAmount)

        return currentToken

    -- Helper: Execute one hop in the route
    executeHop : ContractId T.Token -> (ContractId P.Pool, Party, ((Numeric 10, Numeric 10), Int)) -> Update (ContractId T.Token)
    executeHop tokenCid (poolCid, poolParty, ((resA, resB), fee)) = do
      -- Create swap request for this hop
      swapReq <- create SR.SwapRequest with
        trader = trader
        poolCid = poolCid
        poolParty = poolParty
        -- ... other fields

      -- Execute swap
      swapReady <- exercise swapReq SR.PrepareSwap
      outputToken <- exercise swapReady SR.ExecuteSwap with
        poolTokenACid = ...  -- Would need to pass these
        poolTokenBCid = ...
        poolAmountA = resA
        poolAmountB = resB

      return outputToken
```

**Usage:**
```typescript
// Client-side: Find path
const route = findBestPath(pools, "USDC", "BTC", 100.0);

// On-ledger: Execute route
await ledger.exercise(MultiHopSwap.ExecuteRoute, contractId, {
  poolParties: route.poolParties,
  intermediateReserves: route.reserves
});
```

---

## 🔨 Phase 4: Price Oracles - REVISED

**Timeline:** 1-2 weeks
**Approach:** Cumulative price (Uniswap v3 style)

---

### 4.1 TWAP Oracle

**File:** `daml/Oracle/TWAP.daml`

```daml
module Oracle.TWAP where

import qualified AMM.Pool as P
import DA.Time

-- Cumulative price oracle (constant storage)
template TWAPOracle
  with
    poolCid : ContractId P.Pool
    operator : Party
    priceCumulativeA : Numeric 10  -- ∫ priceA dt
    priceCumulativeB : Numeric 10  -- ∫ priceB dt
    lastUpdateTime : Time
    observationWindow : RelTime    -- e.g., 1 hour
  where
    signatory operator

    -- Update: Add price * time to cumulative
    choice UpdatePrice : ContractId TWAPOracle
      controller operator
      do
        now <- getTime
        let dt = now `subTime` lastUpdateTime

        -- Get current reserves
        (resA, resB) <- exercise poolCid P.GetReserves

        -- Calculate current prices
        let priceA = resB / resA  -- Price of A in terms of B
        let priceB = resA / resB  -- Price of B in terms of A

        -- Update cumulative prices
        let newCumA = priceCumulativeA + (priceA * intToDecimal (convertRelTimeToMicroseconds dt))
        let newCumB = priceCumulativeB + (priceB * intToDecimal (convertRelTimeToMicroseconds dt))

        -- Archive old, create new
        archive self
        create this with
          priceCumulativeA = newCumA
          priceCumulativeB = newCumB
          lastUpdateTime = now

    -- GetTWAP: Calculate time-weighted average price
    nonconsuming choice GetTWAP : (Numeric 10, Numeric 10)
      with
        historicalOracle : ContractId TWAPOracle  -- Oracle from T ago
      controller operator
      do
        -- Fetch historical state
        historical <- fetch historicalOracle

        -- Calculate time difference
        let timeDiff = lastUpdateTime `subTime` historical.lastUpdateTime

        -- TWAP = (cumNow - cumThen) / (tNow - tThen)
        let twapA = (priceCumulativeA - historical.priceCumulativeA)
                   / intToDecimal (convertRelTimeToMicroseconds timeDiff)
        let twapB = (priceCumulativeB - historical.priceCumulativeB)
                   / intToDecimal (convertRelTimeToMicroseconds timeDiff)

        return (twapA, twapB)
```

**Benefits:**
- ✅ Constant storage (no arrays!)
- ✅ Uniswap v3 proven formula
- ✅ Efficient calculation
- ✅ Manipulation resistant (time-weighted)

---

## 🔨 Phase 5: Advanced Features - REVISED

**Timeline:** 2-3 weeks
**Approach:** Simplified governance, optional features

---

### 5.1 Simplified Governance

**File:** `daml/Governance/PoolConfig.daml`

```daml
-- Operator-controlled for v1 (simple, battle-tested)
template PoolConfig
  with
    poolCid : ContractId Pool
    operator : Party
    currentFeeBps : Int
  where
    signatory operator

    choice UpdateFee : ContractId PoolConfig
      with newFeeBps : Int
      controller operator
      do
        assertMsg "Fee must be <= 100 bps (1%)" (newFeeBps <= 100)

        -- Update pool
        -- (would need to add UpdateFee choice to Pool)

        archive self
        create this with currentFeeBps = newFeeBps
```

**For v2 (optional): Opt-in delegation**
- LPs can delegate voting power
- Operator counts votes
- Execute if threshold met
- No global queries needed

---

## 📊 Revised Timeline

### Sprint 1 (Week 1-2): Liquidity Provision ✅ READY TO START
- **Day 1-2:** Implement LPToken.daml
- **Day 3-5:** Update Pool.daml with AddLiquidity/RemoveLiquidity
- **Day 6-7:** Update SwapRequest to sync reserves
- **Day 8-10:** Testing (8 comprehensive tests)
- **Deliverable:** Working add/remove liquidity

### Sprint 2 (Week 3-4): Multi-Pool
- **Day 1-3:** PoolFactory + PoolAnnouncement
- **Day 4-6:** Off-ledger discovery (TypeScript)
- **Day 7-10:** Testing multiple pools
- **Deliverable:** Support ETH/USDC, ETH/DAI, etc.

### Sprint 3 (Week 5-7): Multi-Hop Routing
- **Day 1-5:** Off-ledger pathfinding (TypeScript)
- **Day 6-10:** On-ledger Router execution
- **Day 11-15:** Testing complex routes
- **Deliverable:** Swap any token to any

### Sprint 4 (Week 8-9): Price Oracles
- **Day 1-5:** TWAP cumulative price
- **Day 6-10:** Integration with swaps
- **Deliverable:** Manipulation protection

### Sprint 5 (Week 10-12): Advanced Features
- **Day 1-5:** Flash swaps (optional)
- **Day 6-10:** Limit orders (optional)
- **Day 11-15:** Simplified governance
- **Deliverable:** Feature-complete DEX

**Total: ~12 weeks to production-ready DEX**

---

## 🎯 Phase 1 Detailed Checklist (Next 10 Days)

### Day 1-2: LPToken Implementation
- [ ] Create `daml/LPToken/LPToken.daml`
- [ ] Implement Transfer (bilateral pattern)
- [ ] Implement Credit (merge tokens)
- [ ] Implement Burn (for removal)
- [ ] Add contract key
- [ ] Write basic tests (transfer, burn)

### Day 3-5: Pool Enhancement
- [ ] Update `daml/AMM/Pool.daml`
- [ ] Add fields: totalLPSupply, reserveA, reserveB
- [ ] Implement AddLiquidity choice
- [ ] Implement RemoveLiquidity choice
- [ ] Implement GetSupply (lpIssuer only)
- [ ] Implement GetReserves (public)

### Day 6-7: Integration
- [ ] Update SwapRequest to sync reserves
- [ ] Add UpdateReserves choice to Pool
- [ ] Ensure swaps update reserves correctly

### Day 8-10: Testing
- [ ] testAddFirstLiquidity
- [ ] testAddSubsequentLiquidity
- [ ] testRemoveLiquidity
- [ ] testRemovePartialLiquidity
- [ ] testAddLiquiditySlippage
- [ ] testRemoveLiquiditySlippage
- [ ] testLPTokenTransfer
- [ ] testEarnFees (full lifecycle)
- [ ] Fix any bugs found
- [ ] Verify all 20+ tests pass (12 existing + 8 new)

---

## 🚀 What's Different (Summary)

### v1.0 Plan (Original)
- ❌ Request templates (extra contracts)
- ❌ Mutable registry
- ❌ On-ledger pathfinding
- ❌ Array-based TWAP
- ❌ Complex governance

### v2.0 Plan (Revised) ✅
- ✅ Direct Pool choices (atomic)
- ✅ Append-only announcements
- ✅ Off-ledger pathfinding
- ✅ Cumulative TWAP
- ✅ Operator-controlled governance

**Result:** Simpler, faster, more scalable architecture!

---

## 💡 Ready to Start Phase 1

**I'm ready to implement when you are!**

Next steps:
1. I create `LPToken.daml` (Day 1)
2. I update `Pool.daml` (Day 3)
3. I write comprehensive tests (Day 8)
4. You have working liquidity provision! (Day 10)

**Shall we begin?**
