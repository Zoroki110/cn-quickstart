# AMM (Automated Market Maker) Architecture - DAML Implementation

## Overview

This is a **Uniswap v2 style** Automated Market Maker implemented in DAML/Canton. It uses the constant product formula `x * y = k` for pricing.

## Project Structure

```
clearportx/
├── daml/
│   ├── Token/
│   │   └── Token.daml          # Token with issuer-as-signatory design
│   ├── AMM/
│   │   ├── Pool.daml           # AMM liquidity pool
│   │   └── SwapRequest.daml    # Proposal-Accept pattern for swaps
│   ├── TestSwapProposal.daml   # ✅ Main AMM test (PASSES)
│   ├── TestNoArchive.daml      # ✅ Demonstrates DAML bug
│   └── TestCreate.daml         # ✅ Basic setup test
```

## The Challenge: DAML Authorization

### The Problem

DAML has strict authorization rules:

> **When you `create` a contract, ALL signatories must authorize it.**

For a typical token transfer:
```daml
template Token
  where
    signatory owner  -- ❌ Problem!

    choice Transfer
      controller owner
      do
        archive self
        create this with owner = recipient  -- ❌ FAILS!
                                             -- recipient not authorizing!
```

This makes atomic swaps **impossible** with traditional token designs.

### The Solution: Issuer-as-Signatory Pattern

```daml
template Token
  where
    signatory issuer  -- ✅ ONLY issuer signs
    observer owner    -- Owner can see but doesn't sign

    nonconsuming choice Transfer
      controller owner
      do
        -- Create new tokens
        when (qty < amount) $
          void $ create this with owner = owner, amount = amount - qty
        newToken <- create this with owner = recipient, amount = qty

        -- Archive at the END
        archive self
        return newToken
```

**Key insights:**
1. **Issuer stays constant** - no signatory change, no authorization problem
2. **Owner is metadata** - can control transfers but doesn't sign contracts
3. **`nonconsuming` + manual archive** - avoids "contract consumed twice" bug
4. **No contract key** - prevents key collisions during transfers

## AMM Components

### 1. Token (Token/Token.daml)

**Fields:**
- `issuer: Party` - The token issuer (ONLY signatory)
- `owner: Party` - Current owner (observer only)
- `symbol: Text` - Token symbol (e.g., "ETH", "USDC")
- `amount: Numeric 10` - Token amount

**Choices:**
- `Transfer` - Transfer tokens to recipient (owner controlled)
- `Credit` - Mint more tokens (owner controlled)

### 2. Pool (AMM/Pool.daml)

**Fields:**
- `poolOperator: Party` - Pool manager
- `poolParty: Party` - Pool account holding liquidity
- `issuerA, issuerB: Party` - Token issuers
- `symbolA, symbolB: Text` - Token symbols
- `feeBps: Int` - Fee in basis points (30 = 0.3%)

**Choices:**
- `GetReservesForPool` - Get pool reserves (placeholder)
- `GetSupply` - Get LP token supply (placeholder)

### 3. SwapRequest (AMM/SwapRequest.daml)

Uses **Proposal-Accept pattern** for atomic swaps:

```
TX1: Create SwapRequest (trader proposes swap)
TX2: PrepareSwap (trader transfers input tokens to pool)
TX3: ExecuteSwap (pool calculates output and transfers to trader)
```

**SwapRequest fields:**
- `trader: Party` - Who wants to swap
- `poolCid: ContractId Pool` - Which pool
- `inputTokenCid: ContractId Token` - Input token
- `inputAmount: Numeric 10` - How much to swap
- `outputSymbol: Text` - What to receive
- `minOutput: Numeric 10` - Slippage protection
- `deadline: Time` - Expiration time

**Choices:**
- `PrepareSwap` - Trader transfers input to pool, creates SwapReady
- `CancelSwapRequest` - Trader can cancel

**SwapReady choices:**
- `ExecuteSwap` - Pool calculates output using x*y=k, transfers to trader

### AMM Formula (Uniswap v2)

```
amountOut = (amountIn * feeMul * reserveOut) / (reserveIn + amountIn * feeMul)

where:
  feeMul = (10000 - feeBps) / 10000  (e.g., 0.997 for 0.3% fee)
  x * y = k  (constant product)
```

## Testing

### Main Test: TestSwapProposal.daml

**Scenario:**
- Pool has 10 ETH and 20,000 USDC
- Alice swaps 100 USDC for ETH
- Expected output: ~0.0496 ETH (with 0.3% fee)

**Results:**
```
✅ testSwapWithProposal: ok, 5 active contracts, 7 transactions
```

**Run test:**
```bash
daml test --test-pattern testSwapWithProposal
```

## Key Learnings

### 1. The "Contract Consumed Twice" Bug

Using `archive self` at the START of a choice causes DAML Script to fail:
```daml
choice BadTransfer
  do
    archive self      -- ❌ Archives here
    create newToken   -- DAML validates transaction twice = consumed twice!
```

**Solution:** Use `nonconsuming` and archive at the END.

### 2. Contract Key Collisions

With contract keys, creating a token with the same key before archiving the old one fails:
```daml
key (issuer, symbol, owner)

-- During transfer:
create Token with owner = owner, amount = remainder  -- Same key!
-- Original token not archived yet = KEY VIOLATION
```

**Solution:** Remove contract keys, use ContractId directly.

### 3. Signatory Changes Are Impossible

You CANNOT change signatories in a single transaction:
```daml
signatory owner

create this with owner = newOwner  -- ❌ newOwner not authorizing!
```

**Solution:** Keep signatory constant (issuer), change owner as metadata.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                         AMM SWAP FLOW                        │
└─────────────────────────────────────────────────────────────┘

TX 1: Alice creates SwapRequest
  ┌──────────────┐
  │ SwapRequest  │  signatory: Alice
  │ trader: Alice│  observer: PoolParty
  │ input: USDC  │
  │ output: ETH  │
  └──────────────┘

TX 2: Alice exercises PrepareSwap
  ┌──────────────┐         Transfer         ┌──────────────┐
  │ SwapRequest  │ ───────────────────────> │  SwapReady   │
  └──────────────┘                          │ trader: Alice│
        │                                    └──────────────┘
        │ Transfers Alice's USDC to Pool
        ▼
  ┌──────────────┐
  │ Token (USDC) │  owner: Alice → PoolParty
  └──────────────┘

TX 3: Pool exercises ExecuteSwap
  ┌──────────────┐                          ┌──────────────┐
  │  SwapReady   │  Calculates x*y=k       │  Token (ETH) │
  │              │ ───────────────────────> │ owner: Alice │
  └──────────────┘  Transfers ETH to Alice  │ amount: 0.049│
                                             └──────────────┘
```

## Future Improvements

1. **Add Liquidity** - Allow users to add liquidity and receive LP tokens
2. **Remove Liquidity** - Burn LP tokens to withdraw assets
3. **Price Oracles** - Track TWAP (Time-Weighted Average Price)
4. **Multi-hop Swaps** - Swap through multiple pools
5. **Flash Swaps** - Borrow tokens and repay in same transaction
6. **Governance** - DAO control over fees and parameters

## Questions?

The core innovation is the **issuer-as-signatory pattern** which solves DAML's authorization constraints for atomic swaps. This pattern can be used for any DeFi protocol requiring permissionless token transfers.
