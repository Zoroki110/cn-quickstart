# AMM Uniswap v2 DAML/Canton - Plan de Développement Production

## Résumé Exécutif

Développement d'un Automated Market Maker (AMM) style Uniswap v2 sur DAML/Canton avec architecture de garde stricte, tokens physiques on-ledger, et sécurité production-grade.

**Durée estimée** : 30 jours-personne (incluant marge de sécurité)  
**Architecture** : Pool sans état + tokens en garde par poolParty + LP tokens séparés  
**Invariant principal** : Conservation stricte - sum(tokens_on_ledger) == reserves_calculées

---

## Architecture Fondamentale

### Principes Critiques

1. **Modèle de garde strict**
   - Tokens physiques : Contrats Token avec `owner = poolParty`
   - Pool coordinateur : Orchestre transferts sans détenir de valeurs
   - LP tokens : Contrats séparés avec mint/burn atomiques
   - Aucune "réserve" stockée - interrogation des tokens détenus

2. **Règles DAML strictes**
   - `archive self` avant tout `create` (éviter duplication)
   - `Numeric 10` partout (pas de Decimal)
   - Frais en `Int` bps (30 = 0.3%)
   - Clés canoniques pour unicité

3. **Parties et rôles**
```
issuerParty     : Émetteur tokens (USDC, ETH wrappers)
poolOperator    : Déploie pools, gère poolParty
poolParty       : Détient tokens déposés (custody)
lpTokenIssuer   : Émet/brûle LP tokens
traders/LPs     : Utilisateurs finaux
```

---

## Phase 0 : Infrastructure de Base (3 jours)

### Structure Projet

```
amm-canton/
├── daml.yaml
├── canton/
│   ├── topology.conf       # Configuration participants
│   ├── bootstrap.canton    # Script init parties
│   └── monitoring.conf     # Métriques/alertes
├── daml/
│   ├── Token/
│   │   ├── Fungible.daml   # Interface standard
│   │   ├── Token.daml      # Implémentation avec clés
│   │   └── Utils.daml      # Merge/Split helpers
│   ├── AMM/
│   │   ├── Types.daml      # Numeric 10, feeBps : Int
│   │   ├── Math.daml       # Calculs déterministes
│   │   ├── LPToken.daml    # Mint/Burn atomiques
│   │   ├── Pool.daml       # Coordinateur sans état
│   │   └── Router.daml     # Orchestration atomique
│   └── Governance/
│       └── Pausable.daml   # Circuit breaker global
├── test/
│   ├── Conservation.daml   # Tests invariants
│   ├── Atomicity.daml      # Tests transactions
│   └── Attack.daml         # Tests sécurité
└── scripts/
    └── Deploy.daml          # Bootstrap production
```

### Token avec Garde Correcte

```daml
module Token.Token where

template Token
  with
    issuer : Party
    owner : Party
    symbol : Text
    amount : Numeric 10
  where
    signatory issuer
    observer owner
    
    key (issuer, symbol, owner) : (Party, Text, Party)
    maintainer key._1
    
    ensure amount > 0.0
    
    choice Transfer : (ContractId Token, Optional (ContractId Token))
      with
        recipient : Party
        qty : Numeric 10
      controller owner
      do
        assertMsg "Positive quantity" (qty > 0.0)
        assertMsg "Sufficient balance" (qty <= amount)
        
        -- CRITIQUE: Archive avant création
        archive self
        
        newToken <- create this with 
          owner = recipient
          amount = qty
          
        remainder <- if qty < amount
          then Some <$> create this with 
                 owner = owner
                 amount = amount - qty
          else return None
          
        return (newToken, remainder)
    
    choice Merge : ContractId Token
      with
        otherCid : ContractId Token
      controller owner
      do
        other <- fetch otherCid
        assertMsg "Same denomination" 
          (issuer == other.issuer && 
           symbol == other.symbol && 
           owner == other.owner)
        
        -- Archive les deux avant création
        archive self
        archive otherCid
        
        create this with amount = amount + other.amount
    
    choice Split : (ContractId Token, ContractId Token)
      with
        amount1 : Numeric 10
      controller owner
      do
        assertMsg "Valid split" (amount1 > 0.0 && amount1 < amount)
        
        archive self
        
        tok1 <- create this with amount = amount1
        tok2 <- create this with amount = amount - amount1
        
        return (tok1, tok2)
```

