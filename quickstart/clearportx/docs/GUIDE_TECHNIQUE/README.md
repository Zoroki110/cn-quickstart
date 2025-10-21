# Guide Technique ClearportX DEX - Documentation Complète

**Version**: 2.0.0 - Guide Modulaire  
**Date**: 21 Octobre 2025  
**Lignes totales**: 8,799 lignes de documentation technique  
**Temps de lecture estimé**: 3h15 (lecture complète)

---

## 📚 Vue d'Ensemble

Ce guide technique couvre **l'intégralité du système ClearportX DEX**, du smart contract DAML jusqu'au déploiement sur Canton Network, dans le même style pédagogique détaillé que le guide original.

**Style de documentation** :
- ✅ Français
- ✅ Explications ligne par ligne avec flèches (←)
- ✅ Diagrammes ASCII
- ✅ Formules mathématiques AMM
- ✅ Exemples de code complets
- ✅ Flows end-to-end avec code

---

## 📖 Modules du Guide

### Module 00 : INDEX (348 lignes)
**Navigation hub pour tous les modules**
- Table des matières complète
- Liens vers tous les modules
- Recommandations de lecture par profil utilisateur

📄 [00_INDEX.md](./00_INDEX.md)

---

### Module 01 : ARCHITECTURE GLOBALE (830 lignes)
**Vue d'ensemble du système complet**
- Stack technologique (DAML 3.3, Spring Boot, Canton, PQS, Prometheus)
- Architecture en couches (Frontend → Backend → Canton → PQS)
- Flow de données complet
- Choix de design critiques

📄 [01_ARCHITECTURE_GLOBALE.md](./01_ARCHITECTURE_GLOBALE.md)

---

### Module 02 : DAML CORE TEMPLATES (713 lignes)
**Smart contracts DAML détaillés**
- **Pool.daml** (446 lignes analysées)
  - AddLiquidity : formules LP tokens
  - RemoveLiquidity : burn proportionnel
  - Archive-and-recreate pattern
- **Token.daml** : trust model (issuer = signatory)
- **LPToken.daml** : LP token mechanics

📄 [02_DAML_CORE_TEMPLATES.md](./02_DAML_CORE_TEMPLATES.md)

---

### Module 03 : DAML SWAP SYSTEM (1,499 lignes)
**Système de swap complet**
- **SwapRequest.daml** : Pattern 2-step (PrepareSwap + ExecuteSwap)
- **AtomicSwap.daml** : Swap atomique 1-step (recommandé production)
- **Receipt.daml** : Preuve d'exécution
- **PoolAnnouncement.daml** : Découverte sans clés
- Comparaison 2-step vs 1-step

📄 [03_DAML_SWAP_SYSTEM.md](./03_DAML_SWAP_SYSTEM.md)

---

### Module 04 : BACKEND CONTROLLERS (1,562 lignes)
**Endpoints REST API Spring Boot**
- **SwapController** : /api/swap/atomic, /prepare, /execute
- **LiquidityController** : /api/liquidity/add
- **LedgerHealthController** : /api/health/ledger
- Sécurité (OAuth2 JWT, PartyGuard)
- Gestion d'erreurs et retry logic

📄 [04_BACKEND_CONTROLLERS.md](./04_BACKEND_CONTROLLERS.md)

---

### Module 05 : BACKEND SERVICES (1,345 lignes)
**Services backend core**
- **LedgerApi** : Wrapper Canton Ledger API (gRPC)
  - create(), exercise(), createAndGetCid()
  - Multi-party authorization
  - Transaction tree parsing
- **IdempotencyService** : Duplicate prevention (15 min cache)
- **LedgerReader** : Authoritative contract reads
- **PqsSyncUtil** : Test synchronization

📄 [05_BACKEND_SERVICES.md](./05_BACKEND_SERVICES.md)

---

### Module 06 : SÉCURITÉ & INFRASTRUCTURE (761 lignes)
**Sécurité et métriques**
- **Rate Limiting** : 0.4 TPS (devnet compliance)
  - Token bucket algorithm (AtomicLong + CAS)
