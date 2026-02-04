# AMM Quick Start Guide

## Project Status: ✅ WORKING

All tests pass:
- ✅ `testSwapWithProposal` - Full AMM swap (Alice: 100 USDC → ~0.0496 ETH)
- ✅ `testNoArchive` - Demonstrates DAML bug workaround
- ✅ `setup` - Basic pool creation
- ✅ `createInitialTokens` - Token minting

## Run Tests

```bash
cd /root/cn-quickstart/quickstart/clearportx

# Run all tests
daml test

# Run specific test
daml test --test-pattern testSwapWithProposal
```

## Key Files

### Core AMM
- [`daml/Token/Token.daml`](daml/Token/Token.daml) - Token with issuer-as-signatory
- [`daml/AMM/Pool.daml`](daml/AMM/Pool.daml) - AMM liquidity pool
- [`daml/AMM/SwapRequest.daml`](daml/AMM/SwapRequest.daml) - Swap proposal-accept

### Tests
- [`daml/TestSwapProposal.daml`](daml/TestSwapProposal.daml) - **Main AMM test**
- [`daml/TestNoArchive.daml`](daml/TestNoArchive.daml) - Bug demonstration

### Documentation
- [`AMM-ARCHITECTURE.md`](AMM-ARCHITECTURE.md) - **Complete architecture guide**
- [`DEBUGGING-JOURNEY.md`](DEBUGGING-JOURNEY.md) - **6-hour debugging story**
- This file - Quick reference

## The Critical Pattern

```daml
template Token
  with
    issuer : Party  -- Central authority
    owner : Party   -- Current holder
  where
    signatory issuer  -- ✅ Issuer ONLY (not owner!)
    observer owner

    -- No contract key to avoid collisions

    nonconsuming choice Transfer  -- ✅ Manual archive control
      controller owner
      do
        -- 1. Create new tokens
        when (qty < amount) $
          void $ create this with owner = owner, amount = amount - qty
        newToken <- create this with owner = recipient, amount = qty

        -- 2. Archive at END (not beginning!)
        archive self

        return newToken
```

## Why This Works

| Traditional Token | AMM Token |
|------------------|-----------|
| `signatory owner` | `signatory issuer` |
| Can't create for recipient | ✅ Issuer authorizes all |
| Needs recipient approval | ✅ No approval needed |
| `archive self` at start | ✅ `archive self` at end |
| "Contract consumed twice" | ✅ No errors |
| Has contract key | ✅ No key (uses ContractId) |

## AMM Swap Flow

```
┌─────────┐   100 USDC    ┌──────┐   ~0.0496 ETH   ┌───────┐
│  Alice  │ ────────────> │ Pool │ ───────────────> │ Alice │
└─────────┘               └──────┘                  └───────┘
                         10 ETH
                         20,000 USDC
                         (x * y = k)
```

**3 Transactions:**
1. Alice creates `SwapRequest`
2. Alice executes `PrepareSwap` (transfers USDC to pool)
3. Pool executes `ExecuteSwap` (calculates & transfers ETH to Alice)

## Next Steps

1. **Read** [`AMM-ARCHITECTURE.md`](AMM-ARCHITECTURE.md) for full details
2. **Study** [`daml/TestSwapProposal.daml`](daml/TestSwapProposal.daml) to understand flow
3. **Experiment** - Modify fees, amounts, add new features
4. **Build** - Add liquidity, remove liquidity, multi-hop swaps

## Common Issues

### Issue: "Contract consumed twice"
**Cause:** Using `archive self` at START of choice
**Fix:** Use `nonconsuming` and `archive self` at END

### Issue: "Key violation"
**Cause:** Contract key exists during transfer
**Fix:** Remove contract key, use ContractId

### Issue: "Missing authorization from recipient"
**Cause:** Recipient is signatory but not authorizing
**Fix:** Use `signatory issuer` (not owner)

## DAML Version

```yaml
sdk-version: 2.10.2
```

Tested on DAML SDK 2.9.3 and 2.10.2.

---

**🎉 You now have a working AMM in DAML!**

Questions? Read the debugging journey to understand how we got here.