---

## Phase 1 : Pool Sans État avec Garde (7 jours)

### LP Token Séparé

```daml
module AMM.LPToken where

template LPToken
  with
    lpTokenIssuer : Party
    poolId : Text
    owner : Party
    shares : Numeric 10
  where
    signatory lpTokenIssuer
    observer owner
    
    key (lpTokenIssuer, poolId, owner) : (Party, Text, Party)
    maintainer key._1
    
    ensure shares > 0.0
    
    choice TransferLP : (ContractId LPToken, Optional (ContractId LPToken))
      with
        to : Party
        qty : Numeric 10
      controller owner
      do
        archive self
        newLP <- create this with owner = to, shares = qty
        remainder <- if qty < shares
          then Some <$> create this with shares = shares - qty
          else return None
        return (newLP, remainder)
    
    choice BurnLP : Optional (ContractId LPToken)
      with
        burnAmount : Numeric 10
      controller lpTokenIssuer
      do
        assertMsg "Valid burn" (burnAmount > 0.0 && burnAmount <= shares)
        archive self
        if burnAmount < shares
          then Some <$> create this with shares = shares - burnAmount
          else return None
```

### Pool Coordinateur

```daml
module AMM.Pool where
import qualified Token.Token as T
import qualified AMM.LPToken as LP
import AMM.Math

template Pool
  with
    poolOperator : Party
    poolParty : Party        -- Custody account
    lpTokenIssuer : Party
    tokenA : Party           -- Issuer A
    tokenB : Party           -- Issuer B
    symbolA : Text
    symbolB : Text
    feeBps : Int            -- 30 = 0.3%
    poolId : Text
  where
    signatory poolOperator, lpTokenIssuer
    observer poolParty, tokenA, tokenB
    
    key (symbolA, symbolB) : (Text, Text)
    maintainer key._1
    
    ensure symbolA < symbolB  -- Ordre canonique forcé
    
    -- Vue non-consommante des réserves
    nonconsuming choice GetReserves : (Numeric 10, Numeric 10, Numeric 10)
      with viewer : Party
      controller viewer
      do
        -- Interroger tokens détenus par poolParty
        tokensA <- queryFilter @T.Token 
          (\t -> t.symbol == symbolA && t.owner == poolParty)
        tokensB <- queryFilter @T.Token
          (\t -> t.symbol == symbolB && t.owner == poolParty)
        lpSupply <- queryFilter @LP.LPToken
          (\lp -> lp.poolId == poolId)
          
        let reserveA = sum [t.amount | (_, t) <- tokensA]
        let reserveB = sum [t.amount | (_, t) <- tokensB]
        let totalLP = sum [lp.shares | (_, lp) <- lpSupply]
        
        return (reserveA, reserveB, totalLP)
    
    nonconsuming choice AddLiquidity : ContractId LP.LPToken
      with
        provider : Party
        tokenACid : ContractId T.Token
        tokenBCid : ContractId T.Token
        minLiquidity : Numeric 10
        deadline : Time
      controller provider
      do
        now <- getTime
        assertMsg "Expired" (now <= deadline)
        
        tokA <- fetch tokenACid
        tokB <- fetch tokenBCid
        assertMsg "Token A match" (tokA.symbol == symbolA)
        assertMsg "Token B match" (tokB.symbol == symbolB)
        
        (reserveA, reserveB, totalLP) <- exercise self GetReserves with viewer = provider
        
        -- Calculer liquidité optimale
        let (amountA, amountB, liquidity) = 
              if totalLP == 0.0
              then (tokA.amount, tokB.amount, sqrt (tokA.amount * tokB.amount))
              else 
                let ratio = reserveB / reserveA
                let optimalB = tokA.amount * ratio
                if optimalB <= tokB.amount
                then (tokA.amount, optimalB, tokA.amount * totalLP / reserveA)
                else 
                  let optimalA = tokB.amount / ratio
                  (optimalA, tokB.amount, tokB.amount * totalLP / reserveB)
        
        assertMsg "Min liquidity" (liquidity >= minLiquidity)
        
        -- Transferts atomiques vers poolParty
        (poolTokenA, providerRemainderA) <- exercise tokenACid T.Transfer with
          recipient = poolParty
          qty = amountA
          
        (poolTokenB, providerRemainderB) <- exercise tokenBCid T.Transfer with
          recipient = poolParty
          qty = amountB
        
        -- Mint LP tokens
        create LP.LPToken with
          lpTokenIssuer
          poolId
          owner = provider
          shares = liquidity
    
    nonconsuming choice SwapExactIn : (ContractId T.Token, Numeric 10)
      with
        trader : Party
        inputCid : ContractId T.Token
        outputSymbol : Text
        minOutput : Numeric 10
        deadline : Time
      controller trader
      do
        now <- getTime
        assertMsg "Expired" (now <= deadline)
        
        inputToken <- fetch inputCid
        let inputSymbol = inputToken.symbol
        assertMsg "Valid pair" (inputSymbol == symbolA || inputSymbol == symbolB)
        assertMsg "Different symbols" (inputSymbol /= outputSymbol)
        
        (reserveA, reserveB, _) <- exercise self GetReserves with viewer = trader
        
        let (reserveIn, reserveOut) = 
              if inputSymbol == symbolA 
              then (reserveA, reserveB)
              else (reserveB, reserveA)
        
        -- Calculer output avec frais
        let feeMultiplier = (10000 - intToDecimal feeBps) / 10000.0
        let amountInWithFee = inputToken.amount * feeMultiplier
        let numerator = amountInWithFee * reserveOut
        let denominator = reserveIn + amountInWithFee
        let amountOut = numerator / denominator
        
        assertMsg "Slippage" (amountOut >= minOutput)
        assertMsg "Liquidity" (amountOut < reserveOut)
        
        -- Transférer input vers pool
        (poolReceived, _) <- exercise inputCid T.Transfer with
          recipient = poolParty
          qty = inputToken.amount
        
        -- Sélectionner et transférer output depuis pool
        poolTokens <- queryFilter @T.Token
          (\t -> t.symbol == outputSymbol && t.owner == poolParty)
        
        case poolTokens of
          [] -> error "No liquidity"
          (outCid, outToken) : _ -> do
            (traderReceived, _) <- exercise outCid T.Transfer with
              recipient = trader
              qty = amountOut
            
            -- Vérifier invariant k
            (newReserveA, newReserveB, _) <- exercise self GetReserves with viewer = trader
            let kBefore = reserveA * reserveB
            let kAfter = newReserveA * newReserveB
            assertMsg "K increased" (kAfter >= kBefore * 0.9999)
            
            return (traderReceived, amountOut)
```

