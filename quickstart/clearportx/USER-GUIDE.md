# ClearPortX DEX - User Guide

## Overview

ClearPortX is a decentralized exchange (DEX) built on DAML using the Automated Market Maker (AMM) model, similar to Uniswap v2. This guide explains all the features available to users.

---

## 🎯 What Can Users Do?

ClearPortX allows users to:
1. **Swap Tokens** - Trade one token for another using liquidity pools
2. **Provide Liquidity** - Deposit token pairs to earn fees
3. **Remove Liquidity** - Withdraw your share of the pool
4. **Transfer LP Tokens** - Send your liquidity position to others

---

## 📊 DEX Architecture Map

```
┌─────────────────────────────────────────────────────────────┐
│                        USER ACTIONS                          │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
      ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
      │   SWAPPING   │ │  LIQUIDITY   │ │  LP TOKENS   │
      │              │ │  PROVISION   │ │              │
      └──────────────┘ └──────────────┘ └──────────────┘
              │               │               │
              │               │               │
              ▼               ▼               ▼
      ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
      │ SwapRequest  │ │     Pool     │ │   LPToken    │
      │   Template   │ │   Template   │ │   Template   │
      └──────────────┘ └──────────────┘ └──────────────┘
              │               │               │
              │               │               │
              ▼               ▼               ▼
        ┌─────────────────────────────────────────┐
        │          Token Template                 │
        │  (ETH, USDC, or other assets)          │
        └─────────────────────────────────────────┘
```

---

## 🔄 Feature 1: Token Swapping

### What It Does
Exchange one token for another at the current market price determined by the pool's reserves.

### Available Choices

