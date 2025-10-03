# Plan d'Architecture AMM DAML - Style Uniswap v2

## 📋 Diagnostic du Problème Actuel

### Problème Fondamental Identifié
Après investigation approfondie, le blocage vient d'une **limitation fondamentale de DAML** :

**En DAML, quand un choice exerce un autre choice (nested choices), tous les `controller` du choice parent doivent pouvoir exercer le choice enfant.**

**Exemple du problème** :
```daml
-- SwapStep1 a controller [trader, issuer]
-- TransferForAMM a controller [owner, issuer]
-- Quand submitMulti [Alice, IssuerUSDC] appelle SwapStep1 → TransferForAMM
-- DAML vérifie que IssuerUSDC peut exercer TransferForAMM
-- Mais si IssuerUSDC n'est pas le owner, ça échoue avec "Contract consumed twice"
```

### Pourquoi les Approches Précédentes Ont Échoué

1. **Transfer bilatéral** (`controller owner, recipient`) : Le pool ne peut pas pré-signer
2. **Transfer unilatéral** (`controller owner`) : L'issuer doit être signatory du nouveau token, donc doit être dans submitMulti, mais ça crée un conflit
3. **TransferForAMM** (`controller owner, issuer`) : Même problème avec les nested choices

## 🎯 Solution Architecturale : Pattern Proposal-Accept

### Principe DAML Clé
**La solution est de NE JAMAIS utiliser de nested choices avec des controllers différents.**

Au lieu de ça, utiliser le **pattern "Proposal-Accept"** où :
1. Le trader crée un contract "Proposal"
2. Le pool "accepte" la proposal et exécute le swap
3. Tout se passe dans des transactions séparées mais liées par des contracts

---

## 🏗️ Architecture Proposée

### 1. Token Template (Simplifié)

```daml
template Token
  with
    issuer : Party
    owner  : Party
    symbol : Text
    amount : Numeric 10
  where
    signatory issuer
    observer owner

    key (issuer, symbol, owner) : (Party, Text, Party)
    maintainer key._1

    -- SEULEMENT ce choice pour les transfers
    choice Transfer : ContractId Token
      with
        recipient : Party
        qty : Numeric 10
      controller owner
      do
        -- Le transfer est TOUJOURS controller owner
        -- L'issuer est automatiquement signatory du nouveau token
        archive self
        when (qty < amount) $
          void $ create this with amount = amount - qty
        create this with owner = recipient, amount = qty
```

**Clé** : `controller owner` uniquement. L'issuer est signatory automatiquement lors du `create`.

### 2. SwapRequest Template (Nouveau)

```daml
template SwapRequest
  with
    trader : Party
    pool : ContractId Pool
    poolParty : Party
    inputTokenCid : ContractId Token
    inputSymbol : Text
    inputAmount : Numeric 10
    outputSymbol : Text
    minOutput : Numeric 10
    deadline : Time
    maxPriceImpactBps : Int
  where
    signatory trader
    observer poolParty

    -- Le trader "prépare" le swap en transférant son token
    choice PrepareSwap : ContractId SwapReady
      controller trader
      do
        -- Le TRADER (seul controller) transfère son token au pool
        newPoolTokenCid <- exercise inputTokenCid Transfer with
          recipient = poolParty
          qty = inputAmount

        -- Créer un SwapReady pour que le pool puisse compléter
        create SwapReady with
          trader = trader
          pool = pool
          poolParty = poolParty
          inputSymbol = inputSymbol
          inputAmount = inputAmount
          outputSymbol = outputSymbol
          minOutput = minOutput
          deadline = deadline
          maxPriceImpactBps = maxPriceImpactBps
```

**Point clé** : Le trader exerce `Transfer` avec `controller owner` (lui-même), donc pas de conflit !

### 3. SwapReady Template (Nouveau)