### Mathématiques AMM

```daml
module AMM.Math where

-- Racine carrée pour liquidité initiale
sqrt : Numeric 10 -> Numeric 10
sqrt x = 
  if x <= 0.0 then 0.0
  else 
    let approx = x / 2.0
        improve y = (y + x / y) / 2.0
        iterate n y = if n <= 0 then y else iterate (n - 1) (improve y)
    in iterate 10 approx

-- Conversion Int vers Decimal pour frais
intToDecimal : Int -> Numeric 10
intToDecimal i = fromIntegral i
```

---

## Phase 2 : Sécurisation et Atomicité (7 jours)

### Helper pour Défragmentation

```daml
module AMM.TokenUtils where
import Token.Token

-- Merge tous les tokens fragmentés d'un owner
mergeAllTokens : Party -> Text -> Party -> Update (ContractId Token)
mergeAllTokens issuer symbol owner = do
  tokens <- queryFilter @Token 
    (\t -> t.issuer == issuer && t.symbol == symbol && t.owner == owner)
  
  case tokens of
    [] -> error "No tokens found"
    [(cid, _)] -> return cid
    (cid1, tok1) : rest -> do
      -- Merger récursivement
      foldlA (\accCid (nextCid, _) -> 
        exercise accCid Merge with otherCid = nextCid
      ) cid1 rest
```

### Circuit Breaker Global

```daml
template EmergencyPause
  with
    admin : Party
    paused : Bool
    reason : Text
    pausedAt : Time
  where
    signatory admin
    
    key admin : Party
    maintainer key
    
    nonconsuming choice RequireActive : ()
      with checker : Party
      controller checker
      do assertMsg ("Paused: " <> reason) (not paused)
    
    choice Pause : ContractId EmergencyPause
      with newReason : Text
      controller admin
      do
        now <- getTime
        create this with 
          paused = True
          reason = newReason
          pausedAt = now
    
    choice Unpause : ContractId EmergencyPause
      controller admin
      do create this with paused = False, reason = ""
```

