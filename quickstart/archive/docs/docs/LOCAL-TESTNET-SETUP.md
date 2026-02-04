# Setup Canton Local pour Tester le DEX

## 🚀 Quick Start (5 minutes)

### Étape 1: Vérifier que Canton tourne

Tu es déjà dans `cn-quickstart`, donc Canton devrait être disponible:

```bash
# Check si Canton est installé
ls -la ../../canton/

# Si oui, démarrer Canton
cd ../../
make canton-start
# OU
cd canton
./bin/canton daemon -c examples/01-simple-topology/simple-topology.conf
```

### Étape 2: Upload ton DAR

Dans un **nouveau terminal**:

```bash
cd /root/cn-quickstart/quickstart/clearportx

# Upload le DAR sur Canton local
daml ledger upload-dar \
  --host localhost \
  --port 5011 \
  .daml/dist/clearportx-1.0.0.dar
```

**Résultat attendu:**
```
Uploading .daml/dist/clearportx-1.0.0.dar to localhost:5011
DAR upload succeeded.
```

---

## 🎯 Test Complet du DEX

### Script 1: Initialisation

Crée `daml/LocalInit.daml`:

```daml
module LocalInit where

import Daml.Script
import DA.Time

import qualified Token.Token as T
import qualified AMM.Pool as P

-- Initialise le DEX en local
initLocal : Script ()
initLocal = script do
  -- Créer les parties
  poolOperator <- allocateParty "PoolOperator"
  poolParty <- allocateParty "PoolParty"
  lpIssuer <- allocateParty "LPIssuer"
  issuerUSDC <- allocateParty "IssuerUSDC"
  issuerETH <- allocateParty "IssuerETH"
  alice <- allocateParty "Alice"
  bob <- allocateParty "Bob"

  now <- getTime

  debug "=== Minting initial tokens ==="

  -- Tokens pour Alice (test trader)
  aliceUSDC <- submit issuerUSDC $ createCmd T.Token with
    issuer = issuerUSDC
    owner = alice
    symbol = "USDC"
    amount = 50000.0

  aliceETH <- submit issuerETH $ createCmd T.Token with
    issuer = issuerETH
    owner = alice
    symbol = "ETH"
    amount = 10.0

  -- Tokens pour Bob (test trader)
  bobUSDC <- submit issuerUSDC $ createCmd T.Token with
    issuer = issuerUSDC
    owner = bob
    symbol = "USDC"
    amount = 30000.0

  debug "✅ Test users have tokens"

  -- Créer le pool
  pool <- submit poolOperator $ createCmd P.Pool with
    poolOperator
    poolParty
    lpIssuer
    issuerA = issuerETH
    issuerB = issuerUSDC
    symbolA = "ETH"
    symbolB = "USDC"
    feeBps = 30
    poolId = "ETH-USDC-LOCAL"
    maxTTL = hours 2
    totalLPSupply = 0.0
    reserveA = 0.0
    reserveB = 0.0

  debug "✅ Pool created"

  -- Ajouter liquidité initiale
  lpProviderETH <- submit issuerETH $ createCmd T.Token with
    issuer = issuerETH
    owner = poolOperator
    symbol = "ETH"
    amount = 100.0

  lpProviderUSDC <- submit issuerUSDC $ createCmd T.Token with
    issuer = issuerUSDC
    owner = poolOperator
    symbol = "USDC"
    amount = 200000.0

  (lpToken, poolWithLiquidity) <- submitMulti [poolOperator, poolParty, lpIssuer] [] $
    exerciseCmd pool P.AddLiquidity with
      provider = poolOperator
      tokenACid = lpProviderETH
      tokenBCid = lpProviderUSDC
      amountA = 100.0
      amountB = 200000.0
      minLPTokens = 0.0
      deadline = addRelTime now (hours 1)

  debug "✅ Pool has liquidity: 100 ETH + 200,000 USDC"
  debug $ "   Price: 1 ETH = 2,000 USDC"
  debug ""
  debug "🎉 LOCAL DEX READY!"
  debug "   - Alice: 50,000 USDC + 10 ETH"
  debug "   - Bob: 30,000 USDC"
  debug "   - Pool: 100 ETH + 200,000 USDC"

  return ()
```

**Exécuter:**
```bash
# Rebuild avec le nouveau script
make build

# Run l'init
daml script \
  --dar .daml/dist/clearportx-1.0.0.dar \
  --script-name LocalInit:initLocal \
  --ledger-host localhost \
  --ledger-port 5011
```

---

### Script 2: Test Swap

Crée `daml/LocalTestSwap.daml`:

```daml
module LocalTestSwap where

import Daml.Script
import DA.Time

import qualified Token.Token as T
import qualified AMM.SwapRequest as SR
import qualified AMM.Pool as P

-- Test un swap: Alice swap 5000 USDC → ETH
testLocalSwap : Script ()
testLocalSwap = script do
  -- Récupérer les parties existantes
  alice <- allocateParty "Alice"
  poolParty <- allocateParty "PoolParty"
  poolOperator <- allocateParty "PoolOperator"
  issuerETH <- allocateParty "IssuerETH"
  issuerUSDC <- allocateParty "IssuerUSDC"

  now <- getTime

  -- Trouver le pool
  pools <- query @P.Pool poolOperator
  let (poolCid, pool) = head pools

  debug $ "Found pool: " <> pool.poolId
  debug $ "Reserves: " <> show pool.reserveA <> " ETH, " <> show pool.reserveB <> " USDC"

  -- Trouver les tokens d'Alice
  aliceTokens <- query @T.Token alice
  let aliceUSDCs = filter (\(_, t) -> t.symbol == "USDC" && t.owner == alice) aliceTokens
  let (aliceUSDCCid, aliceUSDC) = head aliceUSDCs

  debug $ "Alice has: " <> show aliceUSDC.amount <> " USDC"

  -- Créer swap request: 5000 USDC → ETH
  swapReq <- submit alice $ createCmd SR.SwapRequest with
    trader = alice
    poolCid
    poolParty
    poolOperator
    issuerA = issuerETH
    issuerB = issuerUSDC
    symbolA = "ETH"
    symbolB = "USDC"
    feeBps = 30
    maxTTL = hours 2
    inputTokenCid = aliceUSDCCid
    inputSymbol = "USDC"
    inputAmount = 5000.0
    outputSymbol = "ETH"
    minOutput = 2.0  -- Minimum 2 ETH attendu
    deadline = addRelTime now (hours 1)
    maxPriceImpactBps = 500  -- Max 5% impact

  debug "📝 Swap request created"

  -- Prepare swap (transfer USDC to pool)
  swapReady <- submit alice $ exerciseCmd swapReq SR.PrepareSwap

  debug "✅ Swap prepared (USDC transferred to pool)"

  -- Trouver les pool tokens
  poolTokens <- query @T.Token poolParty
  let poolETHs = filter (\(_, t) -> t.symbol == "ETH" && t.owner == poolParty) poolTokens
  let poolUSDCs = filter (\(_, t) -> t.symbol == "USDC" && t.owner == poolParty) poolTokens
  let (poolETHCid, _) = head poolETHs
  let (poolUSDCCid, _) = head poolUSDCs

  -- Execute swap
  (aliceNewETH, newPool) <- submit poolParty $ exerciseCmd swapReady SR.ExecuteSwap with
    poolTokenACid = poolETHCid
    poolTokenBCid = poolUSDCCid

  -- Vérifier résultat
  newETHToken <- queryContractId alice aliceNewETH
  let Some ethReceived = newETHToken

  newPoolData <- queryContractId poolOperator newPool
  let Some newPoolState = newPoolData

  debug ""
  debug "🎉 SWAP SUCCESSFUL!"
  debug $ "   Alice swapped: 5,000 USDC"
  debug $ "   Alice received: " <> show ethReceived.amount <> " ETH"
  debug $ "   New pool reserves: " <> show newPoolState.reserveA <> " ETH, " <> show newPoolState.reserveB <> " USDC"
  debug $ "   Effective price: " <> show (5000.0 / ethReceived.amount) <> " USDC/ETH"

  return ()
```

**Exécuter:**
```bash
# Rebuild
make build

# Run le test swap
daml script \
  --dar .daml/dist/clearportx-1.0.0.dar \
  --script-name LocalTestSwap:testLocalSwap \
  --ledger-host localhost \
  --ledger-port 5011
```

---

## 🎯 Tests Complets

### Test Multiple Swaps

```bash
# Test 1: Alice swap USDC → ETH
daml script --dar .daml/dist/clearportx-1.0.0.dar \
  --script-name LocalTestSwap:testLocalSwap \
  --ledger-host localhost --ledger-port 5011

# Test 2: Query pool state
daml ledger query \
  --host localhost --port 5011 \
  --template "AMM.Pool:Pool"

# Test 3: Query all tokens
daml ledger query \
  --host localhost --port 5011 \
  --template "Token.Token:Token"
```

---

## 📊 Monitoring

### Vérifier l'état du système

```bash
# 1. Combien de pools existent?
daml ledger query --host localhost --port 5011 \
  --template "AMM.Pool:Pool" | grep -c "contractId"

# 2. Combien de tokens existent?
daml ledger query --host localhost --port 5011 \
  --template "Token.Token:Token" | grep -c "contractId"

# 3. État d'un pool spécifique
daml ledger query --host localhost --port 5011 \
  --template "AMM.Pool:Pool" | jq '.[] | {poolId, reserveA, reserveB}'
```

---

## 🔧 Troubleshooting

### Canton ne démarre pas
```bash
# Vérifier les logs
tail -f ../../canton/log/canton.log

# Tuer les processus existants
pkill -f canton

# Redémarrer
cd ../../canton
./bin/canton daemon -c examples/01-simple-topology/simple-topology.conf
```

### DAR upload fail
```bash
# Vérifier que Canton écoute sur 5011
netstat -tuln | grep 5011

# Vérifier le ledger ID
daml ledger info --host localhost --port 5011
```

### Script fail
```bash
# Run avec --debug pour plus d'infos
daml script \
  --dar .daml/dist/clearportx-1.0.0.dar \
  --script-name LocalInit:initLocal \
  --ledger-host localhost \
  --ledger-port 5011 \
  --debug
```

---

## ✅ Checklist Avant Lundi

- [ ] Canton local qui tourne
- [ ] DAR uploaded
- [ ] Script d'init executé avec succès
- [ ] Test swap réussi
- [ ] Pool reserves mis à jour correctement
- [ ] Tokens fragmentés gérés
- [ ] Documentation des problèmes rencontrés

---

## 🎯 Ce Qui Change pour le Vrai Testnet (Lundi)

```bash
# LOCAL (maintenant)
--host localhost
--port 5011

# TESTNET (lundi)
--host IP_DU_SERVEUR
--port 5011
--access-token-file token.txt  # Le token qu'ils vont recevoir
```

C'est **exactement le même code**, juste le host qui change! 🚀
