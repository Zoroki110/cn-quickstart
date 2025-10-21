# 💰 Protocol Fees ClearportX - Guide Complet

## 📊 SYSTÈME DE FEES

### **Répartition Standard (comme Uniswap)**
```
Swap 100 CANTON:
├── Total Fee: 0.3% = 0.3 CANTON
│   ├── Protocol Fee (25%): 0.075 CANTON → ClearportX Treasury
│   └── LP Fee (75%): 0.225 CANTON → Reste dans le pool (LPs)
└── Output: ~4.985 USDC (selon AMM)
```

---

## 🔧 IMPLÉMENTATION SIMPLIFIÉE

### **Option 1: Protocol Fee Token Direct** (Recommandé)

Au lieu de collecter dans un template séparé, on crée directement un token pour ClearportX à chaque swap:

```daml
-- Dans ExecuteSwap (SwapRequest.daml ligne 137-143)
-- Calcul fees
let totalFee = inputAmount - ainFee  -- 0.3 CANTON
let protocolFee = totalFee * 0.25     -- 25% = 0.075 CANTON
let lpFee = totalFee * 0.75           -- 75% = 0.225 CANTON

-- Créer token protocol fee pour treasury
protocolFeeCid <- create T.Token with
  issuer = inputIssuer
  owner = protocolFeeReceiver  -- ClearportX treasury
  symbol = inputSymbol
  amount = protocolFee

-- LP fee reste dans le pool (ajusté dans les réserves)
let ainAfterProtocolFee = inputAmount - protocolFee
```

### **Avantages**:
✅ Simple - pas de template intermédiaire
✅ Immédiat - fees récupérables instantanément
✅ Transparent - chaque swap crée un token de fee visible
✅ Flexible - ClearportX peut transfer/vendre immédiatement

---

## 💡 OPTION 2: Accumulateur (Plus Complexe)

Si tu veux accumuler les fees avant de les retirer:

```daml
-- 1. Créer un collector au lancement du pool
collectorCid <- create ProtocolFeeCollector with
  treasury = clearportx
  poolId = "CANTON-USDC"
  tokenSymbol = "CANTON"
  tokenIssuer = cantonFoundation
  accumulatedFees = 0.0

-- 2. À chaque swap, update le collector
exercise collectorCid AddProtocolFee with
  feeAmount = protocolFee
  swapTime = now

-- 3. Retrait périodique (ex: chaque semaine)
(feesToken, newCollector) <- exercise collectorCid WithdrawProtocolFees with
  recipient = clearportx_treasury
```

### **Avantages**:
✅ Fees groupées - moins de tokens individuels
✅ Gas efficient - un seul token par période
✅ Comptabilité - historique des fees

### **Inconvénients**:
❌ Plus complexe - 2 transactions (collect + withdraw)
❌ Retard - fees pas disponibles immédiatement

---

## 🚀 IMPLÉMENTATION RECOMMANDÉE

### **Modification du SwapRequest.daml**

```daml
-- Ligne 137-143 (calcul fees)
let feeMul = (10000.0 - intToDecimal feeBps) / 10000.0
let totalFeeAmount = inputAmount * (1.0 - feeMul)  -- 0.3%

-- NOUVEAU: Split fee entre protocol et LP
let protocolFeeAmount = totalFeeAmount * 0.25  -- 25% pour ClearportX
let lpFeeAmount = totalFeeAmount * 0.75        -- 75% pour LPs

-- NOUVEAU: Créer token protocol fee
protocolFeeCid <- create T.Token with
  issuer = (if inputSymbol == symbolA then pool.issuerA else pool.issuerB)
  owner = pool.protocolFeeReceiver
  symbol = inputSymbol
  amount = protocolFeeAmount

-- Ajuster le calcul AMM pour ne garder que LP fee dans le pool
let ainAfterAllFees = inputAmount - protocolFeeAmount  -- Protocol fee sortie
let ainForAMM = ainAfterAllFees * feeMul / (1.0 - feeMul * 0.75)  -- LP fee reste

let denom = rin + ainForAMM
let aout = (ainForAMM * rout) / denom
```

### **Modification du Pool.daml**

```daml
-- Ajouter protocolFeeReceiver au template (DÉJÀ FAIT ✅)
template Pool
  with
    ...
    protocolFeeReceiver : Party  -- ClearportX treasury
```

---

## 📊 EXEMPLE CONCRET

### **Swap: 100 CANTON → USDC**

**Pool initial**:
- 1,000,000 CANTON
- 50,000 USDC
- Prix: 1 CANTON = 0.05 USDC

**Calcul fees**:
```
Total fee 0.3% de 100 = 0.3 CANTON
├── Protocol (25%): 0.075 CANTON → Token créé pour ClearportX
└── LP (75%): 0.225 CANTON → Reste dans pool

AMM input (sans protocol fee):
- Input effectif: 100 - 0.075 = 99.925 CANTON
- Input après LP fee: 99.925 * 0.997 = 99.625 CANTON

Output calculé:
- aout = (99.625 * 50000) / (1000000 + 99.625)
- aout ≈ 4.98 USDC
```