### Router avec Protection MEV

```daml
module AMM.Router where

template Router
  with
    operator : Party
  where
    signatory operator
    
    choice SwapWithProtection : (ContractId Token, Numeric 10)
      with
        poolCid : ContractId Pool
        trader : Party
        inputCid : ContractId Token
        outputSymbol : Text
        minOutput : Numeric 10
        deadline : Time
        maxPriceImpactBps : Int
      controller trader
      do
        -- Vérifier pause globale
        pauseContract <- lookupByKey @EmergencyPause operator
        case pauseContract of
          Some cid -> exercise cid RequireActive with checker = trader
          None -> return ()
        
        -- Obtenir prix avant swap
        pool <- fetch poolCid
        (r0, r1, _) <- exercise poolCid GetReserves with viewer = trader
        let priceBefore = r1 / r0
        
        -- Exécuter swap
        (output, amountOut) <- exercise poolCid SwapExactIn with
          trader, inputCid, outputSymbol, minOutput, deadline
        
        -- Vérifier impact prix
        (nr0, nr1, _) <- exercise poolCid GetReserves with viewer = trader
        let priceAfter = nr1 / nr0
        let priceImpact = abs (priceAfter - priceBefore) / priceBefore * 10000.0
        assertMsg "Price impact too high" (priceImpact <= intToDecimal maxPriceImpactBps)
        
        return (output, amountOut)
```

---

## Phase 3 : Tests Exhaustifs (3 jours)

### Tests de Conservation

```daml
module Test.Conservation where

conservationTest = scenario do
  -- Setup parties
  issuer <- getParty "Issuer"
  poolOp <- getParty "PoolOperator"
  poolParty <- getParty "PoolParty"
  lpIssuer <- getParty "LPIssuer"
  alice <- getParty "Alice"
  bob <- getParty "Bob"
  
  -- Créer tokens initiaux
  tokenA1 <- submit issuer $ create Token with
    issuer, owner = alice, symbol = "USDC", amount = 1000.0
    
  tokenB1 <- submit issuer $ create Token with
    issuer, owner = alice, symbol = "ETH", amount = 10.0
    
  -- Créer pool
  pool <- submit poolOp $ create Pool with
    poolOperator = poolOp
    poolParty
    lpTokenIssuer = lpIssuer
    tokenA = issuer
    tokenB = issuer
    symbolA = "ETH"  -- Ordre canonique
    symbolB = "USDC"
    feeBps = 30
    poolId = "ETH-USDC"
  
  -- Ajouter liquidité et vérifier conservation
  let totalBefore = 1000.0 + 10.0
  
  lpToken <- submit alice $ exercise pool AddLiquidity with
    provider = alice
    tokenACid = tokenA1
    tokenBCid = tokenB1
    minLiquidity = 0.0
    deadline = time (date 2030 Jan 1) 0 0 0
  
  -- Vérifier : sum(tokens alice) + sum(tokens poolParty) == totalBefore
  aliceTokens <- query @Token alice
  poolTokens <- query @Token poolParty
  
  let aliceSum = sum [t.amount | (_, t) <- aliceTokens, t.owner == alice]
  let poolSum = sum [t.amount | (_, t) <- poolTokens, t.owner == poolParty]
  
  assert (aliceSum + poolSum == totalBefore)
```

### Tests d'Atomicité

```daml
atomicityTest = scenario do
  -- Test 1: Deadline expirée -> rien ne bouge
  -- Test 2: Slippage dépassé -> rollback complet
  -- Test 3: Token inexistant -> erreur propre
  -- Test 4: Montants négatifs -> rejet immédiat
```

### Tests d'Attaques

```daml
module Test.Attacks where

attackTests = scenario do
  -- Test 1: Double-spend
  -- Tenter de réutiliser un contractId archivé
  -- Attendu: erreur "Contract not found"
  
  -- Test 2: Overflow
  -- Tenter swap avec amount = 10^20
  -- Attendu: erreur Numeric overflow
  
  -- Test 3: Sandwich attack
  -- Alice fait gros swap, Bob tente front-run
  -- Attendu: protection slippage bloque Bob
  
  -- Test 4: Pool draining
  -- Tenter de vider entièrement une réserve
  -- Attendu: "Insufficient liquidity"
  
  -- Test 5: Fragmentation attack
  -- Créer 1000 micro-tokens pour DOS
  -- Attendu: auto-merge les consolide
```

