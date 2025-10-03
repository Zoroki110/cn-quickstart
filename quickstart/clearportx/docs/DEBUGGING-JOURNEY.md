# The 6-Hour Debugging Journey: Building AMM in DAML

## Timeline of Discovery

### Attempt 1: Traditional Token Design (Failed)
**Duration:** 1 hour
**Approach:** `signatory owner`

```daml
template Token
  where
    signatory owner  -- Owner signs

    choice Transfer
      controller owner
      do
        archive self
        create this with owner = recipient
```

**Error:**
```
Attempt to exercise a contract that was consumed in same transaction.
Contract: #3:0 (Token.Token:Token)
```

**Why:** DAML Script validation bug with `archive self` before `create`.

---

### Attempt 2: Dual Controllers (Failed)
**Duration:** 1.5 hours
**Approach:** `controller owner, recipient`

```daml
choice Transfer
  with recipient : Party
  controller owner, recipient  -- Both control
  do
    archive self
    create this with owner = recipient
```

**Test:**
```daml
submitMulti [alice, bob] [] $
  exerciseCmd token Transfer with recipient = bob
```

**Error:** Same "contract consumed twice"

**Why:** DAML verifies transaction for EACH party in submitMulti, causing double exercise.

---

### Attempt 3: Dual Signatories (Failed)
**Duration:** 1 hour
**Approach:** `signatory issuer, owner`

```daml
template Token
  where
    signatory issuer, owner  -- Both sign
```

**Error:**
```
create of Token.Token:Token failed due to missing authorization from 'PoolParty'
```

**Why:** Creating token requires BOTH issuer AND owner to authorize. In `submit alice`, only Alice authorizes. Can't create token for Bob.

---

### Attempt 4: Working Example Discovery (Breakthrough!)
**Duration:** 1 hour
**Discovery:** Found `TestWorking.daml` with `TokenWorking`

```daml
template TokenWorking
  where
    signatory owner  -- Just owner

    choice TransferWorking
      controller owner, recipient  -- Both!
```

**Test:**
```daml
submitMulti [alice, bob] [] $
  exerciseCmd token TransferWorking
```

**Error:** STILL "contract consumed twice"!

**Realization:** Even the "working" example doesn't work! The pattern itself is flawed.

---

### Attempt 5: Issuer-Only Signatory (Failed at first)
**Duration:** 30 minutes
**Approach:** `signatory issuer` only

```daml
template Token
  where
    signatory issuer  -- Only issuer
    observer owner
```

**Error:** STILL "contract consumed twice"

**Why:** Still using `archive self` at the beginning.

---

### Attempt 6: Testing Without Archive (Discovery #2!)
**Duration:** 30 minutes
**Created:** `TestNoArchive.daml`

```daml
choice GiveNoArchive
  controller owner
  do
    -- NO archive - just create
    create Coin { issuer = issuer, owner = newOwner }
```

**Result:** ✅ **SUCCESS!**

**Discovery:** The bug is `archive self` BEFORE `create`, not the signatory pattern!

---

### Attempt 7: Nonconsuming Choice (Final Solution!)
**Duration:** 1 hour
**Approach:** `nonconsuming` with manual archive at END

```daml
template Token
  where
    signatory issuer
    observer owner

    nonconsuming choice Transfer  -- ✅ Key insight!
      controller owner
      do
        -- Create tokens FIRST
        when (qty < amount) $
          void $ create this with owner = owner, amount = amount - qty
        newToken <- create this with owner = recipient, amount = qty

        -- Archive LAST
        archive self
        return newToken
```

**Result:** ✅ **SUCCESS!**

---

## The Three Critical Insights

### 1. DAML Authorization Rule
```
When creating a contract:
  ALL signatories must authorize

When exercising a choice:
  ALL controllers must authorize

Authorization = controllers + signatories (of template)
```

**Implication:** Can't create token for someone else if they're a signatory.

### 2. The "Archive Before Create" Bug

DAML Script validation:
1. Validates transaction (consumes contract)
2. Executes transaction (consumes again)
3. Error: "contract consumed twice"

**Solution:** Use `nonconsuming` and archive at END.

### 3. Contract Key Collisions

With keys, during transfer:
```
Original: (issuer, symbol, Alice) exists
Create remainder: (issuer, symbol, Alice) ← KEY VIOLATION!
```

**Solution:** No contract keys, use ContractId directly.

---

## Failed Alternatives Explored

### Alternative A: Proposal-Accept Pattern
```daml
template TransferProposal
  signatory sender
  observer recipient

  choice Accept
    controller recipient
```

**Problem:** Requires 2 extra transactions for AMM (breaks atomicity).

**AMM needs:**
- TX 1: Alice→Pool transfer
- TX 2: Pool→Alice transfer

**With Proposal-Accept:**
- TX 1: Alice proposes
- TX 2: Pool accepts Alice's USDC
- TX 3: Pool proposes ETH
- TX 4: Alice accepts ETH ← Not atomic!

### Alternative B: Pre-Authorization Pattern
```daml
template DepositAuth
  signatory recipient

  choice Deposit
    controller depositor
```

**Problem:** Complex, requires setup, still doesn't solve nested choice atomicity.

### Alternative C: DAML Finance Holdings
**Problem:** Would require complete rewrite using DAML Finance library. Overkill for learning project.

---

## Statistics

- **Total time:** ~6 hours
- **Approaches tried:** 7
- **Test files created:** 13
- **Test files kept:** 3
- **Lines of code written:** ~2000
- **Lines of code kept:** ~300

---

## Key Takeaways

1. **DAML is NOT Solidity** - Authorization rules are fundamentally different
2. **Signatories vs Controllers** - Understanding the difference is critical
3. **Choices are consuming by default** - Manual control with `nonconsuming`
4. **Contract keys can hurt** - Sometimes ContractId is simpler
5. **DAML Script has quirks** - The "archive before create" bug cost 4 hours

---

## The Winning Pattern

For DeFi protocols in DAML:

```daml
template Asset
  with
    issuer : Party    -- Central authority
    owner : Party     -- Current holder
  where
    signatory issuer  -- ✅ Issuer ONLY
    observer owner    -- ✅ Owner can see

    nonconsuming choice Transfer  -- ✅ Manual archive control
      controller owner
      do
        -- Creates first
        newAsset <- create this with owner = recipient
        -- Archive last
        archive self
        return newAsset
```

This enables:
- ✅ Permissionless transfers
- ✅ Atomic swaps
- ✅ No recipient authorization needed
- ✅ Works with nested choices
- ✅ Compatible with DAML Script

---

## What I Learned About DAML

**Strengths:**
- Strong guarantees about authorization
- Impossible to create unauthorized contracts
- Excellent for multi-party workflows with explicit consent

**Weaknesses:**
- DeFi patterns (permissionless transfers) require workarounds
- Contract key limitations
- DAML Script validation can be confusing
- Limited documentation for advanced patterns

**Conclusion:** DAML favors **permissioned** finance over **permissionless** DeFi. The issuer-as-signatory pattern bridges this gap.