**Résultat**:
- Trader reçoit: ~4.98 USDC
- ClearportX reçoit: Token 0.075 CANTON
- Pool garde: 0.225 CANTON (augmente reserveA)

**Pool final**:
- 1,000,099.925 CANTON (input - protocol fee)
- 49,995.02 USDC (output sorti)
- LP tokens inchangés mais valent plus (0.225 CANTON de fees inclus)

---

## 💼 GESTION DES PROTOCOL FEES

### **Retrait des Fees** (ClearportX Treasury)

```daml
-- Les fees sont déjà des tokens T.Token
-- Tu peux directement:

1. Les garder: Balance s'accumule naturellement
2. Les vendre: Swap protocol fees contre stablecoin
3. Les distribuer: Transfer aux stakeholders
4. Les burn: Si tokenomics spécifique
```

### **Visualisation des Fees**

```daml
-- Script pour voir protocol fees accumulés
viewProtocolFees = script do
  treasury <- allocateParty "ClearportX_Treasury"

  -- Query tous les tokens détenus par treasury
  feeTokens <- query @T.Token treasury

  -- Grouper par symbol
  let cantonFees = sum [t.amount | (_, t) <- feeTokens, t.symbol == "CANTON"]
  let usdcFees = sum [t.amount | (_, t) <- feeTokens, t.symbol == "USDC"]

  debug $ "Protocol Fees Collected:"
  debug $ "  CANTON: " <> show cantonFees
  debug $ "  USDC: " <> show usdcFees
```

---

## 🎯 CONFIGURATION DU PROTOCOL FEE RECEIVER

### **Au lancement du pool**

```daml
-- Script d'initialisation lundi
initProduction = script do
  clearportx <- allocateParty "ClearportX_Treasury"
  poolOperator <- allocateParty "PoolOperator"

  -- Créer pool avec protocol fee receiver
  poolCid <- submit poolOperator $ createCmd Pool with
    poolOperator = poolOperator
    poolParty = poolOperator
    symbolA = "CANTON"
    symbolB = "USDC"
    protocolFeeReceiver = clearportx  -- ← ICI
    feeBps = 30  -- 0.3% total
    ...
```

---

## 📈 MÉTRIQUES & ANALYTICS

### **APY Protocol Fees**

```typescript
// Frontend dashboard
const calculateProtocolFeeAPY = (pool: Pool, timeframe: number) => {
  const dailyVolume = getTotalVolume(pool.poolId, 'daily')
  const protocolFeesDaily = dailyVolume * 0.003 * 0.25  // 0.3% * 25%
  const protocolFeesYearly = protocolFeesDaily * 365

  return {
    daily: protocolFeesDaily,
    yearly: protocolFeesYearly,
    apy: (protocolFeesYearly / pool.tvl) * 100
  }
}
```

### **Exemple Calcul**

```
Pool CANTON/USDC:
- TVL: $1M
- Volume journalier: $100K
- Protocol fees journaliers: $100K * 0.3% * 25% = $75
- Protocol fees annuels: $75 * 365 = $27,375
- Protocol fee APY: $27,375 / $1M = 2.74%

LPs APY (75% des fees):
- LP fees journaliers: $100K * 0.3% * 75% = $225
- LP fees annuels: $225 * 365 = $82,125
- LP APY: $82,125 / $1M = 8.21%
```

---

## ✅ CHECKLIST IMPLÉMENTATION

### **Backend (DAML)**
- [x] Ajouter `protocolFeeReceiver` au Pool template
- [ ] Modifier `ExecuteSwap` pour split fees (25/75)
- [ ] Créer token protocol fee à chaque swap
- [ ] Tester avec script de swap

### **Tests**
- [ ] Test: Protocol fee = 25% des fees totaux
- [ ] Test: LP fee = 75% reste dans pool
- [ ] Test: ClearportX reçoit bien le token
- [ ] Test: Calcul AMM correct avec nouveau fee split

### **Monitoring**
- [ ] Dashboard: Protocol fees par pool
- [ ] Dashboard: Protocol fees totaux
- [ ] Analytics: APY protocol vs LP

---

## 🚀 PROCHAINES ÉTAPES

1. **Modifier SwapRequest.daml** pour split fees
2. **Tester** avec script de swap
3. **Valider** montants protocol fees
4. **Déployer** lundi avec nouveau système

**Temps estimé**: 2-3 heures de dev + tests

---

**Questions**:
1. Tu préfères **Option 1** (token direct) ou **Option 2** (accumulateur) ?
2. Le `protocolFeeReceiver` sera quelle party ? (ClearportX_Treasury ?)
3. On implémente maintenant ou tu veux voir le code final d'abord ?
