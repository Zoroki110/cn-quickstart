# Module 02 - DAML Core Templates

**Version**: 2.0.0
**Dernière mise à jour**: 20 Octobre 2025  
**Prérequis**: [Module 01 - Architecture](./01_ARCHITECTURE_GLOBALE.md)
**Temps de lecture**: ~2 heures

[← Module précédent: Architecture](./01_ARCHITECTURE_GLOBALE.md) | [Module suivant: DAML Swap System →](./03_DAML_SWAP_SYSTEM.md)

---

## Table des Matières

1. [Pool.daml - Le Cœur de l'AMM](#1-pooldaml---le-cœur-de-lamm)
2. [Token.daml - Tokens Fungibles](#2-tokendaml---tokens-fungibles)
3. [LPToken.daml - Liquidity Provider Tokens](#3-lptokendaml---liquidity-provider-tokens)

---

## 1. Pool.daml - Le Cœur de l'AMM

**Fichier source**: `clearportx/daml/AMM/Pool.daml` (446 lignes)

### 1.1 Template Pool - Structure

```haskell
-- Lines 31-51: Template definition
template Pool
  with
    poolOperator : Party           -- ← Admin du pool
    poolParty : Party              -- ← Owner des tokens dans le pool
    lpIssuer : Party               -- ← Créateur des LP tokens
    issuerA : Party                -- ← Issuer du tokenA (ex: USDC issuer)
    issuerB : Party                -- ← Issuer du tokenB (ex: ETH issuer)
    symbolA : Text                 -- ← "USDC"
    symbolB : Text                 -- ← "ETH"
    feeBps : Int                   -- ← Fee en basis points (30 = 0.3%)
    poolId : Text                  -- ← Unique identifier "USDC-ETH"
    maxTTL : RelTime               -- ← Time-to-live pour operations
    totalLPSupply : Numeric 10     -- ← Total LP tokens mintés
    reserveA : Numeric 10          -- ← Réserve actuelle tokenA
    reserveB : Numeric 10          -- ← Réserve actuelle tokenB
    tokenACid : Optional (ContractId T.Token)  -- ← CID du token A dans pool
    tokenBCid : Optional (ContractId T.Token)  -- ← CID du token B dans pool
    protocolFeeReceiver : Party    -- ← ClearportX treasury (25% fees)
    maxInBps : Int                 -- ← Max input (ex: 10000 = 100% reserve)
    maxOutBps : Int                -- ← Max output (ex: 5000 = 50% reserve)
  where
    signatory poolOperator
    observer poolParty, lpIssuer, issuerA, issuerB, protocolFeeReceiver
```

**Pourquoi ces champs?**

| Champ | Raison |
|-------|--------|
| `totalLPSupply` | Tracker combien de LP tokens existent (pour calcul proportions) |
| `reserveA`, `reserveB` | État du pool pour formule AMM (x * y = k) |
| `tokenACid`, `tokenBCid` | **ContractId-Only architecture** - pas de contract keys en DAML 3.3! |
| `protocolFeeReceiver` | 25% des fees vont ici (ClearportX treasury) |
| `maxInBps`, `maxOutBps` | Protection contre swaps trop gros qui vident le pool |

### 1.2 AddLiquidity Choice (Lines 72-153)

**Objectif**: Ajouter de la liquidité au pool et recevoir des LP tokens

```haskell
-- Lines 72-81: Signature du choice
nonconsuming choice AddLiquidity : (ContractId LP.LPToken, ContractId Pool)
  with
    provider : Party                -- ← Qui ajoute la liquidité
    tokenACid : ContractId T.Token  -- ← Son token A
    tokenBCid : ContractId T.Token  -- ← Son token B  
    amountA : Numeric 10            -- ← Combien de A à déposer
    amountB : Numeric 10            -- ← Combien de B à déposer
    minLPTokens : Numeric 10        -- ← Slippage protection
    deadline : Time                 -- ← Expiration
  controller provider, poolParty, lpIssuer
```

**Flow détaillé**:

```
1. Validations (lines 84-104)
   ├─ Deadline check
   ├─ Token symbols match pool
   ├─ Token issuers match pool
   └─ Minimum liquidity (anti-dust attack)

2. Calcul LP tokens (lines 107-112)
   ├─ Si totalLPSupply == 0 (first liquidity):
   │  └─ lpTokens = sqrt(amountA * amountB)  ← Geometric mean
   │     Exemple: sqrt(100 * 1) = 10 LP tokens
   │
   └─ Sinon (subsequent liquidity):
      └─ lpTokens = min(
           amountA * totalLPSupply / reserveA,  ← Proportion A
           amountB * totalLPSupply / reserveB   ← Proportion B
         )
         Prend le min pour éviter arbitrage

3. Slippage protection (line 115)
   └─ assertMsg "Slippage" (lpTokens >= minLPTokens)

4. Transfer tokens to pool (lines 119-125)
   ├─ If provider == poolParty:
   │  └─ Bootstrap case, skip transfer
   └─ Else:
      └─ exercise tokenACid Transfer with recipient = poolParty

5. Consolidation (lines 129-135)
   ├─ Pool a déjà des tokens?
   │  ├─ Oui: Merge nouveaux avec existants
   │  └─ Non: Les nouveaux deviennent canoniques
   │
   └─ finalTokenACid = case this.tokenACid of
        None -> newTokenA           ← First liquidity
        Some existing -> exercise existing Merge with newTokenA

6. Mint LP tokens (lines 138-142)
   └─ create LPToken with
        issuer = lpIssuer
        owner = provider           ← Provider reçoit les LP tokens
        amount = lpTokensToMint

7. Archive-and-recreate pool (lines 145-151)
   ├─ archive self                ← Archive ancien Pool
   └─ create this with            ← Create nouveau Pool avec:
        totalLPSupply = old + lpTokensToMint
        reserveA = old + amountA
        reserveB = old + amountB
        tokenACid = Some finalTokenACid
        tokenBCid = Some finalTokenBCid
```

**Formule LP Tokens - Détail Mathématique**:

```
First Liquidity:
  lpTokens = sqrt(amountA * amountB)

  Pourquoi geometric mean?
  - Fair pour les 2 assets
  - Évite manipulation du ratio initial
  
  Exemple:
    Ajoute 100 USDC + 1 ETH
    lpTokens = sqrt(100 * 1) = sqrt(100) = 10 LP tokens

Subsequent Liquidity:
  lpTokens = min(
    amountA * totalLPSupply / reserveA,
    amountB * totalLPSupply / reserveB
  )
  
  Pourquoi min()?
  - Empêche de manipuler le ratio du pool
  - Provider doit respecter le ratio actuel
  - L'excess token reste au provider
  
  Exemple:
    Pool actuel: 200 USDC, 2 ETH, 20 LP supply
    Provider veut ajouter: 50 USDC, 0.5 ETH
    
    Via USDC: 50 * 20 / 200 = 5 LP tokens
    Via ETH:  0.5 * 20 / 2 = 5 LP tokens
    
    min(5, 5) = 5 LP tokens ✅
    
    Si provider avait mis 50 USDC + 0.4 ETH:
    Via USDC: 50 * 20 / 200 = 5 LP
    Via ETH:  0.4 * 20 / 2 = 4 LP
    
    min(5, 4) = 4 LP tokens ← Limitant factor
    (Provider devrait ajuster à 40 USDC + 0.4 ETH pour ratio optimal)
```

### 1.3 RemoveLiquidity Choice (Lines 156-240)

**Objectif**: Burn LP tokens et retirer liquidité proportionnelle

```haskell
-- Lines 156-164: Signature
nonconsuming choice RemoveLiquidity : 
    (ContractId T.Token, ContractId T.Token, ContractId Pool)
  with
    provider : Party
    lpTokenCid : ContractId LP.LPToken
    lpTokenAmount : Numeric 10
    minAmountA : Numeric 10  -- ← Slippage protection
    minAmountB : Numeric 10
    deadline : Time
  controller provider, poolParty, lpIssuer
```

**Flow détaillé**:

```
1. Validations (lines 167-193)
   ├─ Deadline check
   ├─ lpTokenAmount > 0
   ├─ Pool has tokens (can't remove from empty pool)
   └─ Pool tokens belong to poolParty

2. Calculate withdrawal amounts (lines 196-198)
   ├─ shareRatio = lpTokenAmount / totalLPSupply
   │  Exemple: Burn 5 LP sur 20 total = 0.25 (25%)
   │
   ├─ amountAOut = reserveA * shareRatio
   │  Exemple: 200 USDC * 0.25 = 50 USDC
   │
   └─ amountBOut = reserveB * shareRatio
      Exemple: 2 ETH * 0.25 = 0.5 ETH

3. Slippage protection (lines 201-202)
   └─ assertMsg (amountAOut >= minAmountA && amountBOut >= minAmountB)

4. Burn LP tokens (line 210)
   └─ exercise lpTokenCid LP.Burn with qty = lpTokenAmount

5. Transfer tokens to provider (lines 214-215)
   ├─ tokenAOut = exercise poolTokenACid Transfer with
   │                recipient = provider, qty = amountAOut
   └─ tokenBOut = exercise poolTokenBCid Transfer with
                    recipient = provider, qty = amountBOut

6. Handle empty pool edge case (lines 218-229)
   ├─ If newReserveA < 0.001 OR newReserveB < 0.001:
   │  └─ Reset pool to empty state:
   │     totalLPSupply = 0
   │     reserveA = 0
   │     reserveB = 0
   │     tokenACid = None
   │     tokenBCid = None
   │
   └─ Pourquoi? Ensure invariant: totalLPSupply > 0 => reserves > 0

7. Archive-and-recreate pool (lines 232-238)
   └─ create this with updated values
```

### 1.4 AtomicSwap Choice (Lines 335-446)

**Objectif**: Swap en 1 étape (vs 2-step PrepareSwap/ExecuteSwap)

**Pourquoi AtomicSwap au lieu de 2-step?**
- ✅ **Évite stale poolCid problem**: Swap A et Swap B simultanés → Swap B fail car poolCid archivé
- ✅ **Plus simple pour l'utilisateur**: 1 transaction au lieu de 2
- ✅ **Atomique**: Tout succeed ou tout fail

```haskell
-- Lines 335-345: Signature
nonconsuming choice AtomicSwap : (ContractId T.Token, ContractId Pool)
  with
    trader : Party
    traderInputTokenCid : ContractId T.Token
    inputSymbol : Text          -- ← "USDC"
    inputAmount : Numeric 10    -- ← 100.0
    outputSymbol : Text         -- ← "ETH"
    minOutput : Numeric 10      -- ← 0.03 (slippage protection)
    maxPriceImpactBps : Int     -- ← 100 (1% max price impact)
    deadline : Time
  controller trader, poolParty
```

**Flow complet AtomicSwap**:

```
ÉTAPE 1: VALIDATIONS (lines 348-369)
├─ Deadline check
├─ inputAmount > 0
├─ inputSymbol ∈ {symbolA, symbolB}
├─ outputSymbol ∈ {symbolA, symbolB}  
├─ inputSymbol ≠ outputSymbol
├─ maxPriceImpactBps <= 5000 (max 50%)
└─ Determine swap direction (A→B or B→A)

ÉTAPE 2: PROTOCOL FEE EXTRACTION (lines 372-383)
├─ totalFeeRate = feeBps / 10000
│  Exemple: 30 / 10000 = 0.003 (0.3%)
│
├─ totalFeeAmount = inputAmount * totalFeeRate  
│  Exemple: 100 * 0.003 = 0.3 USDC
│
├─ protocolFeeAmount = totalFeeAmount * 0.25
│  Exemple: 0.3 * 0.25 = 0.075 USDC (25% to ClearportX)
│
├─ poolFeeAmount = totalFeeAmount * 0.75
│  Exemple: 0.3 * 0.75 = 0.225 USDC (75% stays in pool)
│
└─ Split token: exercise traderInputTokenCid TransferSplit
     recipient = protocolFeeReceiver
     qty = protocolFeeAmount
     
   Résultat:
   ├─ protocolFeeCid (0.075 USDC) → ClearportX treasury
   └─ remainderCid (99.925 USDC) → Continue le swap

ÉTAPE 3: TRANSFER REMAINDER TO POOL (lines 386-388)
└─ poolInputTokenCid = exercise remainderCid Transfer with
     recipient = poolParty
     qty = amountAfterProtocolFee (99.925 USDC)

ÉTAPE 4: AMM CALCULATION (lines 391-394)
├─ feeMul = (10000 - feeBps) / 10000
│  Exemple: (10000 - 30) / 10000 = 0.997
│
├─ ainFee = amountAfterProtocolFee * feeMul
│  Exemple: 99.925 * 0.997 = 99.624 USDC
│
├─ denom = rin + ainFee
│  Exemple (si pool a 200 USDC): 200 + 99.624 = 299.624
│
└─ aout = (ainFee * rout) / denom
   Exemple (si pool a 2 ETH):
   aout = (99.624 * 2) / 299.624 = 0.665 ETH
   
   Formule Constant Product AMM:
   x * y = k
   (rin + ain) * (rout - aout) = rin * rout
   
   Résout pour aout:
   aout = (ain * rout) / (rin + ain)

ÉTAPE 5: VALIDATIONS OUTPUT (lines 397-409)
├─ Slippage check:
│  assertMsg (aout >= minOutput)
│  Exemple: 0.665 >= 0.03 ✅
│
├─ Liquidity check:
│  assertMsg (aout < rout)
│  Exemple: 0.665 < 2 ✅
│
├─ Max input check:
│  maxInputAmount = rin * maxInBps / 10000
│  assertMsg (amountAfterProtocolFee <= maxInputAmount)
│
├─ Max output check:
│  maxOutputAmount = rout * maxOutBps / 10000
│  assertMsg (aout <= maxOutputAmount)
│
└─ Price impact check:
   pBefore = rout / rin
   Exemple: 2 / 200 = 0.01 (1 ETH = 100 USDC)
   
   pAfter = (rout - aout) / (rin + amountAfterProtocolFee)
   Exemple: (2 - 0.665) / (200 + 99.925) = 0.00445
   
   impBps = |pAfter - pBefore| / pBefore * 10000
   Exemple: |0.00445 - 0.01| / 0.01 * 10000 = 5550 bps = 55.5%
   
   assertMsg (impBps <= maxPriceImpactBps)

ÉTAPE 6: CONSOLIDATE INPUT TOKENS (lines 412-414)
└─ Merge poolInputTokenCid avec pool's canonical token
   consolidatedInCid = exercise canonicalCid Merge with poolInputTokenCid

ÉTAPE 7: TRANSFER OUTPUT TO TRADER (lines 417-422)
└─ Split pool's output token:
   (poolOutRemainderCid, traderOutputCid) = 
     exercise canonicalOutCid TransferSplit with
       recipient = trader
       qty = aout

ÉTAPE 8: UPDATE RESERVES (lines 425-444)
├─ Calculate new reserves:
│  If A→B swap:
│    newReserveA = reserveA + amountAfterProtocolFee
│    newReserveB = reserveB - aout
│  If B→A swap:
│    newReserveA = reserveA - aout
│    newReserveB = reserveB + amountAfterProtocolFee
│
└─ Archive-and-recreate pool:
   exercise self ArchiveAndUpdateReserves with
     updatedReserveA = newReserveA
     updatedReserveB = newReserveB
     updatedTokenACid = Some finalTokenACid
     updatedTokenBCid = Some finalTokenBCid
```

**Exemple Complet de Swap**:

```
État Initial du Pool:
  reserveA = 1000 USDC
  reserveB = 10 ETH
  k = 1000 * 10 = 10,000

Trader veut swap:
  Input: 100 USDC
  Output: ETH
  minOutput: 0.9 ETH
  maxPriceImpactBps: 200 (2%)

Calculs:
1. Protocol fee:
   totalFee = 100 * 0.003 = 0.3 USDC
   protocolFee = 0.3 * 0.25 = 0.075 USDC → ClearportX
   amountForPool = 100 - 0.075 = 99.925 USDC

2. AMM output:
   feeMul = 0.997
   ainFee = 99.925 * 0.997 = 99.624 USDC
   
   aout = (99.624 * 10) / (1000 + 99.624)
        = 996.24 / 1099.624
        = 0.906 ETH

3. Slippage check:
   0.906 >= 0.9 ✅ OK

4. Price impact:
   pBefore = 10 / 1000 = 0.01 (1 ETH = 100 USDC)
   pAfter = (10 - 0.906) / (1000 + 99.925) = 0.00827
   impact = |0.00827 - 0.01| / 0.01 * 10000 = 1730 bps = 17.3%
   
   17.3% > 2% ❌ FAIL - Price impact trop élevé!
   
   Trader doit:
   - Réduire inputAmount (ex: 20 USDC au lieu de 100)
   - OU augmenter maxPriceImpactBps
   - OU split le swap en plusieurs petits swaps

5. New pool state (si swap passait):
   newReserveA = 1000 + 99.925 = 1099.925 USDC
   newReserveB = 10 - 0.906 = 9.094 ETH
   newK = 1099.925 * 9.094 = 10,002.6 ≈ 10,000 ✅
   
   (Le k augmente légèrement grâce aux fees!)
```

---

## 2. Token.daml - Tokens Fungibles

**Fichier source**: `clearportx/daml/Token/Token.daml`

### 2.1 Design Token - Trust Model

**⚠️ CRITICAL: Token Design nécessite TRUST COMPLET dans l'issuer!**

```haskell
-- Lines 51-58: Template structure
template Token
  with
    issuer : Party    -- ← SEUL signatory (pas owner!)
    owner  : Party    -- ← Juste metadata, PAS signatory
    symbol : Text     -- ← "USDC", "ETH", etc.
    amount : Numeric 10
  where
    signatory issuer  -- ← ISSUER contrôle tout!
    observer owner    -- ← Owner peut juste voir
```

**Pourquoi issuer seul signatory?**

```
Problème à résoudre: AMM atomique

Si owner était signatory:
  - Swap nécessiterait authorization du recipient
  - Impossible en 1 transaction atomique
  - Besoin de Proposal-Accept pattern (4 transactions!)
  
Solution: issuer = sole signatory
  - Issuer peut créer/transférer tokens sans auth du owner
  - Enables atomic swaps
  - Trade-off: Users MUST trust issuer completely
```

### 2.2 Transfer Choice

```haskell
-- Lines 69-87: Transfer implementation
nonconsuming choice Transfer : ContractId Token
  with
    recipient : Party
    qty       : Numeric 10
  controller owner  -- ← Owner initie le transfer
  do
    assertMsg "Positive quantity" (qty > 0.0)
    assertMsg "Sufficient balance" (qty <= amount)

    -- Create remainder token first (if partial transfer)
    when (qty < amount) $
      void $ create this with owner = owner, amount = amount - qty

    -- Create recipient's token
    recipientToken <- create this with 
      owner = recipient,    -- ← Nouveau owner
      amount = qty
      -- issuer reste le même! (clé du design)

    -- Archive source token LAST
    archive self

    return recipientToken
```

**Ordre des opérations critique**:

```
1. Create remainder (if qty < amount)
   └─ Évite de perdre les tokens restants

2. Create recipient token
   └─ Nouveau contract avec owner = recipient
   └─ issuer reste unchanged (important!)

3. Archive source LAST
   └─ Si on archive first, remainder create échoue (no source!)
```

### 2.3 TransferSplit Choice (Protocol Fees)

```haskell
-- Lines 89-115: TransferSplit for protocol fee extraction
nonconsuming choice TransferSplit : 
    (Optional (ContractId Token), ContractId Token)
  with
    recipient : Party
    qty : Numeric 10
  controller owner
  do
    assertMsg "Positive quantity" (qty > 0.0)
    assertMsg "Sufficient balance" (qty <= amount)

    -- Split in 3 parts:
    -- 1. Protocol fee (qty) → recipient
    -- 2. Remainder (amount - qty) → owner (for further use)
    
    let remainder = amount - qty
    
    if remainder > 0.0
    then do
      -- Create protocol fee token
      feeToken <- create this with owner = recipient, amount = qty
      
      -- Create remainder token
      remainderToken <- create this with owner = owner, amount = remainder
      
      -- Archive source
      archive self
      
      return (Some feeToken, remainderToken)
    else do
      -- Full transfer (rare for protocol fees)
      feeToken <- create this with owner = recipient, amount = qty
      archive self
      return (None, feeToken)
```

**Usage dans AtomicSwap**:

```haskell
-- Pool.AtomicSwap extrait protocol fee:
(maybeProtocolFeeCid, remainderCid) <- 
  exercise traderInputTokenCid TransferSplit with
    recipient = protocolFeeReceiver
    qty = protocolFeeAmount

-- Résultat:
-- maybeProtocolFeeCid: Some(0.075 USDC) → ClearportX
-- remainderCid: 99.925 USDC → Continue vers pool
```

### 2.4 Merge Choice (Consolidation)

```haskell
-- Lines 117-135: Merge tokens
nonconsuming choice Merge : ContractId Token
  with
    otherTokenCid : ContractId Token
  controller owner
  do
    other <- fetch otherTokenCid
    
    -- Validations
    assertMsg "Same owner" (other.owner == owner)
    assertMsg "Same issuer" (other.issuer == issuer)
    assertMsg "Same symbol" (other.symbol == symbol)
    
    -- Create merged token
    mergedToken <- create this with
      amount = amount + other.amount  -- ← Sum amounts
    
    -- Archive both source tokens
    archive self
    archive otherTokenCid
    
    return mergedToken
```

**Usage dans AddLiquidity**:

```haskell
-- Pool reçoit nouveaux tokens, doit les merge avec existants
finalTokenACid <- case this.tokenACid of
  None -> return newTokenAFromProvider  -- First liquidity
  Some existingCid -> 
    exercise existingCid Merge with otherTokenCid = newTokenAFromProvider
    -- Pool maintenant a 1 seul token A contract (consolidated)
```

---

## 3. LPToken.daml - Liquidity Provider Tokens

**Fichier source**: `clearportx/daml/LPToken/LPToken.daml`

### 3.1 Template LPToken

```haskell
template LPToken
  with
    issuer : Party    -- ← lpIssuer (usually poolParty)
    owner  : Party    -- ← Liquidity provider
    poolId : Text     -- ← "USDC-ETH" (which pool)
    amount : Numeric 10
  where
    signatory issuer
    observer owner
    
    ensure amount > 0.0
```

**Différence avec Token**:
- ✅ LP tokens sont **pool-specific** (`poolId` field)
- ✅ Représentent **share** du pool
- ✅ Valeur = (reserveA + reserveB) / totalLPSupply

### 3.2 Burn Choice

```haskell
nonconsuming choice Burn : ()
  with
    qty : Numeric 10
  controller owner
  do
    assertMsg "Positive quantity" (qty > 0.0)
    assertMsg "Sufficient balance" (qty <= amount)
    
    -- Create remainder if partial burn
    when (qty < amount) $
      void $ create this with amount = amount - qty
    
    -- Archive source (burn!)
    archive self
    
    return ()  -- ← Pas de return ContractId (tokens détruits)
```

**Usage dans RemoveLiquidity**:

```haskell
-- User veut retirer liquidité, doit burn LP tokens
_ <- exercise lpTokenCid LP.Burn with qty = lpTokenAmount

-- Après burn:
-- - LP tokens détruits
-- - Pool renvoie tokens proportionnels
```

### 3.3 Transfer Choice (même que Token)

```haskell
nonconsuming choice Transfer : ContractId LPToken
  with
    recipient : Party
    qty : Numeric 10
  controller owner
  do
    -- Même logique que Token.Transfer
    -- LP tokens sont transférables!
```

**Use case**: LP peut vendre/transférer sa position à quelqu'un d'autre

---

## 📝 Résumé du Module 02

Vous avez maintenant compris les **3 templates core** de ClearportX:

✅ **Pool.daml** (446 lignes) - Le cœur de l'AMM
- `AddLiquidity`: Déposer tokens, recevoir LP tokens (géométrique mean pour first, proportionnel ensuite)
- `RemoveLiquidity`: Burn LP tokens, retirer tokens proportionnels
- `AtomicSwap`: Swap en 1 étape avec protocol fee extraction (25/75 split)
- Archive-and-recreate pattern pour updates

✅ **Token.daml** - Tokens fungibles
- Issuer = sole signatory (trust model)
- `Transfer`: Move tokens entre parties
- `TransferSplit`: Extract protocol fees
- `Merge`: Consolidate multiple tokens

✅ **LPToken.daml** - LP tokens
- Représentent share du pool
- `Burn`: Détruire lors de RemoveLiquidity
- `Transfer`: LP positions transférables

**Formules clés**:
- AMM: `aout = (ain * feeMul * rout) / (rin + ain * feeMul)`
- LP tokens (first): `sqrt(amountA * amountB)`
- LP tokens (subsequent): `min(amountA * supply / reserveA, amountB * supply / reserveB)`
- Protocol fee: `totalFee * 0.25` → ClearportX, `totalFee * 0.75` → Pool

**Prochaine étape**: [Module 03 - DAML Swap System](./03_DAML_SWAP_SYSTEM.md) pour comprendre SwapRequest, Receipt, et PoolAnnouncement!

---

[← Module précédent: Architecture](./01_ARCHITECTURE_GLOBALE.md) | [Module suivant: DAML Swap System →](./03_DAML_SWAP_SYSTEM.md)
