# MODULE 03 - SYSTÈME DE SWAP DAML

**Auteur**: Documentation technique ClearportX  
**Date**: 2025-10-21  
**Version**: 1.0.0  
**Prérequis**: Module 01 (Architecture), Module 02 (Templates Core)

---

## TABLE DES MATIÈRES

1. [Vue d'ensemble du système de swap](#1-vue-densemble-du-système-de-swap)
2. [SwapRequest.daml - Pattern Proposal-Accept](#2-swaprequestdaml---pattern-proposal-accept)
3. [AtomicSwap.daml - Swap atomique 1-step](#3-atomicswapdaml---swap-atomique-1-step)
4. [Receipt.daml - Preuve d'exécution](#4-receiptdaml---preuve-dexécution)
5. [PoolAnnouncement.daml - Découverte sans clés](#5-poolannouncementdaml---découverte-sans-clés)
6. [Comparaison des deux approches](#6-comparaison-des-deux-approches)
7. [Flux de données complets](#7-flux-de-données-complets)

---

## 1. VUE D'ENSEMBLE DU SYSTÈME DE SWAP

### 1.1 Architecture du système

Le système de swap ClearportX utilise **deux patterns complémentaires** :

```
┌─────────────────────────────────────────────────────────────────┐
│                    SWAP EXECUTION PATTERNS                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  PATTERN 1: TWO-STEP (SwapRequest → SwapReady → Receipt)        │
│  ┌──────────┐    PrepareSwap    ┌──────────┐   ExecuteSwap     │
│  │ Swap     │ ─────────────────> │ Swap     │ ───────────────> │
│  │ Request  │  (trader extracts  │ Ready    │   (pool calcs    │
│  │          │   protocol fee)    │          │    AMM + swap)   │
│  └──────────┘                    └──────────┘                   │
│       ↓                                ↓                        │
│   Trader CID                     Transferred to Pool            │
│                                                                  │
│  PATTERN 2: ONE-STEP (AtomicSwapProposal → Receipt)             │
│  ┌──────────┐                                                   │
│  │ Atomic   │    ExecuteAtomicSwap                              │
│  │ Swap     │ ────────────────────────────────────────────────> │
│  │ Proposal │   (creates SwapRequest + PrepareSwap +            │
│  └──────────┘    ExecuteSwap atomically)                        │
│                                                                  │
│  SHARED OUTPUT: Receipt (proof of execution)                    │
│  ┌──────────┐                                                   │
│  │ Receipt  │ ← Contains: amountIn, amountOut, fees, price      │
│  └──────────┘                                                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 Choix du pattern selon le contexte

| Critère                     | Two-Step (SwapRequest)    | One-Step (AtomicSwap)     |
|-----------------------------|---------------------------|---------------------------|
| **Transactions DAML**       | 2 (PrepareSwap + Execute) | 1 (tout atomique)         |
| **Latence**                 | Plus lente (2 TXs)        | Rapide (1 TX)             |
| **Flexibilité**             | Trader peut annuler       | Tout ou rien              |
| **Complexité DAML**         | Plus simple (choices séparés) | Plus complexe (nested)  |
| **Utilisation backend**     | API REST ClearportX       | Tests DAML, scripts       |
| **Observabilité**           | 2 étapes observables      | Transaction atomique      |

**Recommandation production** : Utiliser **AtomicSwapProposal** pour le backend REST car :
- ✅ Latence minimale (1 seule transaction DAML)
- ✅ Atomicité garantie (pas de state intermédiaire SwapReady)
- ✅ Rate limiting simplifié (0.4 TPS devnet)
- ✅ Moins de ContractIds à gérer

---

## 2. SWAPREQUEST.DAML - PATTERN PROPOSAL-ACCEPT

### 2.1 Template SwapRequest

```daml
template SwapRequest
  with
    trader : Party                       -- ← Utilisateur qui initie le swap
    poolCid : ContractId P.Pool          -- ← ContractId du pool (no contract key!)
    poolParty : Party                    -- ← Propriétaire du pool
    poolOperator : Party                 -- ← Opérateur (peut différer de poolParty)
    issuerA : Party                      -- ← Émetteur token A
    issuerB : Party                      -- ← Émetteur token B
    symbolA : Text                       -- ← "ETH", "USDC", etc.
    symbolB : Text
    feeBps : Int                         -- ← Fee en basis points (30 = 0.3%)
    maxTTL : RelTime                     -- ← Time-to-live maximum
    inputTokenCid : ContractId T.Token   -- ← Token du trader (avant transfer)
    inputSymbol : Text                   -- ← "ETH" ou "USDC" (direction du swap)
    inputAmount : Numeric 10             -- ← Montant input (AVANT protocol fee)
    outputSymbol : Text                  -- ← Token souhaité en output
    minOutput : Numeric 10               -- ← Slippage protection
    deadline : Time                      -- ← Expiration du swap
    maxPriceImpactBps : Int              -- ← Max 50% (5000 bps)
  where
    signatory trader                     -- ← Seul le trader signe
    observer poolParty                   -- ← Pool observe la proposition
```

**Points clés** :
- `inputTokenCid` est **encore chez le trader** (pas encore transféré)
- `inputAmount` est le montant **AVANT extraction du protocol fee**
- `observer poolParty` permet au pool de voir la proposition
- Pas de `contract key` (architecture ContractId-Only)

### 2.2 Choice PrepareSwap - Extraction du Protocol Fee

**Flow complet** :

```
AVANT PrepareSwap:
┌─────────────┐
│ trader owns │  inputTokenCid (100 ETH)
│ 100 ETH     │
└─────────────┘

PENDANT PrepareSwap (lines 48-88):
┌──────────────────────────────────────────────────────────────┐
│ 1. Calculate Protocol Fee (25% of total 0.3% fee)            │
│    totalFeeRate = 30 / 10000 = 0.003                         │
│    totalFee = 100 * 0.003 = 0.3 ETH                          │
│    protocolFee = 0.3 * 0.25 = 0.075 ETH (25% to ClearportX) │
│    amountAfterProtocolFee = 100 - 0.075 = 99.925 ETH         │
│                                                               │
│ 2. TransferSplit (line 55)                                   │
│    ┌───────────────────────────────────────────────────┐    │
│    │ inputTokenCid (100 ETH) ──> SPLIT ──> 2 tokens:   │    │
│    │   • protocolFeeReceiver owns 0.075 ETH            │    │
│    │   • remainderCid owns 99.925 ETH (still trader!)  │    │
│    └───────────────────────────────────────────────────┘    │
│                                                               │
│ 3. Transfer to Pool (line 63)                                │
│    remainderCid (99.925 ETH) → poolParty                     │
│    Now poolParty owns poolInputTokenCid                      │
│                                                               │
│ 4. Create SwapReady (line 68)                                │
│    Stores: inputAmount = 99.925 (AFTER protocol fee!)       │
│            poolInputCid = ContractId already at poolParty    │
│            protocolFeeAmount = 0.075 (for Receipt)           │
└──────────────────────────────────────────────────────────────┘

APRÈS PrepareSwap:
┌──────────────────────────┐
│ protocolFeeReceiver owns │  0.075 ETH (protocol treasury)
│ poolParty owns           │  99.925 ETH (ready to swap)
│ SwapReady exists         │  (waiting for ExecuteSwap)
└──────────────────────────┘
```

**Code ligne par ligne** (lines 48-88) :

```daml
choice PrepareSwap : (ContractId SwapReady, ContractId T.Token)
  with
    protocolFeeReceiver : Party  -- ← Passé en paramètre (ClearportX treasury)
  controller trader              -- ← Seul le trader peut préparer (il owns inputTokenCid)
  do
    -- Calcul du protocol fee (25% du total 0.3%)
    let totalFeeRate = intToDecimal feeBps / 10000.0      -- ← 30 / 10000 = 0.003
    let totalFeeAmount = inputAmount * totalFeeRate        -- ← 100 * 0.003 = 0.3
    let protocolFeeAmount = totalFeeAmount * 0.25          -- ← 0.3 * 0.25 = 0.075 ETH
    let amountAfterProtocolFee = inputAmount - protocolFeeAmount  -- ← 99.925 ETH

    -- TransferSplit : 1 token → 2 tokens (protocol + remainder)
    (maybeRemainder, _) <- exercise inputTokenCid T.TransferSplit with
      recipient = protocolFeeReceiver  -- ← ClearportX treasury
      qty = protocolFeeAmount          -- ← 0.075 ETH va au treasury

    -- Récupère le reste (99.925 ETH still owned by trader)
    let Some remainderCid = maybeRemainder

    -- Transfer le reste au pool (prerequisite pour ExecuteSwap)
    poolInputTokenCid <- exercise remainderCid T.Transfer with
      recipient = poolParty           -- ← Maintenant poolParty owns 99.925 ETH
      qty = amountAfterProtocolFee    -- ← 99.925 ETH

    -- Créer SwapReady avec montant APRÈS protocol fee
    swapReadyCid <- create SwapReady with
      trader = trader
      poolCid = poolCid
      poolParty = poolParty
      protocolFeeReceiver = protocolFeeReceiver
      issuerA = issuerA
      issuerB = issuerB
      symbolA = symbolA
      symbolB = symbolB
      feeBps = feeBps
      maxTTL = maxTTL
      inputSymbol = inputSymbol
      inputAmount = amountAfterProtocolFee  -- ← 99.925 (montant pool, pas original!)
      poolInputCid = poolInputTokenCid      -- ← CID déjà chez poolParty
      outputSymbol = outputSymbol
      minOutput = minOutput
      deadline = deadline
      maxPriceImpactBps = maxPriceImpactBps
      protocolFeeAmount = protocolFeeAmount -- ← 0.075 (pour Receipt audit)

    return (swapReadyCid, poolInputTokenCid)
```

**Pourquoi `controller trader` ?** (line 46)
- Le choice `Transfer` de Token.daml a `controller owner`
- `inputTokenCid` appartient au trader
- Donc seul le trader peut exercer `Transfer` sur son token
- Le pool ne peut PAS exercer `PrepareSwap` (il ne possède pas `inputTokenCid`)

### 2.3 Template SwapReady

```daml
template SwapReady
  with
    trader : Party
    poolCid : ContractId P.Pool
    poolParty : Party
    protocolFeeReceiver : Party       -- ← Observer pour voir les fees
    issuerA : Party
    issuerB : Party
    symbolA : Text
    symbolB : Text
    feeBps : Int
    maxTTL : RelTime
    inputSymbol : Text
    inputAmount : Numeric 10          -- ← Montant APRÈS protocol fee (99.925)
    poolInputCid : ContractId T.Token -- ← Token DÉJÀ transféré à poolParty
    outputSymbol : Text
    minOutput : Numeric 10
    deadline : Time
    maxPriceImpactBps : Int
    protocolFeeAmount : Numeric 10    -- ← Protocol fee extrait (0.075)
  where
    signatory trader
    observer poolParty, protocolFeeReceiver
```

**État du monde à ce stade** :
- ✅ Protocol fee extrait (0.075 ETH au treasury)
- ✅ Input token transféré au pool (99.925 ETH chez poolParty)
- ✅ SwapReady contract créé (prêt pour ExecuteSwap)
- ⏳ Aucun calcul AMM encore effectué
- ⏳ Reserves du pool pas encore modifiées

### 2.4 Choice ExecuteSwap - Calcul AMM et Swap

**Flow complet** (227 lignes, lines 118-227) :

```
┌────────────────────────────────────────────────────────────────┐
│ EXECUTESWAP - 8 ÉTAPES CRITIQUES                               │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│ ÉTAPE 1: Fetch Pool & Validations (lines 121-142)              │
│   pool <- fetch poolCid   ← Get ACTUAL current reserves        │
│   now <- getTime          ← Check deadline                     │
│   assertMsg "Swap expired" (now <= deadline)                   │
│   assertMsg "Input reserve > 0" (rin > 0.0)                    │
│   assertMsg "Output reserve > 0" (rout > 0.0)                  │
│   assertMsg "Input amount > 0" (inputAmount > 0.0)             │
│   assertMsg "Fee valid" (feeBps >= 0 && feeBps <= 10000)       │
│                                                                 │
│ ÉTAPE 2: Determine Direction (lines 133-136)                   │
│   if inputSymbol == symbolA:                                   │
│     direction = A→B                                            │
│     rin = poolAmountA, rout = poolAmountB                      │
│     poolInCid = pool.tokenACid, poolOutCid = pool.tokenBCid    │
│   else:                                                         │
│     direction = B→A                                            │
│     rin = poolAmountB, rout = poolAmountA                      │
│     poolInCid = pool.tokenBCid, poolOutCid = pool.tokenACid    │
│                                                                 │
│ ÉTAPE 3: AMM Calculation (lines 144-148) - CONSTANT PRODUCT    │
│   feeMul = (10000 - feeBps) / 10000  ← 0.997 for 0.3% fee     │
│   ainFee = inputAmount * feeMul       ← 99.925 * 0.997 = 99.63│
│   denom = rin + ainFee                ← New reserve in         │
│   aout = (ainFee * rout) / denom      ← x * y = k formula     │
│                                                                 │
│   Exemple: rin=1000 ETH, rout=2000000 USDC, inputAmount=99.925│
│   ainFee = 99.925 * 0.997 = 99.626 ETH                        │
│   denom = 1000 + 99.626 = 1099.626 ETH                        │
│   aout = (99.626 * 2000000) / 1099.626 = 181,277 USDC         │
│                                                                 │
│ ÉTAPE 4: Slippage & Limits (lines 151-168)                     │
│   assertMsg "Min output not met" (aout >= minOutput)           │
│   assertMsg "Liquidity exhausted" (aout < rout)                │
│   assertMsg "Input > maxInBps" (inputAmount <= maxInput)       │
│   assertMsg "Output > maxOutBps" (aout <= maxOutput)           │
│   assertMsg "Price impact > 50%" (maxPriceImpactBps <= 5000)  │
│                                                                 │
│   Price impact calculation:                                    │
│   pBefore = rout / rin        ← 2000000 / 1000 = 2000 USDC/ETH│
│   pAfter = (rout - aout) / (rin + inputAmount)                │
│          = (2000000 - 181277) / (1000 + 99.925)               │
│          = 1653.6 USDC/ETH                                     │
│   impact = |pAfter - pBefore| / pBefore * 10000               │
│          = |1653.6 - 2000| / 2000 * 10000 = 1732 bps (17.32%) │
│                                                                 │
│ ÉTAPE 5: Pool Token Validation (lines 170-174)                 │
│   assertMsg "Pool has no output tokens" (poolOutCid != None)   │
│   ← Critical: Cannot swap from empty pool                      │
│                                                                 │
│ ÉTAPE 6: Consolidation Input (lines 176-180)                   │
│   case poolInCidCanonical of                                   │
│     None -> poolInputCid becomes canonical (first swap!)       │
│     Some canonicalCid -> Merge poolInputCid into canonical     │
│   ← Archive-and-recreate: consolidate multiple tokens          │
│                                                                 │
│ ÉTAPE 7: Transfer Output (lines 183-188)                       │
│   (maybeRemainder, traderOutputCid) <- TransferSplit with      │
│     recipient = trader                                         │
│     qty = aout       ← 181,277 USDC au trader                 │
│   poolOutRemainderCid stays with pool                          │
│                                                                 │
│ ÉTAPE 8: Update Reserves (lines 191-226)                       │
│   newReserveA = rin + inputAmount (if A→B) or rout - aout     │
│   newReserveB = rout - aout (if A→B) or rin + inputAmount     │
│   assertMsg "New reserves > 0" (both positive)                 │
│                                                                 │
│   Archive old pool, create new pool:                           │
│   newPool <- exercise poolCid P.ArchiveAndUpdateReserves with  │
│     updatedReserveA = newReserveA                              │
│     updatedReserveB = newReserveB                              │
│     updatedTokenACid = finalTokenACid (consolidated)           │
│     updatedTokenBCid = finalTokenBCid (remainder)              │
│                                                                 │
│   Create Receipt:                                              │
│   create R.Receipt with                                        │
│     trader, poolParty, inputSymbol, outputSymbol               │
│     amountIn = inputAmount    ← 99.925 ETH (after protocol)   │
│     amountOut = aout          ← 181,277 USDC                  │
│     protocolFee = protocolFeeAmount ← 0.075 ETH               │
│     price = aout / inputAmount ← 1814.7 USDC/ETH              │
│     outputTokenCid = traderOutputCid                           │
│     newPoolCid = newPool                                       │
│     timestamp = now                                            │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

**CRITICAL FIX #5** (line 122) : **Fetch Pool Actual Reserves**

```daml
choice ExecuteSwap : ContractId R.Receipt
  controller poolParty  -- ← Seul le pool exécute (il owns les tokens)
  do
    -- CRITICAL-5 FIX: Fetch pool to get ACTUAL current reserves (not parameters)
    pool <- fetch poolCid  -- ← Toujours fetch pour avoir l'état réel du pool!

    -- Use ACTUAL pool reserves directly
    let poolAmountA = pool.reserveA  -- ← Pas de paramètres, fetch du ledger
    let poolAmountB = pool.reserveB
```

**Pourquoi c'est critique ?**
- Sans `fetch`, on utiliserait des réserves périmées (passées en paramètres)
- Avec `fetch`, on lit l'état actuel du ledger (source de vérité)
- Si 10 swaps concurrent, chaque `ExecuteSwap` voit les bonnes réserves
- Prévient les arbitrages basés sur des prix stale

**CRITICAL FIX #1** (lines 138-142) : **Validate Before Division**

```daml
-- CRITICAL-1 FIX: Validate ALL values before any division operations
assertMsg "Input reserve must be positive" (rin > 0.0)
assertMsg "Output reserve must be positive" (rout > 0.0)
assertMsg "Input amount must be positive" (inputAmount > 0.0)
assertMsg "Fee basis points must be valid" (feeBps >= 0 && feeBps <= 10000)
```

**Pourquoi ?**
- Division par zéro = crash de la transaction
- `aout = (ainFee * rout) / denom` nécessite `denom > 0`
- `pBefore = rout / rin` nécessite `rin > 0`
- Validation proactive = meilleure UX (message d'erreur clair)

**MEDIUM FIX #3** (lines 161-162) : **Limit Price Impact**

```daml
-- MEDIUM-3 FIX: Enforce reasonable price impact limits
assertMsg "Price impact tolerance too high (max 50% allowed)"
  (maxPriceImpactBps <= 5000)
```

**Pourquoi ?**
- Empêche les traders de set `maxPriceImpactBps = 10000` (100%)
- 50% est déjà énorme (pools très illiquides)
- Protection contre les swaps qui vident complètement le pool

**Pool Consolidation** (lines 176-180) :

```daml
-- CONSOLIDATION: Merge poolInputCid into canonical token (NO new transfer!)
-- poolInputCid is already owned by poolParty from PrepareSwap
consolidatedInCid <- case poolInCidCanonical of
  None -> return poolInputCid  -- ← First swap: poolInputCid devient canonical
  Some canonicalCid -> exercise canonicalCid T.Merge with 
    otherTokenCid = poolInputCid  -- ← Merge 99.925 ETH into canonical
```

**Pourquoi Merge au lieu de Transfer ?**
- `poolInputCid` est **déjà owned by poolParty** (fait dans PrepareSwap)
- `Merge` consolide 2 tokens du **même owner** en 1 seul token
- Évite la fragmentation (100 swaps = 1 token pool, pas 100 tokens)
- Pattern archive-and-recreate : `canonicalCid` archivé, nouveau token créé

**Transfer Output au Trader** (lines 183-188) :

```daml
-- Transfer output from pool to trader
let Some canonicalOutCid = poolOutCidCanonical  -- ← Garanti != None (validé ligne 170)
(maybePoolOutRemainder, traderOutputCid) <- exercise canonicalOutCid T.TransferSplit with
  recipient = trader
  qty = aout  -- ← 181,277 USDC au trader

let Some poolOutRemainderCid = maybePoolOutRemainder  -- ← Pool garde le reste
```

**Flow des tokens** :

```
AVANT TransferSplit:
Pool owns canonicalOutCid = 2,000,000 USDC

APRÈS TransferSplit (aout = 181,277 USDC):
Pool owns poolOutRemainderCid = 1,818,723 USDC
Trader owns traderOutputCid = 181,277 USDC
```

**Update Reserves Direction-Aware** (lines 191-206) :

```daml
-- Calculate new reserves
let newReserveA = if isAtoB
                 then rin + inputAmount  -- ← Input was A (ETH in)
                 else rout - aout        -- ← Output was A (ETH out)
let newReserveB = if isAtoB
                 then rout - aout        -- ← Output was B (USDC out)
                 else rin + inputAmount  -- ← Input was B (USDC in)

-- Verify new reserves are still positive
assertMsg "New reserve A must be positive" (newReserveA > 0.0)
assertMsg "New reserve B must be positive" (newReserveB > 0.0)

-- Update pool with new reserves and consolidated token CIDs
let (finalTokenACid, finalTokenBCid) = if isAtoB
      then (consolidatedInCid, poolOutRemainderCid)
      else (poolOutRemainderCid, consolidatedInCid)

-- Update pool using choice (poolParty is controller)
newPool <- exercise poolCid P.ArchiveAndUpdateReserves with
  updatedReserveA = newReserveA
  updatedReserveB = newReserveB
  updatedTokenACid = Some finalTokenACid
  updatedTokenBCid = Some finalTokenBCid
```

**Exemple concret** (swap ETH → USDC) :

```
AVANT:
reserveA = 1000 ETH, reserveB = 2,000,000 USDC
tokenACid = pool's 1000 ETH, tokenBCid = pool's 2,000,000 USDC

INPUT:
inputSymbol = "ETH", inputAmount = 99.925 ETH
isAtoB = True (ETH → USDC)

CALCUL:
ainFee = 99.925 * 0.997 = 99.626 ETH
aout = (99.626 * 2000000) / (1000 + 99.626) = 181,277 USDC

NEW RESERVES:
newReserveA = 1000 + 99.925 = 1099.925 ETH (input was A)
newReserveB = 2000000 - 181277 = 1,818,723 USDC (output was B)

NEW TOKEN CIDS:
finalTokenACid = consolidatedInCid (1099.925 ETH consolidated)
finalTokenBCid = poolOutRemainderCid (1,818,723 USDC remainder)

INVARIANT CHECK:
k_before = 1000 * 2000000 = 2,000,000,000
k_after = 1099.925 * 1818723 ≈ 2,000,136,000
k_after > k_before ✓ (fees increase k!)
```

---

## 3. ATOMICSWAP.DAML - SWAP ATOMIQUE 1-STEP

### 3.1 Pourquoi un swap atomique ?

**Problème avec SwapRequest 2-step** :
- Trader appelle `PrepareSwap` → 1ère transaction DAML
- Pool appelle `ExecuteSwap` → 2ème transaction DAML
- **Latence** : 2 transactions = 2 RTT (Round-Trip Time) au ledger
- **État intermédiaire** : `SwapReady` existe entre les 2 transactions
- **Complexité backend** : Gérer 2 étapes, attendre le CID de SwapReady

**Solution AtomicSwapProposal** :
- ✅ 1 seule transaction DAML (tout ou rien)
- ✅ Latence minimale
- ✅ Pas d'état intermédiaire (pas de SwapReady dangling)
- ✅ Backend simplifié (1 seul appel REST)

### 3.2 Template AtomicSwapProposal

```daml
template AtomicSwapProposal
  with
    trader : Party
    poolCid : ContractId P.Pool          -- ← Pool discovered via PoolAnnouncement
    poolParty : Party
    poolOperator : Party
    issuerA : Party
    issuerB : Party
    symbolA : Text
    symbolB : Text
    feeBps : Int
    maxTTL : RelTime
    protocolFeeReceiver : Party          -- ← ClearportX treasury
    traderInputTokenCid : ContractId T.Token  -- ← Token du trader (avant tout)
    inputSymbol : Text
    inputAmount : Numeric 10             -- ← Montant AVANT protocol fee
    outputSymbol : Text
    minOutput : Numeric 10
    maxPriceImpactBps : Int
    deadline : Time
  where
    signatory trader
    observer poolParty
```

**Différences clés avec SwapRequest** :
- Nom du champ : `traderInputTokenCid` (au lieu de `inputTokenCid`)
- Ajout de : `protocolFeeReceiver` (passé directement, pas via choice parameter)
- Même signatories/observers (trader/poolParty)

### 3.3 Choice ExecuteAtomicSwap - All-in-One

```daml
choice ExecuteAtomicSwap : ContractId R.Receipt
  controller trader, poolParty  -- ← Both must authorize! (nested choices)
  do
    -- Step 1: Create SwapRequest (lines 40-57)
    swapRequestCid <- create SR.SwapRequest with
      trader = trader
      poolCid = poolCid
      poolParty = poolParty
      poolOperator = poolOperator
      issuerA = issuerA
      issuerB = issuerB
      symbolA = symbolA
      symbolB = symbolB
      feeBps = feeBps
      maxTTL = maxTTL
      inputTokenCid = traderInputTokenCid  -- ← Le token original du trader
      inputSymbol = inputSymbol
      inputAmount = inputAmount            -- ← Montant AVANT protocol fee
      outputSymbol = outputSymbol
      minOutput = minOutput
      deadline = deadline
      maxPriceImpactBps = maxPriceImpactBps

    -- Step 2: PrepareSwap (creates SwapReady) (lines 60-61)
    (swapReadyCid, _poolInputTokenCid) <- exercise swapRequestCid SR.PrepareSwap with
      protocolFeeReceiver = protocolFeeReceiver

    -- Step 3: ExecuteSwap immediately (in same transaction - atomic!) (line 64)
    receiptCid <- exercise swapReadyCid SR.ExecuteSwap

    return receiptCid
```

**Flow atomique complet** :

```
┌─────────────────────────────────────────────────────────────────┐
│ TRANSACTION DAML ATOMIQUE (1 seule TX, tout ou rien)            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│ INPUT: AtomicSwapProposal (trader owns traderInputTokenCid)     │
│                                                                  │
│ STEP 1: create SwapRequest                                      │
│   ┌─────────────────────────────────────────────┐              │
│   │ SwapRequest created on ledger               │              │
│   │ • trader: Alice                             │              │
│   │ • inputTokenCid: Alice's 100 ETH            │              │
│   │ • inputAmount: 100 ETH (before protocol)    │              │
│   └─────────────────────────────────────────────┘              │
│                                                                  │
│ STEP 2: exercise PrepareSwap                                    │
│   ┌─────────────────────────────────────────────┐              │
│   │ Protocol fee extraction:                    │              │
│   │ • TransferSplit: 0.075 ETH → treasury       │              │
│   │ • Transfer: 99.925 ETH → poolParty          │              │
│   │ SwapReady created:                          │              │
│   │ • poolInputCid: 99.925 ETH (pool owns)      │              │
│   │ • inputAmount: 99.925 (after protocol)      │              │
│   │ • protocolFeeAmount: 0.075                  │              │
│   └─────────────────────────────────────────────┘              │
│                                                                  │
│ STEP 3: exercise ExecuteSwap                                    │
│   ┌─────────────────────────────────────────────┐              │
│   │ AMM calculation:                            │              │
│   │ • aout = 181,277 USDC                       │              │
│   │ Consolidation:                              │              │
│   │ • Merge 99.925 ETH into pool canonical      │              │
│   │ Transfer output:                            │              │
│   │ • TransferSplit: 181,277 USDC → Alice       │              │
│   │ Update pool:                                │              │
│   │ • Archive old pool                          │              │
│   │ • Create new pool (1099.925 ETH, 1.818M USDC) │           │
│   │ Create Receipt:                             │              │
│   │ • amountIn: 99.925, amountOut: 181,277      │              │
│   │ • protocolFee: 0.075 ETH                    │              │
│   │ • price: 1814.7 USDC/ETH                    │              │
│   └─────────────────────────────────────────────┘              │
│                                                                  │
│ OUTPUT: Receipt (proof of swap execution)                       │
│                                                                  │
│ ATOMICITÉ:                                                       │
│ • Si une étape échoue → TOUT rollback (no partial state)        │
│ • Si tout réussit → Receipt créé (swap garanti exécuté)         │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Pourquoi `controller trader, poolParty` ?** (line 37)

```
ExecuteAtomicSwap calls:
│
├─> PrepareSwap (controller trader)
│   ├─> Transfer (controller owner = trader)
│   └─> TransferSplit (controller owner = trader)
│
└─> ExecuteSwap (controller poolParty)
    ├─> Merge (controller owner = poolParty)
    ├─> TransferSplit (controller owner = poolParty)
    └─> ArchiveAndUpdateReserves (controller poolParty)

Nested choices require ALL controllers to authorize!
Donc: controller trader, poolParty
```

**Backend REST API usage** :

```typescript
// Backend SwapController.java appelle DAML Ledger API
POST /api/swap/atomic
{
  "trader": "Alice::1220...",
  "poolId": "ETH-USDC-pool-0.3%",
  "inputSymbol": "ETH",
  "inputAmount": "100.0",
  "minOutput": "180000.0",
  "maxPriceImpactBps": 1000  // 10% max
}

Backend exécute:
1. Fetch poolCid from PoolAnnouncement discovery
2. Fetch trader's Token CID (inputSymbol)
3. Create AtomicSwapProposal
4. Exercise ExecuteAtomicSwap (1 TX DAML!)
5. Parse Receipt → Return to frontend

Response (1 seul RTT ledger!):
{
  "receiptCid": "00abc123...",
  "amountIn": "99.925",    // After protocol fee
  "amountOut": "181277.45",
  "protocolFee": "0.075",
  "price": "1814.7",
  "txHash": "a7f3c2d1..."
}
```

---

## 4. RECEIPT.DAML - PREUVE D'EXÉCUTION

### 4.1 Rôle du Receipt

Le `Receipt` est créé à la **fin de ExecuteSwap** (SwapRequest ou AtomicSwap). Il sert de :

1. **Audit trail permanent** : Historique immutable de tous les swaps
2. **Preuve d'exécution** : Trader peut prouver qu'il a reçu X tokens
3. **Source pour metrics** : Backend query les Receipts pour Grafana
4. **Debug tool** : Analyser les swaps qui ont échoué (via assertions)

### 4.2 Template Receipt

```daml
template Receipt
  with
    trader : Party                    -- ← Qui a exécuté le swap
    poolParty : Party                 -- ← Pool qui a exécuté
    inputSymbol : Text                -- ← "ETH"
    outputSymbol : Text               -- ← "USDC"
    amountIn : Numeric 10             -- ← 99.925 (APRÈS protocol fee)
    amountOut : Numeric 10            -- ← 181,277 USDC
    protocolFee : Numeric 10          -- ← 0.075 ETH (25% du total fee)
    price : Numeric 10                -- ← Quote per base (amountOut / amountIn)
    outputTokenCid : ContractId T.Token  -- ← Token reçu par le trader
    newPoolCid : ContractId P.Pool    -- ← Pool mis à jour
    timestamp : Time                  -- ← Quand le swap a été exécuté
  where
    signatory poolParty               -- ← Pool signe (source de vérité)
    observer trader                   -- ← Trader observe (peut voir son receipt)
```

**Points clés** :
- `signatory poolParty` (pas trader!) car le pool exécute ExecuteSwap
- `observer trader` permet au trader de query ses propres receipts
- `amountIn` est **APRÈS protocol fee** (99.925, pas 100)
- `price = amountOut / amountIn` = prix effectif du swap (avec slippage)

### 4.3 Choice AcknowledgeReceipt - Optional Cleanup

```daml
choice AcknowledgeReceipt : ()
  controller trader
  do
    archive self  -- ← Archive le receipt (cleanup optionnel)
    return ()
```

**Usage** :
- Trader peut archiver son receipt s'il n'en a plus besoin
- Réduit le nombre de contracts actifs (performance query)
- **Recommandation** : Ne PAS archiver en production (garder audit trail)

### 4.4 Backend Metrics Query

```java
// LedgerReader.java - Query tous les Receipts pour metrics
public List<Receipt.Contract> getAllReceipts() {
    return ledgerClient.getActiveContractSetClient()
        .getActiveContracts(
            new Receipt.ContractFilter(),
            Collections.emptySet(),
            false
        )
        .blockingIterable()
        .iterator();
}

// MetricsService.java - Calcul de metrics depuis Receipts
public SwapMetrics calculateSwapMetrics(List<Receipt.Contract> receipts) {
    return SwapMetrics.builder()
        .totalSwaps(receipts.size())
        .totalVolumeUSD(receipts.stream()
            .mapToDouble(r -> r.data.amountOut.doubleValue())
            .sum())
        .totalProtocolFees(receipts.stream()
            .mapToDouble(r -> r.data.protocolFee.doubleValue())
            .sum())
        .avgPrice(receipts.stream()
            .mapToDouble(r -> r.data.price.doubleValue())
            .average()
            .orElse(0.0))
        .build();
}
```

**Grafana Panels utilisant Receipts** :
- Total Swaps (count)
- Total Volume USD (sum of amountOut)
- Protocol Fees Collected (sum of protocolFee)
- Avg Swap Price by Pair (avg of price, groupé par inputSymbol-outputSymbol)
- Swaps per Hour (count groupé par timestamp buckets)

---

## 5. POOLANNOUNCEMENT.DAML - DÉCOUVERTE SANS CLÉS

### 5.1 Problème : Comment découvrir les pools sans contract keys ?

**DAML 3.3 ContractId-Only architecture** :
- ❌ Pas de `contract keys` (interdit par Canton Network)
- ❌ Impossible de faire `fetchByKey` ou `lookupByKey`
- ❌ Comment trouver le `ContractId` d'un pool ETH-USDC ?

**Solution traditionnelle (avec keys)** :
```daml
-- NE FONCTIONNE PAS EN DAML 3.3!
key (operator, symbolA, symbolB) : (Party, Text, Text)
pool <- lookupByKey @Pool (operator, "ETH", "USDC")
```

**Solution ClearportX (append-only announcements)** :
```daml
-- ✅ Fonctionne en DAML 3.3 ContractId-Only
announcements <- query @PoolAnnouncement  -- Off-ledger query (PQS)
let ethUsdcAnnouncements = filter (\a -> 
      a.symbolA == "ETH" && a.symbolB == "USDC") announcements
let bestPool = minimumBy (\a b -> compare a.feeBps b.feeBps) ethUsdcAnnouncements
```

### 5.2 Design Philosophy - Append-Only Immutability

```
┌─────────────────────────────────────────────────────────────────┐
│ TRADITIONAL MUTABLE REGISTRY (NOT USED)                         │
├─────────────────────────────────────────────────────────────────┤
│ template PoolRegistry                                            │
│   key (operator) : Party                                         │
│   choice RegisterPool : ()  ← Updates registry (archive+recreate)│
│                                                                  │
│ PROBLEMS:                                                        │
│ • ❌ Write contention (only 1 operator can update at a time)    │
│ • ❌ Race conditions (2 pools created simultaneously)            │
│ • ❌ Requires contract key (not allowed in DAML 3.3)            │
│ • ❌ Single point of failure (registry party)                   │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ CLEARPORTX APPEND-ONLY ANNOUNCEMENTS (USED) ✅                  │
├─────────────────────────────────────────────────────────────────┤
│ template PoolAnnouncement                                        │
│   NO contract key                                               │
│   NO choices (immutable announcement)                           │
│                                                                  │
│ CREATION:                                                        │
│ pool <- create Pool with ...                                    │
│ announcement <- create PoolAnnouncement with                     │
│   poolOperator = operator                                       │
│   poolId = "ETH-USDC-0.3%"                                      │
│   symbolA = "ETH", symbolB = "USDC"                             │
│   feeBps = 30                                                   │
│   createdAt = now                                               │
│                                                                  │
│ DISCOVERY (off-ledger query via PQS):                            │
│ SELECT * FROM pool_announcement                                 │
│ WHERE symbol_a = 'ETH' AND symbol_b = 'USDC'                    │
│ ORDER BY fee_bps ASC                                            │
│ LIMIT 1                                                          │
│                                                                  │
│ BENEFITS:                                                        │
│ • ✅ No write contention (each announcement is independent)     │
│ • ✅ Scalable (unlimited concurrent pool creations)             │
│ • ✅ No contract key needed (DAML 3.3 compatible)               │
│ • ✅ Decentralized (any party can announce pools)               │
│ • ✅ Historical record (all pools ever created)                 │
│ • ✅ Competing pools (multiple operators, same pair, different fees) │
└─────────────────────────────────────────────────────────────────┘
```

### 5.3 Template PoolAnnouncement

```daml
template PoolAnnouncement
  with
    poolOperator : Party    -- ← Qui a créé/opère le pool
    poolId : Text           -- ← ID unique (ex: "ETH-USDC-pool-0.3%")
    symbolA : Text          -- ← Premier token ("ETH")
    issuerA : Party         -- ← Émetteur de symbolA
    symbolB : Text          -- ← Deuxième token ("USDC")
    issuerB : Party         -- ← Émetteur de symbolB
    feeBps : Int            -- ← Fee en basis points (30 = 0.3%)
    maxTTL : RelTime        -- ← Max time-to-live pour les swaps
    createdAt : Time        -- ← Timestamp de création
  where
    signatory poolOperator
    observer issuerA, issuerB  -- ← Token issuers observe announcements

    -- Canonical ordering ensures consistency
    ensure
      (symbolA, show issuerA) < (symbolB, show issuerB) &&
      feeBps >= 0 && feeBps <= 10000  -- 0% to 100%

    -- NO CHOICES - This is an immutable announcement
    -- Once created, it exists forever for discovery
```

**Points clés** :
- **Pas de contract key** : Compatible DAML 3.3 ContractId-Only
- **Pas de choices** : Immutable (jamais archivé, jamais modifié)
- **Observer issuerA, issuerB** : Émetteurs de tokens voient les pools utilisant leurs tokens
- **Canonical ordering** : `(symbolA, issuerA) < (symbolB, issuerB)` garantit ordre unique

### 5.4 Usage Pattern - Création de Pool

```daml
-- Script de création de pool (InitializeClearportX.daml)
script
  alice <- allocateParty "Alice"
  bob <- allocateParty "Bob"
  ethIssuer <- allocateParty "ETHIssuer"
  usdcIssuer <- allocateParty "USDCIssuer"
  
  now <- getTime
  
  -- Step 1: Create Pool
  pool <- submit alice do
    createCmd Pool with
      poolParty = alice
      operator = alice
      issuerA = ethIssuer
      issuerB = usdcIssuer
      symbolA = "ETH"
      symbolB = "USDC"
      feeBps = 30  -- 0.3%
      reserveA = 1000.0
      reserveB = 2000000.0
      lpTokenSupply = 44721.35  -- sqrt(1000 * 2000000)
      tokenACid = Some ethTokenCid
      tokenBCid = Some usdcTokenCid
      lpHolders = []
      maxInBps = 1000   -- 10%
      maxOutBps = 1000  -- 10%
      maxTTL = hours 2
      maxPriceImpactBps = 5000  -- 50%
  
  -- Step 2: Announce Pool (append-only discovery)
  announcement <- submit alice do
    createCmd PoolAnnouncement with
      poolOperator = alice
      poolId = "ETH-USDC-pool-0.3%"
      symbolA = "ETH"
      issuerA = ethIssuer
      symbolB = "USDC"
      issuerB = usdcIssuer
      feeBps = 30
      maxTTL = hours 2
      createdAt = now
  
  return (pool, announcement)
```

### 5.5 Discovery Pattern - Backend Query

**Backend Java (LedgerReader.java)** :

```java
// Query all PoolAnnouncements (off-ledger via PQS)
public List<PoolAnnouncement.Contract> discoverPools(
    String symbolA, 
    String symbolB
) {
    var filter = new PoolAnnouncement.ContractFilter();
    
    return ledgerClient.getActiveContractSetClient()
        .getActiveContracts(filter, Collections.emptySet(), false)
        .blockingIterable()
        .stream()
        .filter(contract -> 
            contract.data.symbolA.equals(symbolA) &&
            contract.data.symbolB.equals(symbolB)
        )
        .sorted(Comparator.comparingInt(c -> c.data.feeBps))  // Lowest fees first
        .collect(Collectors.toList());
}

// Get best pool for a token pair
public Optional<ContractId> getBestPool(String symbolA, String symbolB) {
    var announcements = discoverPools(symbolA, symbolB);
    
    if (announcements.isEmpty()) {
        logger.warn("No pools found for pair {}-{}", symbolA, symbolB);
        return Optional.empty();
    }
    
    // Return pool with lowest fees
    var bestAnnouncement = announcements.get(0);
    logger.info("Best pool for {}-{}: {} (fee: {} bps)", 
        symbolA, symbolB, 
        bestAnnouncement.data.poolId, 
        bestAnnouncement.data.feeBps
    );
    
    // Fetch actual Pool contract using poolId
    return fetchPoolByPoolId(bestAnnouncement.data.poolId);
}
```

**PQS PostgreSQL Query** (query directe sur database) :

```sql
-- PQS indexe tous les contracts dans PostgreSQL
SELECT 
    pool_operator,
    pool_id,
    symbol_a,
    symbol_b,
    fee_bps,
    created_at,
    contract_id  -- ContractId pour fetch le Pool
FROM pool_announcement
WHERE symbol_a = 'ETH' 
  AND symbol_b = 'USDC'
ORDER BY fee_bps ASC, created_at DESC
LIMIT 1;
```

**Frontend Discovery** (TypeScript) :

```typescript
// Frontend découvre les pools disponibles
async function discoverETHUSDCPools(): Promise<PoolInfo[]> {
  const response = await fetch('/api/pools/discover', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      symbolA: 'ETH',
      symbolB: 'USDC'
    })
  });
  
  const pools = await response.json();
  
  // pools = [
  //   { poolId: "ETH-USDC-pool-0.3%", feeBps: 30, operator: "Alice::1220..." },
  //   { poolId: "ETH-USDC-pool-0.5%", feeBps: 50, operator: "Bob::1220..." },
  //   { poolId: "ETH-USDC-pool-1.0%", feeBps: 100, operator: "Charlie::1220..." }
  // ]
  
  return pools.sort((a, b) => a.feeBps - b.feeBps);
}
```

### 5.6 Competing Pools - Multiple Operators

```
Scénario: 3 opérateurs créent des pools ETH-USDC avec fees différents

┌─────────────────────────────────────────────────────────────────┐
│ POOL ANNOUNCEMENTS (append-only, never archived)                │
├─────────────────────────────────────────────────────────────────┤
│ 1. PoolAnnouncement                                              │
│    poolOperator: Alice                                          │
│    poolId: "ETH-USDC-pool-0.3%"                                 │
│    feeBps: 30 (0.3%)                                            │
│    createdAt: 2025-01-15 10:00:00                               │
│                                                                  │
│ 2. PoolAnnouncement                                              │
│    poolOperator: Bob                                            │
│    poolId: "ETH-USDC-pool-0.5%"                                 │
│    feeBps: 50 (0.5%)                                            │
│    createdAt: 2025-01-15 11:30:00                               │
│                                                                  │
│ 3. PoolAnnouncement                                              │
│    poolOperator: Charlie                                        │
│    poolId: "ETH-USDC-pool-1.0%"                                 │
│    feeBps: 100 (1.0%)                                           │
│    createdAt: 2025-01-15 14:00:00                               │
└─────────────────────────────────────────────────────────────────┘

Trader choice strategy:
• Lowest fees: Alice (0.3%) ← Best for large swaps
• Most liquidity: Bob (if he has 10x TVL) ← Best for low slippage
• Newest pool: Charlie (1.0%) ← Might have incentives (LP rewards)

Frontend UI:
╔═══════════════════════════════════════════════════════════════╗
║ ETH → USDC Swap                                               ║
╟───────────────────────────────────────────────────────────────╢
║ Available Pools:                                              ║
║ ┌─────────────────────────────────────────────────────────┐   ║
║ │ ✓ Alice's Pool (0.3% fee) - $2M TVL - RECOMMENDED      │   ║
║ │   Bob's Pool (0.5% fee) - $20M TVL                      │   ║
║ │   Charlie's Pool (1.0% fee) - $500K TVL                 │   ║
║ └─────────────────────────────────────────────────────────┘   ║
╚═══════════════════════════════════════════════════════════════╝
```

---

## 6. COMPARAISON DES DEUX APPROCHES

### 6.1 Two-Step vs One-Step - Tableau Comparatif

| Aspect                | Two-Step (SwapRequest)              | One-Step (AtomicSwap)               |
|-----------------------|-------------------------------------|-------------------------------------|
| **Transactions DAML** | 2 (PrepareSwap + ExecuteSwap)      | 1 (ExecuteAtomicSwap)               |
| **Latency**           | ~500ms par TX × 2 = ~1s total       | ~500ms (1 TX)                       |
| **Atomicity**         | Non (état SwapReady intermédiaire)  | Oui (tout ou rien)                  |
| **Cancelability**     | Oui (CancelSwapRequest choice)      | Non (atomique, pas de cancel)       |
| **Backend complexity**| Gérer 2 étapes, poll SwapReady CID  | 1 seul appel ledger API             |
| **Error handling**    | Peut fail à PrepareSwap OU ExecuteSwap | Fail once, rollback tout           |
| **Use case**          | Scripts DAML, tests, debugging      | Production backend REST API         |
| **Rate limiting**     | 2 TX = consomme 2 slots (0.4 TPS)   | 1 TX = consomme 1 slot              |
| **Observability**     | 2 étapes observables (metrics)      | 1 étape (moins granulaire)          |

### 6.2 Quand utiliser Two-Step ?

**Cas d'usage Two-Step (SwapRequest)** :

1. **Scripts DAML interactifs** :
   ```daml
   script
     swapRequest <- submit alice do createCmd SwapRequest with ...
     -- Alice peut réfléchir, analyser, décider...
     (swapReady, _) <- submit alice do exerciseCmd swapRequest PrepareSwap with ...
     -- Pool operator approve manuellement...
     receipt <- submit poolParty do exerciseCmd swapReady ExecuteSwap
   ```

2. **Tests unitaires DAML** :
   ```daml
   testPrepareSwapProtocolFee = script do
     -- Test UNIQUEMENT l'extraction du protocol fee
     (swapReady, poolTokenCid) <- submit trader do 
       exerciseCmd swapRequest PrepareSwap with protocolFeeReceiver = treasury
     
     -- Vérifier que le protocol fee est correct
     treasuryToken <- queryContractId treasury treasuryTokenCid
     assertMsg "Protocol fee incorrect" (treasuryToken.amount == 0.075)
   ```

3. **Debugging complexe** :
   ```daml
   -- Inspecter l'état SwapReady avant ExecuteSwap
   swapReady <- fetch swapReadyCid
   debug ("inputAmount after protocol fee:", swapReady.inputAmount)
   debug ("poolInputCid owner:", swapReady.poolParty)
   
   -- Puis exécuter ExecuteSwap
   receipt <- submit poolParty do exerciseCmd swapReadyCid ExecuteSwap
   ```

4. **Environnements avec approbation manuelle** :
   - Trader prépare le swap (PrepareSwap)
   - Pool operator review manuellement (compliance, AML)
   - Pool operator exécute (ExecuteSwap) seulement si approuvé

### 6.3 Quand utiliser One-Step ?

**Cas d'usage One-Step (AtomicSwap)** :

1. **Production backend REST API** (ClearportX) :
   ```java
   // SwapController.java
   @PostMapping("/api/swap/atomic")
   public ResponseEntity<SwapResponse> executeAtomicSwap(@RequestBody SwapRequest req) {
       // 1 seul appel DAML Ledger API
       var receiptCid = ledgerApi.createAndExercise(
           new AtomicSwapProposal(...),
           AtomicSwapProposal.CHOICE_ExecuteAtomicSwap
       );
       
       return ResponseEntity.ok(new SwapResponse(receiptCid));
   }
   ```

2. **Latency-sensitive applications** :
   - Frontend DEX (UX optimale)
   - Arbitrage bots (speed critical)
   - Market makers (high-frequency)

3. **Rate-limited environments** (Canton Network devnet) :
   - 0.4 TPS global limit
   - AtomicSwap = 1 TX (moins de pression sur rate limiter)
   - Two-Step = 2 TX (2× plus de slots consommés)

4. **Simplified error handling** :
   ```typescript
   try {
     const receipt = await ledger.executeAtomicSwap({...});
     console.log("Swap succeeded:", receipt);
   } catch (err) {
     // Si ça fail, RIEN n'a changé (atomicité garantie)
     console.error("Swap failed (no partial state):", err);
   }
   ```

### 6.4 ClearportX Choice - Why AtomicSwap ?

**Décision ClearportX** : Utiliser **AtomicSwapProposal** pour le backend production.

**Justification** :
1. **Devnet rate limit** : 0.4 TPS → minimiser # transactions
2. **UX** : Frontend veut réponse immédiate (pas d'attente SwapReady)
3. **Simplicité backend** : 1 seul endpoint REST `/api/swap/atomic`
4. **Robustesse** : Atomicité garantit no partial swaps (pas de SwapReady dangling)
5. **Metrics** : Receipt suffit pour observability (pas besoin de tracker SwapReady)

**Code backend actuel** (SwapController.java) :

```java
// Backend utilise AtomicSwap, PAS SwapRequest
public class SwapController {
    
    @PostMapping("/api/swap/atomic")
    @RateLimited(tps = 0.4)  // Devnet compliance
    public ResponseEntity<SwapResponse> executeSwap(@RequestBody SwapRequest request) {
        
        // Idempotency check (prevent double-swaps)
        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyService.isDuplicate(idempotencyKey)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new SwapResponse("DUPLICATE_SWAP"));
        }
        
        // Discover pool from PoolAnnouncement
        var poolCid = ledgerReader.getBestPool(
            request.getInputSymbol(), 
            request.getOutputSymbol()
        );
        
        // Create AtomicSwapProposal
        var proposal = new AtomicSwapProposal(
            trader,
            poolCid,
            poolParty,
            poolOperator,
            issuerA, issuerB,
            symbolA, symbolB,
            feeBps,
            maxTTL,
            protocolFeeReceiver,
            traderInputTokenCid,
            inputSymbol,
            inputAmount,
            outputSymbol,
            minOutput,
            maxPriceImpactBps,
            deadline
        );
        
        // Execute atomic swap (1 TX DAML)
        var receiptCid = ledgerApi.createAndExercise(
            proposal,
            AtomicSwapProposal.CHOICE_ExecuteAtomicSwap
        );
        
        // Cache idempotency key (15 min TTL)
        idempotencyService.cache(idempotencyKey, receiptCid);
        
        // Increment metrics
        metricsService.recordSwap(inputSymbol, outputSymbol, inputAmount);
        
        // Return Receipt to frontend
        return ResponseEntity.ok(new SwapResponse(receiptCid));
    }
}
```

---

## 7. FLUX DE DONNÉES COMPLETS

### 7.1 Flow End-to-End - Two-Step Swap

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ TWO-STEP SWAP FLOW (SwapRequest → SwapReady → Receipt)                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│ STEP 0: Pool Discovery                                                      │
│   Frontend → Backend: GET /api/pools/discover?pair=ETH-USDC                 │
│   Backend → PQS: SELECT * FROM pool_announcement WHERE ...                  │
│   PQS → Backend: [{ poolId: "ETH-USDC-0.3%", feeBps: 30, ... }]             │
│   Backend → Frontend: { pools: [...] }                                      │
│                                                                              │
│ STEP 1: Create SwapRequest                                                  │
│   Frontend → Backend: POST /api/swap/request                                │
│   {                                                                          │
│     trader: "Alice::1220...",                                               │
│     poolId: "ETH-USDC-0.3%",                                                │
│     inputSymbol: "ETH",                                                     │
│     inputAmount: "100.0",                                                   │
│     minOutput: "180000.0"                                                   │
│   }                                                                          │
│                                                                              │
│   Backend → DAML Ledger:                                                    │
│   create SwapRequest with                                                   │
│     trader = Alice                                                          │
│     poolCid = 00abc123... (discovered)                                      │
│     inputTokenCid = Alice's 100 ETH                                         │
│     inputAmount = 100.0                                                     │
│     minOutput = 180000.0                                                    │
│     ...                                                                      │
│                                                                              │
│   DAML Ledger → Backend: swapRequestCid = 00def456...                       │
│   Backend → Frontend: { swapRequestCid: "00def456..." }                     │
│                                                                              │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                                                                              │
│ STEP 2: PrepareSwap (Protocol Fee Extraction)                               │
│   Frontend → Backend: POST /api/swap/prepare                                │
│   { swapRequestCid: "00def456..." }                                         │
│                                                                              │
│   Backend → DAML Ledger:                                                    │
│   exercise swapRequestCid PrepareSwap with                                  │
│     protocolFeeReceiver = ClearportXTreasury                                │
│                                                                              │
│   DAML Ledger execution:                                                    │
│   ┌──────────────────────────────────────────────────────────┐             │
│   │ 1. TransferSplit inputTokenCid (100 ETH):                │             │
│   │    → 0.075 ETH to ClearportXTreasury (protocol fee)      │             │
│   │    → 99.925 ETH remainder (still Alice)                  │             │
│   │                                                           │             │
│   │ 2. Transfer remainder to poolParty:                      │             │
│   │    → poolParty owns 99.925 ETH                           │             │
│   │                                                           │             │
│   │ 3. Create SwapReady:                                     │             │
│   │    inputAmount = 99.925 (AFTER protocol fee)            │             │
│   │    poolInputCid = poolParty's 99.925 ETH CID            │             │
│   │    protocolFeeAmount = 0.075                             │             │
│   └──────────────────────────────────────────────────────────┘             │
│                                                                              │
│   DAML Ledger → Backend:                                                    │
│   (swapReadyCid, poolInputTokenCid) = (00ghi789..., 00jkl012...)            │
│                                                                              │
│   Backend → Frontend:                                                       │
│   {                                                                          │
│     swapReadyCid: "00ghi789...",                                            │
│     amountAfterProtocolFee: "99.925",                                       │
│     protocolFee: "0.075"                                                    │
│   }                                                                          │
│                                                                              │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                                                                              │
│ STEP 3: ExecuteSwap (AMM Calculation & Swap)                                │
│   Backend triggers (automatic or manual):                                   │
│   POST /api/swap/execute { swapReadyCid: "00ghi789..." }                    │
│                                                                              │
│   Backend → DAML Ledger:                                                    │
│   exercise swapReadyCid ExecuteSwap                                         │
│                                                                              │
│   DAML Ledger execution:                                                    │
│   ┌──────────────────────────────────────────────────────────┐             │
│   │ 1. Fetch pool (reserveA=1000, reserveB=2000000)          │             │
│   │ 2. Validations (deadline, reserves > 0, ...)             │             │
│   │ 3. AMM calculation:                                      │             │
│   │    ainFee = 99.925 * 0.997 = 99.626 ETH                 │             │
│   │    aout = (99.626 * 2000000) / (1000 + 99.626)          │             │
│   │         = 181,277 USDC                                   │             │
│   │ 4. Slippage check: 181,277 >= 180,000 ✓                 │             │
│   │ 5. Price impact: 17.32% < 50% ✓                         │             │
│   │ 6. Merge 99.925 ETH into pool canonical                 │             │
│   │ 7. TransferSplit 181,277 USDC to Alice                  │             │
│   │ 8. Archive old pool, create new pool:                   │             │
│   │    reserveA = 1099.925 ETH                               │             │
│   │    reserveB = 1,818,723 USDC                             │             │
│   │ 9. Create Receipt                                        │             │
│   └──────────────────────────────────────────────────────────┘             │
│                                                                              │
│   DAML Ledger → Backend: receiptCid = 00mno345...                           │
│                                                                              │
│   Backend → Frontend:                                                       │
│   {                                                                          │
│     receiptCid: "00mno345...",                                              │
│     amountIn: "99.925",                                                     │
│     amountOut: "181277.0",                                                  │
│     protocolFee: "0.075",                                                   │
│     price: "1814.7",                                                        │
│     txHash: "a7f3c2d1..."                                                   │
│   }                                                                          │
│                                                                              │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                                                                              │
│ STEP 4: Metrics Update                                                      │
│   Backend → Prometheus:                                                     │
│   swap_total{pair="ETH-USDC"}.inc()                                         │
│   swap_volume_usd{pair="ETH-USDC"}.add(181277)                              │
│   protocol_fees_usd{token="ETH"}.add(0.075 * ethPrice)                      │
│                                                                              │
│   Grafana queries Prometheus → Dashboard updated                            │
│                                                                              │
│ TOTAL LATENCY: ~1000ms (2 transactions DAML)                                │
│ TOTAL TRANSACTIONS: 3 (SwapRequest + PrepareSwap + ExecuteSwap)             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.2 Flow End-to-End - One-Step Atomic Swap

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ ONE-STEP ATOMIC SWAP FLOW (AtomicSwapProposal → Receipt)                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│ STEP 0: Pool Discovery (same as two-step)                                   │
│   Frontend → Backend → PQS → Backend → Frontend                             │
│                                                                              │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                                                                              │
│ STEP 1: ExecuteAtomicSwap (ALL IN ONE TRANSACTION!)                         │
│   Frontend → Backend: POST /api/swap/atomic                                 │
│   {                                                                          │
│     trader: "Alice::1220...",                                               │
│     poolId: "ETH-USDC-0.3%",                                                │
│     inputSymbol: "ETH",                                                     │
│     inputAmount: "100.0",                                                   │
│     minOutput: "180000.0",                                                  │
│     idempotencyKey: "swap-12345-abc"  ← Prevent duplicate swaps            │
│   }                                                                          │
│                                                                              │
│   Backend checks:                                                           │
│   1. Rate limiter: 0.4 TPS (devnet compliance) ✓                            │
│   2. Idempotency: not a duplicate ✓                                         │
│   3. Discover poolCid from PoolAnnouncement ✓                               │
│   4. Fetch trader's inputTokenCid ✓                                         │
│                                                                              │
│   Backend → DAML Ledger (1 TRANSACTION ATOMIQUE):                           │
│   create AtomicSwapProposal with                                            │
│     trader = Alice                                                          │
│     poolCid = 00abc123...                                                   │
│     traderInputTokenCid = Alice's 100 ETH                                   │
│     protocolFeeReceiver = ClearportXTreasury                                │
│     inputAmount = 100.0                                                     │
│     minOutput = 180000.0                                                    │
│     ...                                                                      │
│   exercise ExecuteAtomicSwap                                                │
│                                                                              │
│   DAML Ledger execution (ATOMIC - all or nothing):                          │
│   ┌──────────────────────────────────────────────────────────┐             │
│   │ SUB-STEP 1: create SwapRequest                           │             │
│   │   swapRequestCid = 00def456...                           │             │
│   │                                                           │             │
│   │ SUB-STEP 2: exercise PrepareSwap                         │             │
│   │   • TransferSplit: 0.075 ETH → treasury                  │             │
│   │   • Transfer: 99.925 ETH → poolParty                     │             │
│   │   • create SwapReady                                     │             │
│   │   swapReadyCid = 00ghi789...                             │             │
│   │                                                           │             │
│   │ SUB-STEP 3: exercise ExecuteSwap                         │             │
│   │   • Fetch pool (reserveA=1000, reserveB=2000000)         │             │
│   │   • Validations (deadline, reserves, ...)                │             │
│   │   • AMM: aout = 181,277 USDC                             │             │
│   │   • Merge 99.925 ETH into pool                           │             │
│   │   • TransferSplit 181,277 USDC to Alice                  │             │
│   │   • Archive old pool, create new pool                    │             │
│   │   • create Receipt                                       │             │
│   │   receiptCid = 00mno345...                               │             │
│   │                                                           │             │
│   │ ATOMICITY: If ANY sub-step fails → ROLLBACK ALL         │             │
│   └──────────────────────────────────────────────────────────┘             │
│                                                                              │
│   DAML Ledger → Backend: receiptCid = 00mno345...                           │
│                                                                              │
│   Backend post-processing:                                                  │
│   1. Cache idempotency key (15 min TTL)                                     │
│   2. Update metrics (Prometheus)                                            │
│   3. Return response                                                        │
│                                                                              │
│   Backend → Frontend:                                                       │
│   {                                                                          │
│     receiptCid: "00mno345...",                                              │
│     amountIn: "99.925",                                                     │
│     amountOut: "181277.0",                                                  │
│     protocolFee: "0.075",                                                   │
│     price: "1814.7",                                                        │
│     txHash: "a7f3c2d1..."                                                   │
│   }                                                                          │
│                                                                              │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                                                                              │
│ STEP 2: Metrics Update (same as two-step)                                   │
│   Backend → Prometheus → Grafana                                            │
│                                                                              │
│ TOTAL LATENCY: ~500ms (1 TRANSACTION DAML)                                  │
│ TOTAL TRANSACTIONS: 1 (AtomicSwapProposal.ExecuteAtomicSwap)                │
│                                                                              │
│ BENEFITS:                                                                    │
│ • ✅ 2× faster (500ms vs 1000ms)                                            │
│ • ✅ Atomic (no partial state if fail)                                      │
│ • ✅ Simpler backend (1 endpoint, 1 call)                                   │
│ • ✅ Rate limit friendly (0.4 TPS devnet)                                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## RÉSUMÉ MODULE 03

Ce module couvre le **système de swap complet** de ClearportX :

1. **SwapRequest.daml** : Pattern 2-step (PrepareSwap + ExecuteSwap)
   - ✅ Protocol fee extraction (25% ClearportX, 75% pool)
   - ✅ Flexibilité (trader peut cancel)
   - ✅ Observabilité (2 étapes distinctes)
   - ❌ Latence (2 transactions DAML)

2. **AtomicSwap.daml** : Pattern 1-step atomique
   - ✅ Latence minimale (1 transaction)
   - ✅ Atomicité garantie (tout ou rien)
   - ✅ Rate limit friendly (devnet 0.4 TPS)
   - ❌ Moins flexible (pas de cancel)

3. **Receipt.daml** : Preuve d'exécution
   - Audit trail permanent
   - Source pour metrics Prometheus/Grafana
   - Observer pattern (trader + poolParty)

4. **PoolAnnouncement.daml** : Découverte sans clés
   - Append-only immutable announcements
   - No write contention (scalable)
   - Compatible DAML 3.3 ContractId-Only
   - Multiple competing pools (même pair, fees différents)

**Next Steps** : Module 04 (Backend Controllers - REST API).

