# Module 01 - Architecture Globale du Système ClearportX

**Version**: 2.0.0
**Dernière mise à jour**: 20 Octobre 2025
**Prérequis**: Aucun - Ce module est le point d'entrée
**Temps de lecture**: ~30 minutes

[← Retour à l'index](./00_INDEX.md) | [Module suivant: DAML Core Templates →](./02_DAML_CORE_TEMPLATES.md)

---

## Table des Matières

1. [Vue d'Ensemble du Système](#1-vue-densemble-du-système)
2. [Stack Technologique](#2-stack-technologique)
3. [Architecture en Couches](#3-architecture-en-couches)
4. [Composants Principaux](#4-composants-principaux)
5. [Flow de Données](#5-flow-de-données)
6. [Choix de Design Critiques](#6-choix-de-design-critiques)
7. [Déploiement Multi-Environnement](#7-déploiement-multi-environnement)

---

## 1. Vue d'Ensemble du Système

ClearportX est un **DEX (Decentralized Exchange)** de type **AMM (Automated Market Maker)** construit pour **Canton Network**.

### 1.1 Qu'est-ce qu'un AMM?

Un AMM utilise une **formule mathématique** au lieu d'un order book traditionnel pour déterminer les prix:

```
Constant Product Formula: x * y = k

Où:
- x = Réserve du token A dans le pool
- y = Réserve du token B dans le pool  
- k = Constante (product invariant)

Exemple:
Pool ETH/USDC: 1 ETH * 2000 USDC = 2000 (k)
Si quelqu'un achète 0.1 ETH, le nouveau k doit rester ~2000
```

### 1.2 Positionnement

```
┌─────────────────────────────────────────────────────────────┐
│                    Canton Network Ecosystem                 │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Wallet     │  │ ClearportX   │  │    Other     │      │
│  │     App      │  │     DEX      │  │    DApps     │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
│         │                  │                 │              │
│         └──────────────────┴─────────────────┘              │
│                            │                                │
│         ┌──────────────────┴──────────────────┐             │
│         │      Canton Network Ledger          │             │
│         │   (Distributed Privacy-Preserving)  │             │
│         └─────────────────────────────────────┘             │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**ClearportX** est une **application décentralisée** qui fournit:
- ✅ Swaps de tokens sans intermédiaire
- ✅ Pools de liquidité avec AMM
- ✅ Protocol fees pour sustenir le développement
- ✅ Observabilité complète (metrics Prometheus/Grafana)

---

## 2. Stack Technologique

### 2.1 Smart Contracts Layer

```
┌─────────────────────────────────────────┐
│         DAML 3.3.0 Smart Contracts      │
├─────────────────────────────────────────┤
│                                         │
│  - Pool.daml       (AMM Logic)          │
│  - Token.daml      (ERC20-like)         │
│  - LPToken.daml    (Liquidity Tokens)   │
│  - SwapRequest.daml (2-step swaps)      │
│  - AtomicSwap.daml (1-step swaps)       │
│  - Receipt.daml    (Proof of swap)      │
│                                         │
│  Language: Haskell-like functional      │
│  Runtime: Canton Ledger                 │
│                                         │
└─────────────────────────────────────────┘
```

**Pourquoi DAML 3.3.0?**
- ✅ **Functional** et **type-safe** (pas de bugs runtime)
- ✅ **Privacy-preserving** (Canton Network)
- ✅ **Multi-party workflows** natifs
- ⚠️  **Pas de contract keys** en 3.3.0 → Architecture ContractId-Only

### 2.2 Backend Layer

```
┌─────────────────────────────────────────┐
│      Spring Boot 3.4.2 Backend          │
├─────────────────────────────────────────┤
│                                         │
│  REST API:                              │
│    - SwapController.java                │
│    - LiquidityController.java           │
│    - LedgerController.java              │
│                                         │
│  Services:                              │
│    - LedgerReader (Canton gRPC)         │
│    - IdempotencyService (Cache)         │
│    - HealthService (Monitoring)         │
│                                         │
│  Security:                              │
│    - OAuth2 JWT (Keycloak)              │
│    - Rate Limiter (0.4 TPS devnet)      │
│                                         │
│  Language: Java 17                      │
│  Framework: Spring Boot + WebFlux       │
│                                         │
└─────────────────────────────────────────┘
```

**Pourquoi Spring Boot?**
- ✅ **Production-ready** avec metrics, health checks
- ✅ **Async** avec CompletableFuture
- ✅ **OAuth2** intégré pour Canton Network
- ✅ **Observability** avec Micrometer + Prometheus

### 2.3 Infrastructure Layer

```
┌─────────────────────────────────────────┐
│      Canton Network Infrastructure      │
├─────────────────────────────────────────┤
│                                         │
│  Canton Participant:                    │
│    - Ledger API (gRPC)                  │
│    - Transaction processing             │
│    - Privacy engine                     │
│                                         │
│  PQS (Participant Query Service):       │
│    - PostgreSQL indexing                │
│    - Fast queries (vs Ledger API)       │
│    - Contract history                   │
│                                         │
│  Keycloak:                              │
│    - OAuth2 / JWT                       │
│    - Party mapping                      │
│    - Multi-tenant auth                  │
│                                         │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│         Observability Stack             │
├─────────────────────────────────────────┤
│                                         │
│  Prometheus:                            │
│    - Metrics scraping (/actuator)       │
│    - Time-series database               │
│    - Alerting rules                     │
│                                         │
│  Grafana:                               │
│    - Dashboards (swaps, pools, fees)    │
│    - Visualizations                     │
│    - Alerts UI                          │
│                                         │
└─────────────────────────────────────────┘
```

---

## 3. Architecture en Couches

### 3.1 Vue Complète

```
┌───────────────────────────────────────────────────────────────────┐
│                         USER / FRONTEND                           │
│                  (React App @ canton-website)                     │
└─────────────────────────┬─────────────────────────────────────────┘
                          │
                          │ HTTPS / JSON
                          │
┌─────────────────────────▼─────────────────────────────────────────┐
│                    BACKEND - Spring Boot                          │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  Security Layer                                             │ │
│  │  - RateLimiterConfig (0.4 TPS devnet)                       │ │
│  │  - WebSecurityConfig (OAuth2 + Basic Auth)                  │ │
│  │  - IdempotencyService (15min cache)                         │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  REST Controllers (API Endpoints)                           │ │
│  │  - SwapController      POST /api/swap/atomic                │ │
│  │  - LiquidityController POST /api/liquidity/add              │ │
│  │  - LedgerController    GET  /api/pools                      │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  Service Layer (Business Logic)                            │ │
│  │  - LedgerReader (Canton Ledger API gRPC client)             │ │
│  │  - ClearportXInitService (Bootstrap)                        │ │
│  │  - LedgerHealthService (Monitoring)                         │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  Metrics Layer (Observability)                             │ │
│  │  - SwapMetrics (Counters: executed, failed)                │ │
│  │  - PoolMetricsRefresher (Gauges: reserves, k-invariant)    │ │
│  │  - Micrometer → Prometheus exposition                       │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
└─────────────────────────┬─────────────────────────────────────────┘
                          │
                          │ gRPC Ledger API
                          │
┌─────────────────────────▼─────────────────────────────────────────┐
│                  CANTON PARTICIPANT                               │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  Ledger API (gRPC Server)                                   │ │
│  │  - Command Submission Service                               │ │
│  │  - Transaction Service                                      │ │
│  │  - Active Contract Set Service                              │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  DAML Execution Engine                                      │ │
│  │  - Smart contract interpretation                            │ │
│  │  - Authorization checks                                     │ │
│  │  - Privacy preservation                                     │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  Canton Sync Protocol                                       │ │
│  │  - Multi-domain synchronization                             │ │
│  │  - Consensus                                                │ │
│  │  - Finality                                                 │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
└─────────────────────────┬─────────────────────────────────────────┘
                          │
                          │ Indexing
                          │
┌─────────────────────────▼─────────────────────────────────────────┐
│              PQS (Participant Query Service)                      │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  PostgreSQL Database                                        │ │
│  │  - __contracts (all contracts indexed)                      │ │
│  │  - __events (transaction log)                               │ │
│  │  - __contract_tpe (template metadata)                       │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  Usage: Fast queries vs Ledger API                               │
│  - Health checks (packageId verification)                        │
│  - Contract history                                              │
│  - Bulk reads                                                    │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

### 3.2 Séparation des Responsabilités

| Couche | Responsabilité | Technologie |
|--------|----------------|-------------|
| **Frontend** | UI/UX, User interactions | React, TypeScript |
| **Backend** | API, Business logic, Auth | Spring Boot, Java 17 |
| **Smart Contracts** | State machine, Rules | DAML 3.3.0 |
| **Canton** | Ledger, Privacy, Consensus | Canton Participant |
| **PQS** | Fast queries, Indexing | PostgreSQL |
| **Observability** | Metrics, Dashboards | Prometheus, Grafana |

---

## 4. Composants Principaux

### 4.1 Smart Contracts DAML (8 templates)

```
TEMPLATES CORE:
┌──────────────────────────────────────────────┐
│  Pool.daml                                   │
│  - AMM logic (x * y = k)                     │
│  - AddLiquidity / RemoveLiquidity            │
│  - AtomicSwap (1-step)                       │
│  - 446 lignes, le plus complexe              │
├──────────────────────────────────────────────┤
│  Token.daml                                  │
│  - ERC20-like fungible token                 │
│  - Transfer, TransferSplit                   │
│  - Merge (consolidation)                     │
├──────────────────────────────────────────────┤
│  LPToken.daml                                │
│  - Liquidity Provider tokens                 │
│  - Mint (AddLiquidity)                       │
│  - Burn (RemoveLiquidity)                    │
└──────────────────────────────────────────────┘

TEMPLATES SWAP:
┌──────────────────────────────────────────────┐
│  SwapRequest.daml                            │
│  - 2-step swap: PrepareSwap → ExecuteSwap   │
│  - Legacy, mais toujours supporté            │
├──────────────────────────────────────────────┤
│  AtomicSwap.daml                             │
│  - 1-step swap (preferred)                   │
│  - Évite le stale poolCid problem            │
├──────────────────────────────────────────────┤
│  Receipt.daml                                │
│  - Proof of swap execution                   │
│  - Pour auditing                             │
└──────────────────────────────────────────────┘

TEMPLATES DISCOVERY:
┌──────────────────────────────────────────────┐
│  PoolAnnouncement.daml                       │
│  - Immutable, append-only                    │
│  - Off-chain discovery mechanism             │
│  - Nécessaire car pas de contract keys      │
└──────────────────────────────────────────────┘

UTILS:
┌──────────────────────────────────────────────┐
│  Types.daml                                  │
│  - Constantes (minLiquidity, etc.)           │
│  - Types communs                             │
├──────────────────────────────────────────────┤
│  ProtocolFees.daml                           │
│  - Logique split 25% / 75%                   │
│  - ClearportX treasury                       │
├──────────────────────────────────────────────┤
│  RouteExecution.daml                         │
│  - Multi-hop swaps                           │
│  - Path finding (TODO)                       │
└──────────────────────────────────────────────┘
```

### 4.2 Backend Java (5 Controllers + 4 Services)

```
CONTROLLERS (REST API):
┌─────────────────────────────────────────────────┐
│  SwapController.java (~500 lignes)              │
│  - POST /api/swap/atomic                        │
│  - POST /api/swap/prepare                       │
│  - POST /api/swap/execute                       │
│  - Auto-discovery pools par symboles            │
│  - Idempotency (cache 15min)                    │
├─────────────────────────────────────────────────┤
│  LiquidityController.java (~400 lignes)         │
│  - POST /api/liquidity/add                      │
│  - POST /api/liquidity/remove                   │
├─────────────────────────────────────────────────┤
│  LedgerController.java (~300 lignes)            │
│  - GET /api/pools                               │
│  - GET /api/tokens                              │
│  - Query ACS via LedgerReader                   │
├─────────────────────────────────────────────────┤
│  ClearportXInitController.java (~200 lignes)    │
│  - POST /api/init/tokens                        │
│  - POST /api/init/pool                          │
│  - Bootstrap système                            │
├─────────────────────────────────────────────────┤
│  LedgerHealthController.java (~150 lignes)      │
│  - GET /api/health/ledger                       │
│  - PackageId, sync status, contract count       │
└─────────────────────────────────────────────────┘

SERVICES (Business Logic):
┌─────────────────────────────────────────────────┐
│  LedgerReader.java                              │
│  - gRPC client Canton Ledger API                │
│  - pools(), tokens(), lpTokens()                │
│  - Query ACS (Active Contract Set)              │
├─────────────────────────────────────────────────┤
│  IdempotencyService.java                        │
│  - Caffeine cache 15 minutes                    │
│  - Prévention doubles swaps                     │
│  - Thread-safe avec ConcurrentHashMap           │
├─────────────────────────────────────────────────┤
│  ClearportXInitService.java                     │
│  - Bootstrap tokens (USDC, ETH, BTC)            │
│  - Create pools avec liquidité initiale         │
│  - Dev/test setup                               │
├─────────────────────────────────────────────────┤
│  LedgerHealthService.java                       │
│  - Health checks PQS vs Canton                  │
│  - PackageId tracking (version DAR)             │
│  - Contract count monitoring                    │
└─────────────────────────────────────────────────┘
```

### 4.3 Infrastructure (Sécurité + Metrics)

```
SÉCURITÉ:
┌─────────────────────────────────────────────────┐
│  RateLimiterConfig.java                         │
│  - Token bucket (AtomicLong + CAS)              │
│  - Global: 0.4 TPS (devnet requirement)         │
│  - Per-party: 10 RPM                            │
│  - HTTP 429 sur violations                      │
│  - Thread-safe, zero dépendances externes       │
├─────────────────────────────────────────────────┤
│  WebSecurityConfig.java                         │
│  - OAuth2 JWT (Canton Network Keycloak)         │
│  - Basic Auth pour actuator                     │
│  - CORS pour frontend                           │
│  - Spring Security 3.x                          │
└─────────────────────────────────────────────────┘

METRICS:
┌─────────────────────────────────────────────────┐
│  SwapMetrics.java                               │
│  - Counter: clearportx_swap_executed_total      │
│  - Counter: clearportx_swap_prepared_total      │
│  - Counter: clearportx_swap_failed_total        │
│  - Counter: clearportx_fees_protocol_collected  │
│  - Counter: clearportx_fees_lp_collected        │
├─────────────────────────────────────────────────┤
│  PoolMetricsRefresher.java                      │
│  - @Scheduled fixedDelay=10s                    │
│  - Gauge: clearportx_pool_active_count          │
│  - Gauge: clearportx_pool_reserve_amount        │
│  - Gauge: clearportx_pool_k_invariant           │
│  - Query ACS toutes les 10 secondes             │
└─────────────────────────────────────────────────┘
```

---

## 5. Flow de Données

### 5.1 Atomic Swap Flow (Simplifié)

```
┌─────────────┐
│   FRONTEND  │
│   (React)   │
└──────┬──────┘
       │
       │ POST /api/swap/atomic
       │ {
       │   "inputSymbol": "USDC",
       │   "inputAmount": "100.0",
       │   "outputSymbol": "ETH",
       │   "minOutput": "0.03"
       │ }
       │
       ▼
┌──────────────────────────────────────────┐
│  BACKEND - SwapController.java           │
├──────────────────────────────────────────┤
│                                          │
│  1. Rate Limiter Check                   │
│     ├─ Global: 0.4 TPS                   │
│     └─ Per-party: 10 RPM                 │
│        → HTTP 429 si exceeded            │
│                                          │
│  2. Idempotency Check                    │
│     └─ Cache lookup (15min)              │
│        → Return cached si duplicate      │
│                                          │
│  3. OAuth2 JWT Extraction                │
│     └─ Get party from JWT claim          │
│                                          │
│  4. Query Pools (LedgerReader)           │
│     └─ Find pool USDC/ETH                │
│        → Auto-discovery by symbols       │
│                                          │
│  5. Submit Command to Canton             │
│     └─ exerciseAtomicSwap()              │
│                                          │
└──────────┬───────────────────────────────┘
           │
           │ gRPC Ledger API
           │
           ▼
┌──────────────────────────────────────────┐
│  CANTON PARTICIPANT                      │
├──────────────────────────────────────────┤
│                                          │
│  1. Validate Command                     │
│     ├─ Check authorization               │
│     └─ Verify signatures                 │
│                                          │
│  2. Execute DAML Choice                  │
│     └─ Pool.AtomicSwap                   │
│                                          │
└──────────┬───────────────────────────────┘
           │
           │ DAML Execution
           │
           ▼
┌──────────────────────────────────────────┐
│  DAML - Pool.AtomicSwap Choice           │
├──────────────────────────────────────────┤
│                                          │
│  1. Fetch trader's input token           │
│     └─ ContractId Token (USDC)           │
│                                          │
│  2. Split protocol fee (25%)             │
│     ├─ protocolFee = 100 * 0.003 * 0.25  │
│     │              = 0.075 USDC           │
│     └─ exercise TransferSplit            │
│        → To protocolFeeReceiver          │
│                                          │
│  3. Transfer remainder to pool           │
│     └─ 100 - 0.075 = 99.925 USDC         │
│                                          │
│  4. Calculate AMM output                 │
│     ├─ feeMul = 0.997 (0.3% fee)         │
│     ├─ ainFee = 99.925 * 0.997           │
│     └─ aout = (ainFee * rout) / (rin + ainFee)
│        → 0.0299 ETH                      │
│                                          │
│  5. Slippage check                       │
│     └─ 0.0299 >= 0.03 ❌ FAIL            │
│        → Adjust minOutput to 0.029       │
│        → 0.0299 >= 0.029 ✅ OK           │
│                                          │
│  6. Price impact check                   │
│     └─ impBps <= maxPriceImpactBps       │
│                                          │
│  7. Transfer output to trader            │
│     └─ 0.0299 ETH                        │
│                                          │
│  8. Update pool reserves                 │
│     ├─ New reserveA = old + 99.925       │
│     └─ New reserveB = old - 0.0299       │
│                                          │
│  9. Archive old Pool, create new Pool    │
│     └─ Archive-and-recreate pattern      │
│                                          │
└──────────┬───────────────────────────────┘
           │
           │ Transaction Complete
           │
           ▼
┌──────────────────────────────────────────┐
│  BACKEND - Response                      │
├──────────────────────────────────────────┤
│                                          │
│  1. Update Metrics                       │
│     ├─ clearportx_swap_executed_total++  │
│     ├─ clearportx_fees_protocol += 0.075 │
│     └─ clearportx_fees_lp += 0.225       │
│                                          │
│  2. Return Response                      │
│     └─ {                                 │
│          "receiptCid": "00abc...",       │
│          "outputAmount": "0.0299",       │
│          "protocolFee": "0.075",         │
│          "lpFee": "0.225"                │
│        }                                 │
│                                          │
└──────────┬───────────────────────────────┘
           │
           │ JSON Response
           │
           ▼
┌─────────────┐
│   FRONTEND  │
│   Display   │
│   Success   │
└─────────────┘
```

### 5.2 Data Flow - Query Pools

```
Frontend                Backend                Canton              PQS
   │                       │                      │                  │
   │  GET /api/pools       │                      │                  │
   ├──────────────────────>│                      │                  │
   │                       │                      │                  │
   │                       │ Query ACS (Active    │                  │
   │                       │  Contract Set)       │                  │
   │                       ├─────────────────────>│                  │
   │                       │                      │                  │
   │                       │ Stream Pool contracts│                  │
   │                       │<─────────────────────┤                  │
   │                       │                      │                  │
   │                       │ Filter active only   │                  │
   │                       │ (reserveA > 0 &&     │                  │
   │                       │  reserveB > 0)       │                  │
   │                       │                      │                  │
   │ JSON: [Pool1, Pool2]  │                      │                  │
   │<──────────────────────┤                      │                  │
   │                       │                      │                  │
```

**Note**: PQS n'est PAS utilisé pour query pools en temps réel car le backend utilise directement **Ledger API ACS** qui donne l'état actuel sans latence d'indexing.

PQS est utilisé pour:
- Health checks (packageId)
- Historical queries
- Bulk data analysis

---

## 6. Choix de Design Critiques

### 6.1 Architecture ContractId-Only (DAML 3.3.0)

**Problème**: DAML 3.3.0 a **supprimé le support des contract keys**

```haskell
-- ❌ IMPOSSIBLE en DAML 3.3.0:
template Pool
  with
    ...
  where
    key (operator, symbolA, symbolB) : (Party, Text, Text)  -- ❌ REMOVED

-- ❌ IMPOSSIBLE en DAML 3.3.0:
choice ExecuteSwap : ...
  do
    pool <- lookupByKey @Pool (operator, "ETH", "USDC")  -- ❌ NO lookupByKey!
```

**Solution**: Passer explicitement les **ContractIds** partout

```haskell
-- ✅ DAML 3.3.0 Pattern:
choice ExecuteSwap : ...
  with
    poolCid : ContractId Pool  -- ✅ CID passé explicitement
  do
    pool <- fetch poolCid  -- ✅ fetch by CID works
```

**Implication**: 
- Le **backend** doit maintenir une connaissance des ContractIds
- **PoolAnnouncement** template pour discovery off-chain
- **LedgerReader** query l'ACS pour trouver les pools

### 6.2 Archive-and-Recreate Pattern

**Pourquoi?** DAML contracts sont **immutables**. Pour "modifier" un contract, il faut:
1. **Archive** l'ancien contract
2. **Create** un nouveau contract avec nouvelles valeurs

```haskell
-- Exemple: Update reserves après swap
choice ArchiveAndUpdateReserves : ContractId Pool
  with
    updatedReserveA : Numeric 10
    updatedReserveB : Numeric 10
  controller poolParty
  do
    archive self              -- ← Archive ancien Pool
    create this with          -- ← Create nouveau Pool avec...
      reserveA = updatedReserveA  -- ← ... nouvelles réserves
      reserveB = updatedReserveB
```

**Avantages**:
- ✅ **Immutability** → Audit trail complet
- ✅ **History preserved** → Toutes les versions archivées visibles
- ✅ **Thread-safe** → Pas de race conditions

**Inconvénients**:
- ⚠️ **ContractId change** → Backend doit tracker nouveau CID
- ⚠️ **Stale CID problem** → Si 2 swaps simultanés, le 2ème fail

**Solution au stale CID**: **AtomicSwap choice** (1-step au lieu de 2-step)

### 6.3 Protocol Fees Architecture

**Objectif**: 
- 25% des fees → ClearportX Treasury (développement, maintenance)
- 75% des fees → LPs (liquidity providers dans le pool)

**Implémentation**:

```haskell
-- 1. Total fee = 0.3% (30 bps)
let totalFeeRate = 0.003
let totalFeeAmount = inputAmount * totalFeeRate  -- Ex: 100 * 0.003 = 0.3

-- 2. Split 25/75
let protocolFeeAmount = totalFeeAmount * 0.25  -- 0.3 * 0.25 = 0.075
let poolFeeAmount = totalFeeAmount * 0.75      -- 0.3 * 0.75 = 0.225

-- 3. Extract protocol fee AVANT le swap
(protocolFeeCid, remainderCid) <- exercise tokenCid TransferSplit with
  recipient = protocolFeeReceiver  -- ← ClearportX treasury party
  qty = protocolFeeAmount

-- 4. Le poolFee reste dans le pool → augmente réserves → augmente valeur LP tokens
let amountForPool = inputAmount - protocolFeeAmount
-- Ce montant entre dans le pool, donc les LPs bénéficient automatiquement
```

**Pourquoi ce design?**
- ✅ **Transparent**: Fees visibles on-chain
- ✅ **Automatique**: Pas besoin de claim manuel pour LPs
- ✅ **Alignement**: LPs sont incentivés (75% des fees)
- ✅ **Sustaining**: ClearportX a des revenus (25% des fees)

### 6.4 Idempotency (Backend)

**Problème**: Si l'utilisateur retry un swap, on risque de l'exécuter 2 fois!

```
User clicks "Swap" → Request 1 → Processing...
User impatient, clicks again → Request 2 → Processing...
Result: 2 swaps executed! ❌
```

**Solution**: **IdempotencyService** avec cache 15 minutes

```java
// SwapController.java
String idempotencyKey = generateKey(party, req);  
// Key = party + inputSymbol + amount + nonce

if (idempotencyService.isDuplicate(idempotencyKey)) {
    return cachedResponse;  // ← Return result du 1er swap
}

// Execute swap...
result = executeSwap(req);

// Cache result
idempotencyService.cache(idempotencyKey, result, Duration.ofMinutes(15));
```

**Avantages**:
- ✅ **Safe retries**: User peut retry sans double-spend
- ✅ **UX améliorée**: Pas d'erreur "déjà exécuté"
- ✅ **15 min cache**: Après 15min, on peut re-swap les mêmes montants

---

## 7. Déploiement Multi-Environnement

### 7.1 Localnet (Development)

```
┌─────────────────────────────────────────┐
│          LOCALNET SETUP                 │
├─────────────────────────────────────────┤
│                                         │
│  Docker Compose:                        │
│    - canton-participant                 │
│    - postgres (PQS database)            │
│    - keycloak (OAuth2)                  │
│    - backend-service (Spring Boot)      │
│                                         │
│  Configuration:                         │
│    - application.yml (default)          │
│    - rate-limiter.enabled = false       │
│    - oauth2 via localhost:8082          │
│                                         │
│  Usage:                                 │
│    make start                           │
│    → Tout démarre en local              │
│                                         │
└─────────────────────────────────────────┘
```

### 7.2 Canton Network Devnet

```
┌─────────────────────────────────────────┐
│     CANTON NETWORK DEVNET               │
├─────────────────────────────────────────┤
│                                         │
│  Requirements:                          │
│    - IP whitelisted by SVs              │
│    - Rate limiting: 0.5 TPS MAX         │
│    - Production security                │
│                                         │
│  Configuration:                         │
│    - application-devnet.yml             │
│    - rate-limiter.enabled = true        │
│    - global-tps = 0.4 (safe margin)     │
│    - oauth2 via Canton Keycloak         │
│                                         │
│  Deployment:                            │
│    export SPRING_PROFILES_ACTIVE=devnet │
│    ./gradlew build                      │
│    docker-compose -f compose.devnet.yml │
│                                         │
└─────────────────────────────────────────┘
```

**Différences clés**:

| Feature | Localnet | Devnet |
|---------|----------|--------|
| Rate Limiting | ❌ Disabled | ✅ Enabled (0.4 TPS) |
| OAuth2 | Local Keycloak | Canton Network |
| Canton | Local participant | Canton Network |
| Security | Dev mode | Production |
| Metrics | Optional | Required |

---

## 📝 Résumé

Vous avez maintenant une **vue d'ensemble complète** du système ClearportX:

✅ **Stack**: DAML 3.3.0 + Spring Boot + Canton + Prometheus/Grafana
✅ **Architecture**: 3 couches (Smart Contracts, Backend, Infrastructure)
✅ **Components**: 8 templates DAML, 5 controllers, 4 services
✅ **Flow**: Frontend → Backend (rate limit + auth) → Canton → DAML execution
✅ **Design**: ContractId-Only, Archive-and-Recreate, Protocol Fees 25/75
✅ **Deployment**: Localnet (dev) vs Devnet (production-like)

**Prochaine étape**: Plongez dans les [templates DAML Core](./02_DAML_CORE_TEMPLATES.md) pour comprendre la logique AMM en détail!

---

[← Retour à l'index](./00_INDEX.md) | [Module suivant: DAML Core Templates →](./02_DAML_CORE_TEMPLATES.md)
