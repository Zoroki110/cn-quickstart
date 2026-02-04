# Guide Technique Complet - ClearportX DEX v2.0

**Version**: 2.0.0 - Guide Modulaire Complet
**Système**: DAML 3.3.0 + Spring Boot Backend + Canton Network
**Scope**: Smart Contracts + Backend Java + Infrastructure + Déploiement
**Dernière mise à jour**: 20 Octobre 2025

---

## 📚 Table des Matières Générale

Ce guide technique couvre **l'intégralité du système ClearportX DEX**, depuis les smart contracts DAML jusqu'à l'infrastructure de production sur Canton Network.

### Structure du Guide

Le guide est organisé en **8 modules** pour faciliter la navigation et la compréhension:

---

### [01 - Architecture Globale](./01_ARCHITECTURE_GLOBALE.md) (~1000 lignes)

**Contenu**:
- Vue d'ensemble du système complet
- Stack technologique (DAML 3.3.0, Canton, Spring Boot, Postgres, Prometheus, Grafana)
- Diagrammes ASCII de l'architecture end-to-end
- Flow de données: Frontend → Backend → Ledger API → Canton → PQS → Metrics
- Relations entre tous les composants

**Vous apprendrez**:
- Comment tous les composants s'articulent
- Le flow complet d'une transaction swap
- L'architecture de données et de communication
- Les choix de design critiques du système

---

### [02 - DAML Core Templates](./02_DAML_CORE_TEMPLATES.md) (~2000 lignes)

**Contenu**:
- **Pool.daml** - Détail ligne par ligne complet (446 lignes)
  - Template Pool avec tous les champs expliqués
  - AddLiquidity: Comment ajouter de la liquidité avec slippage protection
  - RemoveLiquidity: Retrait de liquidité et burn de LP tokens
  - ArchiveAndUpdateReserves: Pattern archive-and-recreate
  - AtomicSwap: Swap en une étape
- **Token.daml** - Token standard avec TransferSplit pour protocol fees
- **LPToken.daml** - LP tokens (mint/burn)

**Vous apprendrez**:
- Architecture ContractId-Only (pas de contract keys en DAML 3.3.0)
- Pattern Archive-and-Recreate pour mises à jour
- Formules AMM: Constant Product (x * y = k)
- Slippage protection et validations
- Protocol fees (25% ClearportX, 75% LPs)

---

### [03 - DAML Swap System](./03_DAML_SWAP_SYSTEM.md) (~1500 lignes)

**Contenu**:
- **SwapRequest.daml** - Pattern 2-step Proposal-Accept
  - PrepareSwap: Extraction protocol fee + transfer tokens
  - ExecuteSwap: Calcul AMM + envoi output
- **AtomicSwap.daml** - Swap en 1 étape (nouveau, preferred)
- **Receipt.daml** - Proof of swap execution
- **PoolAnnouncement.daml** - Discovery mechanism
- **Types.daml** - Types communs et constantes
- **ProtocolFees.daml** - Logique de distribution des fees
- **RouteExecution.daml** - Multi-hop swaps

**Vous apprendrez**:
- Pourquoi 2-step vs 1-step swap
- Calcul AMM avec fees: `aout = (ain * feeMul * rout) / (rin + ain * feeMul)`
- Price impact et slippage checks
- Protocol fee split (25/75)
- Multi-hop routing

---

### [04 - Backend Controllers](./04_BACKEND_CONTROLLERS.md) (~2000 lignes)

**Contenu** (détail ligne par ligne du code Java):
- **SwapController.java** (~500 lignes analysées)
  - `POST /api/swap/atomic` - Atomic swap endpoint
  - `POST /api/swap/prepare` - PrepareSwap
  - `POST /api/swap/execute` - ExecuteSwap
  - Idempotency avec cache 15 minutes
  - Auto-discovery des pools par symboles
- **LiquidityController.java** (~400 lignes)
  - `POST /api/liquidity/add` - Add liquidity
  - `POST /api/liquidity/remove` - Remove liquidity
- **LedgerController.java** (~300 lignes)
  - `GET /api/pools` - List all active pools
  - `GET /api/tokens` - List user tokens
- **ClearportXInitController.java** (~200 lignes)
  - `POST /api/init/tokens` - Bootstrap tokens
  - `POST /api/init/pool` - Create pool
- **LedgerHealthController.java** (~150 lignes)
  - `GET /api/health/ledger` - Health check avec packageId

**Vous apprendrez**:
- Comment le backend interagit avec Canton Ledger API
- Pattern async avec CompletableFuture
- Gestion OAuth2 JWT et extraction du party
- Idempotency pour éviter doubles exécutions
- Pool auto-discovery quand poolId = null

---

