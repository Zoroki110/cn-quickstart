# Question pour DAML Forum

## Titre
**"Contract consumed twice" when choice exercises another choice with matching controllers**

## Corps de la question

I'm implementing an AMM (Automated Market Maker) in DAML and encountering a "contract consumed twice" error that I cannot resolve.

### Minimal Reproduction

**Token Template:**
```daml
template Token
  with
    issuer : Party
    owner  : Party
    amount : Numeric 10
  where
    signatory issuer
    observer owner

    choice Transfer : ContractId Token
      with
        recipient : Party
        qty : Numeric 10
      controller owner, issuer
      do
        archive self
        create this with owner = recipient, amount = qty
```

**SwapRequest Template:**
```daml
template SwapRequest
  with
    trader : Party
    inputTokenCid : ContractId Token
  where
    signatory trader

    choice PrepareSwap : ()
      with
        inputIssuer : Party
      controller trader, inputIssuer
      do
        _ <- exercise inputTokenCid Transfer with
          recipient = poolParty
          qty = 100.0
        return ()
```

**Test Script:**
```daml
test = script do
  alice <- allocateParty "Alice"
  issuer <- allocateParty "Issuer"

  token <- submit issuer $ createCmd Token with
    issuer = issuer
    owner = alice
    amount = 100.0

  request <- submit alice $ createCmd SwapRequest with
    trader = alice
    inputTokenCid = token

  -- This fails with "Contract consumed twice"
  submitMulti [alice, issuer] [] $
    exerciseCmd request PrepareSwap with
      inputIssuer = issuer
```

### Error
```
Attempt to exercise a contract that was consumed in same transaction.
Contract: Token

Partial transaction:
  Failed exercise (unknown source):
    exercises Transfer on Token
  Sub-transactions:
    └─> 'Alice' exercises Transfer on Token
```

### Question

Why does DAML attempt to exercise `Transfer` twice when:
- `PrepareSwap` has `controller trader, inputIssuer` (Alice, Issuer)
- `Transfer` has `controller owner, issuer` (Alice, Issuer)
- Both controllers match exactly
- `submitMulti [alice, issuer]` includes both parties

The error shows a "Failed exercise (unknown source)" followed by a successful exercise by Alice, suggesting DAML tries to exercise the choice twice with different controller combinations.

**What is the correct pattern to allow a choice to exercise another choice when both require the same set of controllers?**

---

## Alternative (si tu veux une question plus courte)

## Titre
**Nested choice exercise fails with "contract consumed twice" despite matching controllers**

## Corps

I have two choices with matching controllers but get "contract consumed twice" when one exercises the other:

```daml
-- Token.Transfer: controller owner, issuer
-- SwapRequest.PrepareSwap: controller trader, inputIssuer
-- Where trader = owner AND inputIssuer = issuer

choice PrepareSwap : ()
  with inputIssuer : Party
  controller trader, inputIssuer
  do
    exercise inputTokenCid Transfer with ...
```

**Error:** "Attempt to exercise a contract that was consumed in same transaction"

**Test:**
```daml
submitMulti [alice, issuer] [] $
  exerciseCmd request PrepareSwap with inputIssuer = issuer
```

The error shows DAML attempts to exercise `Transfer` twice (once "unknown source", once by Alice).

**Why does this happen when the controllers match exactly? What's the correct pattern for nested choice exercises?**

---

## Informations Supplémentaires à Ajouter si Demandées

- **DAML SDK Version:** 2.9.3
- **Context:** Building Uniswap-style AMM with proposal-accept pattern
- **What I've tried:**
  - Using `controller owner` only → Missing authorization from issuer
  - Using `submit alice` instead of `submitMulti` → Missing authorization from issuer
  - Various combinations of `submitMulti` with actAs/readAs → Same error
  - Different choice signatures → Same error

---

## 📝 Instructions pour Poster

1. **Titre** : Copie un des titres ci-dessus (le plus court est mieux)
2. **Corps** : Copie la version "Alternative" (plus courte) ou la version complète selon ce que tu préfères
3. **Tags suggérés** : `choices`, `authorization`, `scripting`, `error`
4. **Catégorie** : "Help" ou "Questions"

La question est formulée pour :
- ✅ Être courte et directe
- ✅ Inclure le code minimal pour reproduire
- ✅ Expliquer clairement le problème
- ✅ Poser une question spécifique et actionnable

Veux-tu que je reformule quelque chose ou que j'ajoute des détails ?