#### 1. Create Swap Request
**Choice:** `SwapRequest` (template creation)
- **Who:** Any trader with tokens
- **Purpose:** Initiate a swap proposal
- **Required:**
  - Input token (what you're selling)
  - Output token symbol (what you want)
  - Amount to swap
  - Minimum output (slippage protection)
  - Deadline (transaction expiry)
  - Max price impact (safety limit)

#### 2. Prepare Swap
**Choice:** `PrepareSwap`
- **Who:** Trader (you)
- **Purpose:** Transfer your tokens to the pool escrow
- **What Happens:** Your input tokens are locked, ready for execution

#### 3. Execute Swap
**Choice:** `ExecuteSwap`
- **Who:** Pool operator
- **Purpose:** Complete the swap using AMM formula
- **What Happens:**
  - Calculates output based on `x * y = k` formula
  - Deducts 0.3% fee
  - Checks slippage protection
  - Verifies deadline hasn't passed
  - Transfers output tokens to you

#### 4. Cancel Swap Request
**Choice:** `CancelSwapRequest`
- **Who:** Trader (you)
- **Purpose:** Cancel before preparation if you change your mind

#### 5. Cancel Prepared Swap
**Choice:** `CancelPreparedSwap`
- **Who:** Trader (you)
- **Purpose:** Cancel and get your tokens back after preparation

### Protections
- ✅ **Slippage Protection**: Set minimum output to prevent unfavorable execution
- ✅ **Deadline Enforcement**: Transaction expires if not executed in time
- ✅ **Price Impact Limit**: Rejects swaps that move price too much
- ✅ **Fee Transparency**: Fixed 0.3% fee (30 basis points)

### Example Flow
```
1. Alice has 100 USDC, wants ETH
2. Alice creates SwapRequest:
   - Input: 100 USDC
   - Output: ETH
   - minOutput: 0.048 ETH (2% slippage tolerance)
   - deadline: 60 seconds from now
3. Alice calls PrepareSwap → 100 USDC locked
4. Pool operator calls ExecuteSwap
   - Calculates: Alice gets 0.0497 ETH (after 0.3% fee)
   - Checks: 0.0497 >= 0.048 ✓
   - Transfers: 0.0497 ETH to Alice
5. Complete! Alice now has ~0.05 ETH
```

---

## 💧 Feature 2: Liquidity Provision (Add Liquidity)

### What It Does
Deposit equal value of two tokens into a pool to earn trading fees. You receive LP tokens representing your share.

### Available Choices

#### AddLiquidity
**Choice:** `AddLiquidity` (on Pool)
- **Who:** Liquidity provider (you) + pool party + LP issuer
- **Purpose:** Deposit tokens and receive LP tokens
- **Required:**
  - Token A contract ID
  - Token B contract ID
  - Amount of A to deposit
  - Amount of B to deposit
  - Minimum LP tokens (slippage protection)
  - Deadline

**What Happens:**
1. **First Liquidity Provider:**
   - LP tokens = √(amountA × amountB)
   - Sets initial price ratio

2. **Subsequent Providers:**
   - Must deposit proportional amounts
   - LP tokens = min(shareA, shareB)
   - shareA = (amountA × totalSupply) / reserveA
   - shareB = (amountB × totalSupply) / reserveB

### Protections
- ✅ **Slippage Protection**: Minimum LP tokens ensures fair share
- ✅ **Deadline Enforcement**: Deposit expires if pool price changes too much
- ✅ **Proportional Deposits**: Prevents value extraction

### Example Flow - First LP
```
1. Alice wants to provide liquidity for ETH-USDC pool (empty)
2. Alice deposits:
   - 10 ETH
   - 20,000 USDC
3. LP tokens minted: √(10 × 20,000) = 447.21 LP tokens
4. Pool reserves: 10 ETH, 20,000 USDC
5. Price established: 1 ETH = 2,000 USDC
```

### Example Flow - Subsequent LP
```
1. Pool has: 10 ETH, 20,000 USDC, 447.21 LP supply
2. Bob wants to add liquidity
3. Bob deposits:
   - 5 ETH (50% of reserve)
   - 10,000 USDC (50% of reserve)
4. LP tokens minted: 223.60 (50% of supply)
5. Bob owns: 223.60 / 670.81 = 33.33% of pool
6. Pool reserves: 15 ETH, 30,000 USDC
```

---

## 💸 Feature 3: Liquidity Removal (Remove Liquidity)

### What It Does
Burn your LP tokens to withdraw your proportional share of the pool's reserves plus accumulated fees.

### Available Choices

#### RemoveLiquidity
**Choice:** `RemoveLiquidity` (on Pool)
- **Who:** Liquidity provider (you) + pool party + LP issuer
- **Purpose:** Burn LP tokens and receive tokens back
- **Required:**
  - LP token contract ID
  - Amount of LP tokens to burn
  - Minimum amount A (slippage protection)
  - Minimum amount B (slippage protection)
  - Pool's token A contract ID
  - Pool's token B contract ID
  - Deadline

**What Happens:**
1. Calculate your share: `shareRatio = lpTokens / totalSupply`
2. Calculate amounts:
   - amountA = reserveA × shareRatio
   - amountB = reserveB × shareRatio
3. Burn LP tokens
4. Transfer tokens to you
5. Update pool reserves

### Protections
- ✅ **Slippage Protection**: Minimum amounts ensure fair withdrawal
- ✅ **Deadline Enforcement**: Protects against price manipulation
- ✅ **Proportional Withdrawal**: Always get your exact share

### Example Flow
```
1. Bob owns 223.60 LP tokens (33.33% of 670.81 supply)
2. Pool has: 15 ETH, 30,000 USDC
3. Bob burns ALL his LP tokens (223.60)
4. Bob receives:
   - ETH: 15 × (223.60/670.81) = 5 ETH
   - USDC: 30,000 × (223.60/670.81) = 10,000 USDC
5. Bob got back exactly what he deposited + his share of fees!
```

### Fee Accumulation
When you remove liquidity, you receive:
- **Your original deposits** (adjusted for trades)
- **Your share of all 0.3% trading fees** accumulated since you deposited

---

## 🎫 Feature 4: LP Token Management

### What It Does
LP tokens are tradeable assets representing your liquidity position. You can transfer them to others.

### Available Choices

#### 1. Transfer
**Choice:** `Transfer` (on LPToken)
- **Who:** LP token owner (you)
- **Purpose:** Send LP tokens to another party
- **Required:**
  - Recipient party
  - Quantity to transfer
- **What Happens:**
  - Creates new LP token for recipient
  - Creates remainder for you (if partial transfer)
  - Archives original token

#### 2. Credit
**Choice:** `Credit` (on LPToken)
- **Who:** LP token owner (you)
- **Purpose:** Merge multiple LP tokens into one
- **Required:** Quantity to add
- **What Happens:** Combines tokens for easier management

#### 3. Burn
**Choice:** `Burn` (on LPToken)
- **Who:** LP token owner (you)
- **Purpose:** Destroy LP tokens (used by RemoveLiquidity)
- **Required:** Quantity to burn
- **What Happens:**
  - Destroys specified amount
  - Creates remainder (if partial burn)

### Protections
- ✅ **Self-Transfer Forbidden**: Can't transfer to yourself
- ✅ **Balance Checks**: Can't transfer/burn more than you own
- ✅ **Issuer Control**: Only LP issuer can mint new tokens

### Example Flow
```
1. Alice has 447.21 LP tokens
2. Alice transfers 200 LP to Bob
3. Result:
   - Bob: 200 LP tokens (new contract)
   - Alice: 247.21 LP tokens (remainder contract)
   - Original token: archived
4. Bob can now remove his share of liquidity anytime!
```

---

## 🔐 Security Features

### Multi-Party Authorization
Liquidity operations require multiple parties to prevent unauthorized actions:
- **Provider**: You (the liquidity provider)
- **Pool Party**: Holds pool reserves
- **LP Issuer**: Mints/burns LP tokens

### Issuer-as-Signatory Pattern
All tokens (Token, LPToken) use a special pattern where:
- **Issuer** is the only signatory
- **Owner** is an observer
- This enables **bilateral transfers** without recipient authorization
- Prevents malicious token creation

### Mathematical Guarantees
- **Constant Product Formula**: `x * y = k` ensures fair pricing
- **No Price Oracle**: Can't be manipulated by external data
- **Atomic Operations**: All-or-nothing execution prevents partial failures

---

## 📖 Token Standards

### Token (ETH, USDC, etc.)
- **Signatory**: Issuer only
- **Observer**: Owner
- **Choices**: Transfer
- **No Contract Key**: Prevents collisions during transfers

### LPToken (Liquidity Provider Token)
- **Signatory**: LP Issuer only
- **Observer**: Owner
- **Contract Key**: `(issuer, poolId, owner)`
- **Choices**: Transfer, Credit, Burn

### Pool
- **Signatory**: Pool Operator
- **Observers**: Pool Party, LP Issuer, Token Issuers
- **Contract Key**: `(operator, ((symbolA, issuerA), (symbolB, issuerB)))`
- **Unique**: Each token pair has one pool per operator

---

## 🎨 Complete User Journey

### Scenario: Alice Swaps, Then Becomes LP

#### Day 1: Alice Swaps USDC for ETH
```
1. Alice has: 1,000 USDC
2. Pool has: 100 ETH, 200,000 USDC
3. Alice swaps 1,000 USDC for ETH
4. Alice receives: ~0.497 ETH (after 0.3% fee)
5. Pool now: 100.497 ETH, 199,000 USDC
6. Alice has: 0.497 ETH
```

#### Day 2: Alice Provides Liquidity
```
1. Alice has: 10 ETH, 20,000 USDC
2. Pool has: 100 ETH, 200,000 USDC (current price: 1 ETH = 2,000 USDC)
3. Alice adds liquidity:
   - Deposits: 10 ETH + 20,000 USDC
   - Receives: ~31.62 LP tokens (10% of pool)
4. Pool now: 110 ETH, 220,000 USDC
5. Alice owns: 10% of all trading fees going forward
```

#### Day 30: Alice Removes Liquidity
```
1. Pool grew from fees: 115 ETH, 230,000 USDC
2. Alice still owns: 31.62 LP tokens (10% of pool)
3. Alice burns all LP tokens
4. Alice receives:
   - ETH: 11.5 (original 10 + share of fee growth)
   - USDC: 23,000 (original 20,000 + share of fee growth)
5. Alice earned: 1.5 ETH + 3,000 USDC in fees! 🎉
```

---

## 🛡️ Risk Warnings

### Impermanent Loss
If token prices diverge significantly, you may get back different token ratios than you deposited. You could have more value by just holding.

**Example:**
- Deposit: 10 ETH + 20,000 USDC (ETH = $2,000)
- ETH doubles to $4,000
- Withdraw: 7.07 ETH + 28,284 USDC
- Your value: $56,568
- If you held: 10 ETH + 20,000 USDC = $60,000
- **Impermanent loss: $3,432**

However, trading fees may offset this loss over time.

### Slippage
Large swaps move the price significantly. Always set appropriate `minOutput` to protect yourself.

### Smart Contract Risk
While thoroughly tested, smart contracts can have bugs. Only invest what you can afford to lose.

---

## 📞 Summary

ClearPortX DEX provides:
- ✅ **Permissionless token swapping** with price protection
- ✅ **Liquidity provision** to earn trading fees
- ✅ **Flexible position management** via transferable LP tokens
- ✅ **Battle-tested AMM formula** (Uniswap v2 model)
- ✅ **Multi-layer security** (authorization, slippage, deadlines)

**Current Status:** Phase 1 Complete
- ✅ Single-pool swaps
- ✅ Add/remove liquidity
- ✅ LP token management
- 🚧 Multi-pool routing (Phase 3)
- 🚧 Price oracles (Phase 4)
- 🚧 Advanced features (Phase 5)

**All 21 tests passing** ✅