### [05 - Backend Services](./05_BACKEND_SERVICES.md) (~1500 lignes)

**Contenu**:
- **LedgerReader.java** - Query ACS (Active Contract Set)
  - `pools()`: Récupérer tous les pools actifs
  - `tokens(party)`: Tokens d'un utilisateur
  - `lpTokens(party)`: LP tokens d'un utilisateur
  - Connection à Canton via gRPC Ledger API
- **IdempotencyService.java** - Cache 15 minutes
  - Prévention des doubles swaps
  - Caffeine cache implementation
- **ClearportXInitService.java** - Bootstrap
  - Initialization tokens (USDC, ETH, BTC)
  - Pool creation avec liquidité initiale
- **LedgerHealthService.java** - Health checks
  - PQS sync status
  - Package ID tracking pour version
  - Contract count monitoring

**Vous apprendrez**:
- Comment query le Canton Ledger via gRPC
- ACS (Active Contract Set) vs Historical queries
- Pattern service-layer pour business logic
- Health monitoring et diagnostics
- Package versioning avec packageId

---

### [06 - Sécurité & Infrastructure](./06_SECURITE_INFRASTRUCTURE.md) (~1500 lignes)

**Contenu**:
- **RateLimiterConfig.java** - Token Bucket Rate Limiter
  - Algorithme: AtomicLong + CAS (Compare-And-Swap)
  - Global limit: 0.4 TPS (Canton Network devnet requirement)
  - Per-party limit: 10 RPM
  - HTTP 429 response sur violations
  - Implémentation thread-safe sans dépendances externes
- **WebSecurityConfig.java** - Security layers
  - OAuth2 JWT pour API endpoints (Canton Network Keycloak)
  - Basic Auth pour actuator endpoints
  - CORS configuration pour frontend
- **Metrics & Observability**
  - **SwapMetrics.java**: Counters (executed, failed, prepared)
  - **PoolMetricsRefresher.java**: Gauges (reserves, k-invariant, active pools)
  - Prometheus exposition format
  - Grafana dashboard queries (PromQL)
- **LedgerHealthService.java** - Monitoring
  - Health endpoint avec packageId
  - PQS sync detection
  - Contract count tracking

**Vous apprendrez**:
- Token bucket algorithm pour rate limiting
- Thread-safety avec AtomicLong et CAS
- Canton Network devnet: 0.5 TPS limit (on utilise 0.4 TPS)
- OAuth2 Resource Server avec JWT
- Micrometer + Prometheus + Grafana stack
- PromQL queries pour dashboards

---

### [07 - Configuration & Déploiement](./07_CONFIGURATION_DEPLOYMENT.md) (~1000 lignes)

**Contenu**:
- **Localnet Configuration**
  - `application.yml` - Dev local
  - `docker-compose.yaml` - Canton + PQS + Postgres + Keycloak
  - OAuth2 setup local
- **Devnet Configuration**
  - `application-devnet.yml` - Canton Network devnet
  - Rate limiter enabled (0.4 TPS)
  - Security hardening (production error handling)
  - Placeholders pour endpoints Canton (TBD)
- **Testing**
  - DAML tests: TestAMMMath.daml, TestSwapProposal.daml
  - Integration: smoke-test.sh
  - Devnet: devnet-smoke-test.sh avec rate limiting
- **Canton Network Deployment**
  - IP whitelist process
  - SV (Super Validators) propagation
  - Smoke test complet

**Vous apprendrez**:
- Différence localnet vs devnet configuration
- Spring profiles (default vs devnet)
- Canton Network devnet requirements
- IP whitelist et SV propagation (3-4 jours)
- Testing strategy complète

---

### [08 - Flows End-to-End](./08_FLOWS_END_TO_END.md) (~1000 lignes)

**Contenu** (flows complets avec code):
1. **Atomic Swap Flow Complet**
   - Frontend: POST /api/swap/atomic
   - Backend: SwapController → Rate Limiter → Idempotency → Ledger API
   - DAML: AtomicSwap choice → Protocol fee split → AMM calculation
   - Metrics: Counters increment + Fee tracking
   - Response: Receipt + output amount
2. **Add Liquidity Flow**
   - Validation tokens
   - LP tokens mint calculation
   - Pool reserves update
3. **Remove Liquidity Flow**
   - LP tokens burn
   - Proportional withdrawal
   - Pool reserves update
4. **Bootstrap Flow**
   - Tokens creation
   - Pool initialization
   - Initial liquidity
5. **Multi-Hop Swap Flow**
   - Route discovery
   - Sequential swaps
   - Aggregate fees

**Vous apprendrez**:
- Le parcours complet d'une transaction
- Interaction DAML ↔ Backend ↔ Metrics
- Séquence exacte des opérations
- Gestion des erreurs à chaque étape
- Performance et optimisations