---

## Phase 4 : Déploiement Canton (3 jours)

### Configuration Canton

```hocon
canton {
  participants {
    operator {
      storage.type = postgres
      storage.config {
        url = "jdbc:postgresql://localhost:5432/operator"
        user = "canton"
        password = ${OPERATOR_DB_PASSWORD}
      }
      admin-api.port = 5001
      ledger-api.port = 6865
    }
    
    custody {
      storage.type = postgres  
      storage.config {
        url = "jdbc:postgresql://localhost:5432/custody"
        user = "canton"
        password = ${CUSTODY_DB_PASSWORD}
      }
      admin-api.port = 5002
      ledger-api.port = 6866
    }
  }
  
  monitoring {
    metrics.reporters = [{
      type = "prometheus"
      port = 9090
    }]
    
    health-check {
      enable = true
      port = 8080
    }
  }
}
```

### Script Bootstrap Canton

```scala
// bootstrap.canton
import com.digitalasset.canton._

// Créer domaine
val domain = bootstrap.domain("amm-domain", sequencerConnectionFrom = "grpc")

// Connecter participants
operator.domains.connect(domain)
custody.domains.connect(domain)

// Allouer parties
val poolOperator = operator.parties.enable("PoolOperator")
val poolParty = custody.parties.enable("PoolCustody")
val lpTokenIssuer = operator.parties.enable("LPIssuer")
val issuerUSDC = operator.parties.enable("IssuerUSDC")
val issuerETH = operator.parties.enable("IssuerETH")

// Grant des droits
custody.parties.set_display_name(poolParty, "AMM Custody Account")

// Uploader DAR
val darPath = ".daml/dist/amm-v2-1.0.0.dar"
operator.packages.upload(darPath)
custody.packages.upload(darPath)

// Vérifier upload
operator.health.ping(custody)
```

### Monitoring Production

```yaml
# prometheus/alerts.yml
groups:
  - name: amm_critical
    interval: 30s
    rules:
      - alert: HighSlippage
        expr: amm_slippage_bps > 500
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Slippage élevé détecté (>5%)"
          description: "Pool {{ $labels.pool }} - Slippage: {{ $value }}bps"
          
      - alert: LowLiquidity
        expr: amm_pool_tvl < 10000
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Liquidité critique"
          description: "Pool {{ $labels.pool }} TVL: {{ $value }}"
          
      - alert: TokenFragmentation
        expr: amm_tokens_per_owner > 100
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Fragmentation excessive"
          description: "Owner {{ $labels.owner }} a {{ $value }} tokens"
          
      - alert: TransactionLatency
        expr: amm_swap_duration_seconds > 2
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Latence élevée"
          description: "Swap prend {{ $value }}s en moyenne"
```

### Makefile Production

```makefile
.PHONY: build test demo deploy clean monitor

# Build
build:
	daml build
	@echo "Build successful: $$(ls -lh .daml/dist/*.dar)"

# Tests
test: test-unit test-integration test-attacks

test-unit:
	daml test --show-coverage

test-integration: build
	@echo "Starting sandbox for integration tests..."
	daml sandbox --port 6865 &
	sleep 5
	daml script --dar .daml/dist/amm-v2.dar \
	  --script-name Test.Integration:all \
	  --ledger-host localhost --ledger-port 6865
	pkill -f sandbox

test-attacks:
	daml test --files test/Attack.daml

# Local Development
sandbox: build
	daml sandbox --port 6865 .daml/dist/amm-v2.dar

demo: build
	daml script --dar .daml/dist/amm-v2.dar \
	  --script-name Scripts.Demo:fullDemo \
	  --ledger-host localhost --ledger-port 6865

# Canton Deployment
canton-start:
	canton -c canton/topology.conf daemon \
	  --bootstrap canton/bootstrap.canton

canton-deploy: build
	canton run-script canton/deploy.canton

# Monitoring
monitor:
	docker-compose -f monitoring/docker-compose.yml up -d
	@echo "Grafana: http://localhost:3000"
	@echo "Prometheus: http://localhost:9090"

# Cleanup
clean:
	rm -rf .daml target
	docker-compose -f monitoring/docker-compose.yml down
	pkill -f canton || true
	pkill -f sandbox || true
```