- **SwapValidator** : Validation centralisée
- **Métriques Prometheus** : Counters, histograms, gauges
- **Configuration Spring Boot** : application.yml

📄 [06_SECURITE_INFRASTRUCTURE.md](./06_SECURITE_INFRASTRUCTURE.md)

---

### Module 07 : CONFIGURATION & DEPLOYMENT (872 lignes)
**Environnements et déploiement**
- **Localnet** : Development local (Canton standalone)
- **Devnet** : Canton Network testing (0.4 TPS, OAuth2 JWT)
- **Docker Compose** : Architecture 7 services
- **Makefile** : Commands (local-up, test, devnet-init)

📄 [07_CONFIGURATION_DEPLOYMENT.md](./07_CONFIGURATION_DEPLOYMENT.md)

---

### Module 08 : FLOWS END-TO-END (869 lignes)
**Scénarios complets avec code**
- **Atomic Swap ETH → USDC** : Frontend → Backend → DAML → État final
- **Add Liquidity** : Calcul proportionnel, LP minting
- **Remove Liquidity** : Burn LP tokens, withdrawal
- **Scenarios d'erreur** : Slippage, CONTRACT_NOT_FOUND, rate limiting
- **Performance** : Latency breakdown, optimisations

📄 [08_FLOWS_END_TO_END.md](./08_FLOWS_END_TO_END.md)

---

## 🎯 Recommandations de Lecture

### Pour les développeurs DAML
1. Module 01 (Architecture) - 15 min
2. Module 02 (DAML Core) - 15 min
3. Module 03 (DAML Swap) - 30 min
**Total** : 1h

### Pour les développeurs Backend
1. Module 01 (Architecture) - 15 min
2. Module 04 (Controllers) - 35 min
3. Module 05 (Services) - 30 min
4. Module 06 (Sécurité) - 20 min
**Total** : 1h40

### Pour les DevOps / SRE
1. Module 01 (Architecture) - 15 min
2. Module 06 (Sécurité) - 20 min
3. Module 07 (Deployment) - 20 min
**Total** : 55 min

### Pour la lecture complète
Tous les modules dans l'ordre : **3h15**

---

## 📊 Statistiques

```
Total documentation créée : 8,799 lignes
├── Module 00 (INDEX)                : 348 lignes
├── Module 01 (Architecture)         : 830 lignes
├── Module 02 (DAML Core)            : 713 lignes
├── Module 03 (DAML Swap)            : 1,499 lignes
├── Module 04 (Backend Controllers)  : 1,562 lignes
├── Module 05 (Backend Services)     : 1,345 lignes
├── Module 06 (Sécurité)             : 761 lignes
├── Module 07 (Deployment)           : 872 lignes
└── Module 08 (Flows End-to-End)     : 869 lignes

Diagrammes ASCII               : 50+
Exemples de code               : 200+
Formules mathématiques         : 20+
Flows documentés en détail     : 15+
```

---

## 🚀 Quick Start

```bash
# 1. Lire le guide (navigation)
cd clearportx/docs/GUIDE_TECHNIQUE
cat 00_INDEX.md

# 2. Commencer par l'architecture
cat 01_ARCHITECTURE_GLOBALE.md

# 3. Plonger dans DAML
cat 02_DAML_CORE_TEMPLATES.md
cat 03_DAML_SWAP_SYSTEM.md

# 4. Comprendre le backend
cat 04_BACKEND_CONTROLLERS.md
cat 05_BACKEND_SERVICES.md

# 5. Voir la sécurité et le déploiement
cat 06_SECURITE_INFRASTRUCTURE.md
cat 07_CONFIGURATION_DEPLOYMENT.md

# 6. Étudier les flows complets
cat 08_FLOWS_END_TO_END.md
```

---

## 📝 Notes

- **Style** : Même format que GUIDE_TECHNIQUE_COMPLET.md original
- **Langue** : Français
- **Précision** : Explications ligne par ligne avec code source
- **Pédagogie** : ASCII diagrams, formules mathématiques, exemples concrets
- **Complétude** : Couvre DAML + Backend + Infrastructure + Déploiement

---

**Bonne lecture !** 🎓

