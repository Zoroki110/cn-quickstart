# Documentation ClearportX DEX

**Version**: 2.0.0  
**Date**: 21 Octobre 2025  
**Statut**: ✅ Documentation complète et organisée

---

## 📚 Structure de la Documentation

### 1. Guide Technique Modulaire (Recommandé)

**Location**: `GUIDE_TECHNIQUE/`  
**Total**: 8,799 lignes de documentation technique détaillée  
**Format**: 9 modules indépendants en français

```
docs/GUIDE_TECHNIQUE/
├── README.md                          # Vue d'ensemble du guide
├── 00_INDEX.md                        # Navigation hub
├── 01_ARCHITECTURE_GLOBALE.md         # Architecture système
├── 02_DAML_CORE_TEMPLATES.md          # Smart contracts DAML
├── 03_DAML_SWAP_SYSTEM.md             # Système de swap
├── 04_BACKEND_CONTROLLERS.md          # REST API Spring Boot
├── 05_BACKEND_SERVICES.md             # Services backend
├── 06_SECURITE_INFRASTRUCTURE.md      # Sécurité et métriques
├── 07_CONFIGURATION_DEPLOYMENT.md     # Déploiement et config
└── 08_FLOWS_END_TO_END.md             # Scénarios complets
```

**Avantages**:
- ✅ Navigation facile (modules indépendants)
- ✅ Lecture ciblée (choisir les modules pertinents)
- ✅ Mise à jour simplifiée (modifier un module sans affecter les autres)
- ✅ Style pédagogique (diagrammes ASCII, code annoté)

**Quick Start**:
```bash
cd docs/GUIDE_TECHNIQUE
cat README.md                    # Vue d'ensemble
cat 00_INDEX.md                  # Table des matières
cat 01_ARCHITECTURE_GLOBALE.md   # Commencer par l'architecture
```

---

### 2. Guide Technique Original (Référence)

**Location**: `../GUIDE_TECHNIQUE_COMPLET.md` (racine clearportx/)  
**Total**: 3,665 lignes  
**Format**: Fichier unique (DAML uniquement)

**Contenu**:
- Templates DAML détaillés (Pool, Token, LPToken)
- Flows DAML ligne par ligne
- Formules AMM

**Note**: Ce guide original couvre **uniquement DAML**. Pour la documentation complète incluant le backend Java, l'infrastructure et le déploiement, utiliser le guide modulaire.

---

### 3. Documentation Système

**Location**: Racine du projet

- **README.md** - Vue d'ensemble du projet ClearportX
- **ADMIN-QUICK-REFERENCE.md** - Guide admin rapide
- **ADMIN-SYSTEM-GUIDE.md** - Guide système administrateur
- **DEPLOYMENT-CANTON-3.3.md** - Déploiement Canton 3.3
- **LOCAL-TESTNET-SETUP.md** - Setup testnet local
- **METRICS.md** - Documentation métriques Prometheus/Grafana
- **PROTOCOL-FEES-GUIDE.md** - Guide des protocol fees
- **ATOMIC_SWAP_API.md** - API documentation atomic swap

---

## 🎯 Recommandations par Profil

### Développeur DAML
1. `GUIDE_TECHNIQUE/02_DAML_CORE_TEMPLATES.md` (15 min)
2. `GUIDE_TECHNIQUE/03_DAML_SWAP_SYSTEM.md` (30 min)
3. `GUIDE_TECHNIQUE_COMPLET.md` (référence détaillée)

### Développeur Backend Java
1. `GUIDE_TECHNIQUE/01_ARCHITECTURE_GLOBALE.md` (15 min)
2. `GUIDE_TECHNIQUE/04_BACKEND_CONTROLLERS.md` (35 min)
3. `GUIDE_TECHNIQUE/05_BACKEND_SERVICES.md` (30 min)
4. `GUIDE_TECHNIQUE/06_SECURITE_INFRASTRUCTURE.md` (20 min)

### DevOps / SRE
1. `GUIDE_TECHNIQUE/07_CONFIGURATION_DEPLOYMENT.md` (20 min)
2. `DEPLOYMENT-CANTON-3.3.md` (15 min)
3. `METRICS.md` (10 min)

### Débutant (découverte)
1. `README.md` (racine projet)
2. `GUIDE_TECHNIQUE/00_INDEX.md` (navigation)
3. `GUIDE_TECHNIQUE/01_ARCHITECTURE_GLOBALE.md` (vue d'ensemble)

### Expert (référence complète)
Lire tous les modules dans l'ordre (00 → 08) : **3h15**

---

## 📊 Statistiques Documentation

```
Guide Technique Modulaire:
├── 9 modules
├── 8,799 lignes
├── 50+ diagrammes ASCII
├── 200+ exemples de code
├── 20+ formules mathématiques
└── 15+ flows end-to-end

Guide Technique Original:
├── 1 fichier
├── 3,665 lignes
├── DAML uniquement
└── Style pédagogique détaillé

Documentation Système:
├── 8 guides spécialisés
├── Deployment, Admin, Metrics
└── Guides opérationnels
```

---

## 🚀 Quick Links

### Guides Essentiels
- [Guide Technique Modulaire](GUIDE_TECHNIQUE/README.md) - Documentation complète (DAML + Backend + Infra)
- [Guide Technique Original](../GUIDE_TECHNIQUE_COMPLET.md) - Référence DAML détaillée
- [README Principal](../README.md) - Vue d'ensemble projet

### Configuration & Déploiement
- [Deployment Canton 3.3](DEPLOYMENT-CANTON-3.3.md) - Déploiement production
- [Local Testnet Setup](LOCAL-TESTNET-SETUP.md) - Environnement dev local
- [Configuration Guide](GUIDE_TECHNIQUE/07_CONFIGURATION_DEPLOYMENT.md) - Localnet vs Devnet

### API & Intégration
- [Atomic Swap API](ATOMIC_SWAP_API.md) - Documentation API swap
- [Backend Controllers](GUIDE_TECHNIQUE/04_BACKEND_CONTROLLERS.md) - REST endpoints
- [Backend Services](GUIDE_TECHNIQUE/05_BACKEND_SERVICES.md) - Services core

### Monitoring & Métriques
- [Metrics Documentation](METRICS.md) - Prometheus/Grafana
- [Security & Infrastructure](GUIDE_TECHNIQUE/06_SECURITE_INFRASTRUCTURE.md) - Rate limiting, validation

---

## 🔄 Historique des Versions

### Version 2.0.0 (21 Octobre 2025)
- ✅ Guide technique modulaire créé (9 modules, 8799 lignes)
- ✅ Documentation backend Java complète
- ✅ Guides infrastructure et déploiement
- ✅ Flows end-to-end détaillés
- ✅ Nettoyage documentation obsolète (47 fichiers archivés)

### Version 1.0.0 (11 Octobre 2025)
- ✅ Guide technique DAML complet (3665 lignes)
- ✅ Documentation système de base

---

## 📝 Notes

- **Langue**: Français (toute la documentation technique)
- **Format**: Markdown avec diagrammes ASCII
- **Style**: Pédagogique (explications ligne par ligne)
- **Maintenance**: Documentation vivante, mise à jour continue

---

**Pour toute question**: Consulter d'abord le [Guide Technique Modulaire](GUIDE_TECHNIQUE/README.md)