---

## Métriques de Succès

### Phase 0 ✓
- Infrastructure projet créée
- Configuration Canton validée
- CI/CD pipeline configuré

### Phase 1 ✓
- Zero duplication tokens (archive avant create)
- Conservation stricte : sum(in) == sum(out)
- LP tokens proportionnels aux parts
- Tests passent avec Numeric 10
- Pas de réserves stockées, interrogation on-ledger

### Phase 2 ✓
- Pause fonctionne en < 100ms
- Aucun token orphelin possible
- Slippage détecté et bloqué
- Fragmentation auto-mergée
- Protection MEV opérationnelle

### Phase 3 ✓
- 100% couverture code
- Résistance aux 5 attaques listées
- Performance < 500ms par swap
- Tests de propriété (QuickCheck style)

### Phase 4 ✓
- Canton multi-participant opérationnel
- Monitoring temps réel avec alertes
- Recovery testé après crash
- Documentation opérationnelle complète


### Planning Détaillé

| Phase | Durée | Effort | Livrables |
|-------|-------|--------|-----------|
| Phase 0 | 3 jours | 3 j-p | Infrastructure, CI/CD |
| Phase 1 | 7 jours | 14 j-p | Core AMM fonctionnel |
| Phase 2 | 7 jours | 14 j-p | Sécurisation, MEV protection |
| Phase 3 | 3 jours | 6 j-p | Tests exhaustifs |
| Phase 4 | 3 jours | 6 j-p | Déploiement Canton |
| **Total** | **23 jours** | **43 j-p** | **MVP Production** |

### Budget avec Marge

**Risques identifiés** :
- Fragmentation tokens complexe : +3 jours
- Intégration Canton/monitoring : +2 jours  
- Découvertes audit sécurité : +5 jours
- Formation équipe DAML : +3 jours

**Budget réaliste : 30-35 jours calendaires** (60-70 jours-personne)

---

## Risques et Mitigation

### Risques Techniques

| Risque | Impact | Probabilité | Mitigation |
|--------|--------|-------------|------------|
| Arithmetic overflow | Critique | Moyen | Numeric 10, tests limites |
| Token duplication | Critique | Faible | Archive before create |
| MEV/Sandwich | Élevé | Élevé | Slippage protection, deadlines |
| Fragmentation DOS | Moyen | Moyen | Auto-merge, limites |
| Canton sync issues | Élevé | Faible | Multi-participant tests |

### Risques Opérationnels

| Risque | Impact | Mitigation |
|--------|--------|------------|
| Perte clés admin | Critique | Multi-sig, key rotation |
| Pause prolongée | Élevé | Timelock, governance |
| Montée en charge | Moyen | Sharding, Canton domains |
| Audit failures | Élevé | Tests préventifs, code review |

---

## Checklist Production

### Sécurité ✓
- [ ] Tous les `archive self` avant `create`
- [ ] Numeric 10 partout
- [ ] Frais en Int bps
- [ ] Clés canoniques uniques
- [ ] LP tokens avec mint/burn
- [ ] Protection slippage/deadline
- [ ] Circuit breaker global
- [ ] Tests d'attaque passés

### Performance ✓
- [ ] < 500ms par swap
- [ ] < 1s add/remove liquidity
- [ ] Auto-merge fragmentation
- [ ] Query optimization
- [ ] Canton topology optimisée

### Opérationnel ✓
- [ ] Monitoring Prometheus/Grafana
- [ ] Alertes configurées
- [ ] Logs structurés
- [ ] Backup/Recovery testé
- [ ] Documentation API
- [ ] Runbook incidents

### Conformité ✓
- [ ] Audit code externe
- [ ] Tests de charge
- [ ] Plan de continuité
- [ ] Formation équipe support

---

## Commandes Rapides

```bash
# Development
make build          # Compiler DAML
make test          # Tous les tests
make demo          # Démo interactive
make sandbox       # Sandbox local

# Production
make canton-start   # Démarrer Canton
make canton-deploy  # Déployer contrats
make monitor       # Lancer monitoring

# Maintenance
make clean         # Nettoyer tout
```