---

## 🎯 Comment Utiliser ce Guide

### Pour Apprendre le Système Complet
Lisez dans l'ordre: **01 → 02 → 03 → 04 → 05 → 06 → 07 → 08**

### Pour un Composant Spécifique
- **Smart Contracts DAML**: 02, 03
- **Backend API**: 04, 05
- **Infrastructure**: 06
- **Déploiement**: 07
- **Comprendre les flows**: 08

### Pour Débugger un Problème
1. Identifiez le composant: DAML, Backend, ou Infrastructure
2. Allez au module correspondant (02-06)
3. Consultez 08 pour le flow end-to-end
4. Vérifiez 07 pour la configuration

---

## 📊 Métriques du Guide

| Module | Lignes | Temps Lecture | Niveau |
|--------|--------|---------------|--------|
| 01 - Architecture | ~1000 | 30 min | Débutant |
| 02 - DAML Core | ~2000 | 2h | Intermédiaire |
| 03 - DAML Swap | ~1500 | 1.5h | Intermédiaire |
| 04 - Controllers | ~2000 | 2h | Intermédiaire |
| 05 - Services | ~1500 | 1.5h | Avancé |
| 06 - Sécurité | ~1500 | 1.5h | Avancé |
| 07 - Config | ~1000 | 1h | Intermédiaire |
| 08 - Flows | ~1000 | 1h | Tous niveaux |
| **TOTAL** | **~12,000** | **~12h** | - |

---

## 🔗 Liens Rapides

### Documentation Complémentaire
- [Guide Original DAML (V1)](../GUIDE_TECHNIQUE_COMPLET.md) - Focus DAML seulement
- [METRICS.md](../METRICS.md) - Grafana dashboards détaillés
- [ATOMIC_SWAP_API.md](../ATOMIC_SWAP_API.md) - API Swagger
- [DEVNET_DEPLOYMENT.md](../../DEVNET_DEPLOYMENT.md) - Canton Network devnet
- [USER-GUIDE.md](../USER-GUIDE.md) - Guide utilisateur final

### Code Source
- DAML Templates: `clearportx/daml/AMM/`
- Backend Controllers: `backend/src/main/java/.../controller/`
- Backend Services: `backend/src/main/java/.../service/`
- Configuration: `backend/src/main/resources/application*.yml`

---

## 🎓 Niveau de Détail

Ce guide utilise le **même style pédagogique** que le GUIDE_TECHNIQUE_COMPLET.md original:

- ✅ **Explication ligne par ligne** du code critique
- ✅ **Diagrammes ASCII** pour visualiser l'architecture
- ✅ **Exemples concrets** avec valeurs réelles
- ✅ **Annotations inline** dans les extraits de code
- ✅ **Pourquoi et comment** pour chaque décision de design
- ✅ **En français** pour faciliter la compréhension

**Exemple de style**:
```java
// Line 155: Extraction du party depuis JWT OAuth2
String party = jwt.getClaimAsString("party");  // ← Canton Network Keycloak claim

// Line 160: Check idempotency (cache 15 minutes)
String key = generateKey(party, req);  // ← party + inputSymbol + amount + timestamp
if (idempotencyService.isDuplicate(key)) {
    return cachedResponse;  // ← Évite double exécution si retry
}
```

---

## 🚀 Commencer

Pour commencer votre apprentissage du système ClearportX:

1. **Nouveau?** → Commencez par [01 - Architecture Globale](./01_ARCHITECTURE_GLOBALE.md)
2. **Connaissez DAML?** → Sautez à [04 - Backend Controllers](./04_BACKEND_CONTROLLERS.md)
3. **Ops/DevOps?** → Allez direct à [06 - Sécurité](./06_SECURITE_INFRASTRUCTURE.md) et [07 - Déploiement](./07_CONFIGURATION_DEPLOYMENT.md)
4. **Besoin de débugger?** → Consultez [08 - Flows End-to-End](./08_FLOWS_END_TO_END.md)

---

## 📝 Contribuer

Ce guide est maintenu à jour avec le code. Si vous trouvez une erreur ou une amélioration:

1. Créez une issue sur GitHub
2. Proposez une pull request avec corrections
3. Contactez l'équipe ClearportX

---

## 📚 Ressources Externes

- [DAML Documentation](https://docs.daml.com/)
- [Canton Network](https://www.canton.network/)
- [Spring Boot](https://spring.io/projects/spring-boot)
- [Prometheus](https://prometheus.io/)
- [Grafana](https://grafana.com/)

---

**Bonne lecture et bon apprentissage! 🎉**

*Guide créé le 20 Octobre 2025*
*Dernière mise à jour: Voir en-tête de chaque module*