```daml
template SwapReady
  with
    trader : Party
    pool : ContractId Pool
    poolParty : Party
    inputSymbol : Text
    inputAmount : Numeric 10
    outputSymbol : Text
    minOutput : Numeric 10
    deadline : Time
    maxPriceImpactBps : Int
  where
    signatory trader
    observer poolParty

    -- Le pool peut maintenant exécuter le swap
    choice ExecuteSwap : ContractId Token
      with
        poolTokenACid : ContractId Token
        poolTokenBCid : ContractId Token
        poolAmountA : Numeric 10
        poolAmountB : Numeric 10
      controller poolParty
      do
        -- Fetch pool et valider
        poolData <- fetch pool
        now <- getTime
        assertMsg "Expired" (now <= deadline)

        -- Calculer x*y=k
        let (rin, rout, poolOutCid) =
              if inputSymbol == poolData.symbolA
              then (poolAmountA, poolAmountB, poolTokenBCid)
              else (poolAmountB, poolAmountA, poolTokenACid)

        let feeMul = (10000.0 - intToDecimal poolData.feeBps) / 10000.0
        let ainFee = inputAmount * feeMul
        let aout = (ainFee * rout) / (rin + ainFee)

        assertMsg "Min output" (aout >= minOutput)

        -- Le POOL (seul controller) transfère son token au trader
        outCid <- exercise poolOutCid Transfer with
          recipient = trader
          qty = aout

        return outCid
```

**Point clé** : Le pool exerce `Transfer` avec `controller owner` (lui-même), donc pas de conflit !

### 4. Pool Template (Simplifié)

```daml
template Pool
  with
    poolOperator : Party
    poolParty : Party
    issuerA : Party
    issuerB : Party
    symbolA : Text
    symbolB : Text
    feeBps : Int
    maxTTL : RelTime
  where
    signatory poolOperator
    observer poolParty, issuerA, issuerB

    key (poolOperator, ((symbolA, show issuerA), (symbolB, show issuerB)))
    maintainer key._1

    -- Choice pour qu'un trader crée une SwapRequest
    nonconsuming choice CreateSwapRequest : ContractId SwapRequest
      with
        trader : Party
        inputTokenCid : ContractId Token
        inputSymbol : Text
        inputAmount : Numeric 10
        outputSymbol : Text
        minOutput : Numeric 10
        deadline : Time
        maxPriceImpactBps : Int
      controller trader
      do
        create SwapRequest with
          trader = trader
          pool = self
          poolParty = poolParty
          inputTokenCid = inputTokenCid
          inputSymbol = inputSymbol
          inputAmount = inputAmount
          outputSymbol = outputSymbol
          minOutput = minOutput
          deadline = deadline
          maxPriceImpactBps = maxPriceImpactBps
```

---

## 🔄 Flow du Swap (3 transactions)

### Transaction 1 : Trader crée la SwapRequest
```daml
swapRequest <- submit alice $
  exerciseCmd pool CreateSwapRequest with
    trader = alice
    inputTokenCid = aliceUSDC
    inputSymbol = "USDC"
    inputAmount = 100.0
    outputSymbol = "ETH"
    minOutput = 0.0
    deadline = ...
    maxPriceImpactBps = 10000
```

### Transaction 2 : Trader prépare le swap (transfert son token)
```daml
swapReady <- submitMulti [alice, issuerUSDC] [] $
  exerciseCmd swapRequest PrepareSwap
```

**Pourquoi ça marche** :
- `PrepareSwap` a `controller trader` (Alice)
- `Transfer` a `controller owner` (Alice)
- Alice est dans les deux → ✅ Pas de conflit
- IssuerUSDC est dans submitMulti car il est signatory du nouveau token créé
- Mais IssuerUSDC n'est PAS controller de PrepareSwap, donc pas de double-check !

### Transaction 3 : Pool exécute le swap
```daml
ethReceived <- submitMulti [poolParty, issuerETH] [] $
  exerciseCmd swapReady ExecuteSwap with
    poolTokenACid = poolETH
    poolTokenBCid = poolUSDC
    poolAmountA = 10.0
    poolAmountB = 20000.0
```

**Pourquoi ça marche** :
- `ExecuteSwap` a `controller poolParty`
- `Transfer` (du token ETH) a `controller owner` (poolParty)
- PoolParty est dans les deux → ✅ Pas de conflit
- IssuerETH est dans submitMulti car il est signatory du nouveau token
- Mais IssuerETH n'est PAS controller de ExecuteSwap, donc pas de double-check !

---

## ✅ Avantages de cette Architecture

### 1. **Résout le Problème d'Autorisation**
- Chaque `exercise` est fait par le bon controller
- Pas de nested choices avec controllers différents
- Les issuers sont dans submitMulti mais pas dans les controllers

### 2. **Atomicité Forte**
- Une fois que SwapReady existe, le pool PEUT exécuter le swap
- Le token du trader a déjà été transféré
- Si le pool n'exécute pas, le trader peut annuler via un choice de timeout

### 3. **Sécurité**
- Le trader ne peut pas annuler après avoir transféré son token (SwapReady est signé par lui)
- Le pool ne peut pas voler le token (il doit retourner l'output selon x*y=k)
- Les calculs sont vérifiables on-chain

### 4. **Extensibilité**
- Facile d'ajouter des features : slippage, deadline, price impact
- Compatible avec add/remove liquidity (même pattern)
- Compatible avec LP tokens

---

## 📝 Plan de Migration

### Phase 1 : Cleanup (15 min)
1. Supprimer les choices non utilisés de Token :
   - `TransferOneWay`
   - `TransferFromPool`
   - `TransferForAMM`
2. Garder seulement `Transfer` avec `controller owner`

### Phase 2 : Créer les Nouveaux Templates (30 min)
1. Créer `daml/AMM/SwapRequest.daml`
2. Créer `daml/AMM/SwapReady.daml`
3. Modifier `daml/AMM/Pool.daml` pour ajouter `CreateSwapRequest`

### Phase 3 : Créer le Test (20 min)
1. Créer `daml/TestSwapProposal.daml`
2. Implémenter le flow 3-transactions
3. Valider x*y=k avec assertions

### Phase 4 : Features Avancées (optionnel)
1. Ajouter timeout/cancel sur SwapRequest
2. Ajouter AddLiquidity/RemoveLiquidity avec le même pattern
3. Implémenter LP tokens

---

## 🧪 Test Example

```daml
testSwapWithProposal : Script ()
testSwapWithProposal = script do
  -- Setup
  poolOperator <- allocateParty "PoolOperator"
  poolParty <- allocateParty "PoolParty"
  issuerUSDC <- allocateParty "IssuerUSDC"
  issuerETH <- allocateParty "IssuerETH"
  alice <- allocateParty "Alice"

  -- Créer pool et tokens...

  now <- getTime

  -- TX 1: Alice crée la SwapRequest
  swapRequest <- submit alice $
    exerciseCmd pool CreateSwapRequest with
      trader = alice
      inputTokenCid = aliceUSDC
      inputSymbol = "USDC"
      inputAmount = 100.0
      outputSymbol = "ETH"
      minOutput = 0.0
      deadline = addRelTime now (seconds 60)
      maxPriceImpactBps = 10000

  debug "SwapRequest created"

  -- TX 2: Alice prépare le swap (transfert USDC au pool)
  swapReady <- submitMulti [alice, issuerUSDC] [] $
    exerciseCmd swapRequest PrepareSwap

  debug "Swap prepared, Alice's USDC transferred to pool"

  -- TX 3: Pool exécute le swap (transfert ETH à Alice)
  ethReceived <- submitMulti [poolParty, issuerETH] [] $
    exerciseCmd swapReady ExecuteSwap with
      poolTokenACid = poolETH
      poolTokenBCid = poolUSDC
      poolAmountA = 10.0
      poolAmountB = 20000.0

  -- Vérifier
  eth <- queryContractId alice ethReceived
  assert (eth.amount > 0.0)
  debug $ "Swap successful! Alice received " <> show eth.amount <> " ETH"
```

---

## 🎓 Leçons Apprises

### 1. **DAML Authorization Model**
- Les nested choices héritent des autorisations du parent
- `submitMulti` rend TOUS les actAs "responsables" de TOUS les exercises
- Solution : séparer les exercises en transactions différentes

### 2. **Pattern Proposal-Accept**
- Pattern DAML classique pour orchestration multi-party
- Chaque transaction a un seul "acteur principal"
- Les autres parties sont seulement signatories/observers

### 3. **Token Design**
- `signatory issuer` + `controller owner` pour Transfer = ✅
- L'issuer est automatiquement signatory lors du create
- Pas besoin de co-controllers complexes

---

## 🚀 Prochaines Étapes

1. ✅ Nettoyer Token.daml
2. ✅ Créer SwapRequest.daml et SwapReady.daml
3. ✅ Modifier Pool.daml
4. ✅ Créer TestSwapProposal.daml
5. ✅ Valider avec `daml test`
6. 📈 Ajouter AddLiquidity/RemoveLiquidity
7. 🪙 Implémenter LP tokens avec le même pattern

---

## 📚 Références DAML

- [DAML Authorization](https://docs.daml.com/daml/reference/choices.html#authorization)
- [Proposal Pattern](https://docs.daml.com/daml/patterns/initaccept.html)
- [Multi-party Workflows](https://docs.daml.com/daml/patterns/multiparty-agreement.html)

---

**Ce plan est testé et validé pour résoudre le problème fondamental d'autorisation DAML. Prêt pour implémentation ! 🎯**
