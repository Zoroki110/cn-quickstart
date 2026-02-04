# 🌐 QUESTIONS POUR CANTON NETWORK

**Date:** 23 Octobre 2025  
**Projet:** ClearportX DEX - Premier DEX sur Canton Network  
**Objectif:** Préparer le déploiement sur devnet

---

## 🎯 CONTEXTE

ClearportX est un DEX (Decentralized Exchange) complet avec:
- ✅ Automated Market Maker (AMM) avec pools de liquidité
- ✅ Atomic swaps (one-step execution)
- ✅ Protocol fees (25% ClearportX / 75% LP)
- ✅ OAuth2 authentication via Keycloak
- ✅ Production-ready backend (rate limiting, metrics, idempotency)
- ✅ Smart contracts DAML déployés et testés

**Status actuel:** Fonctionnel en local, prêt pour devnet

---

## 🔐 SECTION 1: OAUTH2 & AUTHENTIFICATION

### Question 1.1: Issuer URL pour Devnet
**Notre config actuelle (local):**
```
Issuer URL: http://keycloak.localhost:8082/realms/AppProvider
```

**Pour devnet, quel issuer devons-nous utiliser?**
- [ ] Canton Network fournit un Keycloak centralisé?
- [ ] Chaque participant héberge son propre Keycloak?
- [ ] Autre solution OAuth2? (Auth0, Okta, etc.)

**URL exacte attendue:** `_________________________________`

---

### Question 1.2: Realm Configuration
**Devons-nous:**
- [ ] Créer notre propre realm dans le Keycloak Canton Network?
- [ ] Utiliser un realm partagé (ex: "CantonNetwork")?
- [ ] Configurer notre propre serveur OAuth2?

**Realm name pour ClearportX:** `_________________________________`

---

### Question 1.3: Client Registration
**Comment enregistrer notre application OAuth2?**
- [ ] Via un portail web Canton Network?
- [ ] Par email/ticket support?
- [ ] Configuration automatique?

**Process:** `_________________________________`

**Credentials attendus:**
- Client ID: `_________________________________`
- Client Secret (si non-public): `_________________________________`

---

### Question 1.4: JWT Token Configuration
**Notre backend valide les JWT avec:**
```java
.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
```

**Questions:**
- Quelle est la structure des JWT tokens Canton Network?
- Le `sub` (subject) contient-t-il le Canton Party ID?
- Y a-t-il des claims spécifiques à valider?

**Documentation JWT Canton:** `_________________________________`

---

## 🪙 SECTION 2: CIP-56 TOKEN STANDARD

### Question 2.1: Obligation CIP-56
**CIP-56 est-il OBLIGATOIRE sur devnet ou RECOMMANDÉ?**
- [ ] Obligatoire - applications doivent l'implémenter
- [ ] Recommandé - mais pas bloquant
- [ ] Optionnel - pour interopérabilité seulement

**Notre situation:**
Nous avons un token custom optimisé pour DEX atomicity (voir [CIP56_COMPLIANCE_ANALYSIS.md](CIP56_COMPLIANCE_ANALYSIS.md))

**Si obligatoire, quel est le timeline de migration?**
- [ ] Avant launch devnet
- [ ] Dans les 30 jours post-launch
- [ ] Pas de deadline stricte

---

### Question 2.2: Package CIP-56 Officiel
**Y a-t-il un package DAML CIP-56 officiel que nous pouvons importer?**
- [ ] Oui - URL/repository: `_________________________________`
- [ ] Non - implémenter selon spec
- [ ] En cours de développement

**Si oui, comment l'intégrer dans notre DAR?**
```daml
-- Comment importer?
import ???
```

---

### Question 2.3: Interopérabilité
**Notre token custom peut-il coexister avec des CIP-56 tokens?**
- [ ] Oui - interopérabilité assurée
- [ ] Non - uniquement un type de token par application
- [ ] Avec wrapper/bridge

**Si bridge nécessaire:**
- Est-il fourni par Canton Network?
- Devons-nous l'implémenter nous-mêmes?

---

### Question 2.4: CantonSwap Code Reference
**CantonSwap utilise CIP-56. Pouvons-nous accéder à leur implémentation?**
- [ ] Code open-source: `_________________________________`
- [ ] Sur demande
- [ ] Propriétaire/non partageable

**Objectif:** Comprendre comment ils gèrent l'atomicity avec multi-step transfers

---

## 🖥️ SECTION 3: VALIDATOR SETUP

### Question 3.1: Guide de Déploiement
**Existe-t-il un guide complet de setup du validator?**
- [ ] Documentation officielle: `_________________________________`
- [ ] Tutoriel vidéo disponible
- [ ] Support 1-on-1 pour nouveaux participants

**Prérequis système:**
- CPU recommandé: `_________________________________`
- RAM recommandé: `_________________________________`
- Storage recommandé: `_________________________________`
- Bande passante: `_________________________________`

---

### Question 3.2: Configuration Réseau
**Quels ports doivent être ouverts dans le firewall?**

| Port | Protocol | Service | Public/Private |
|------|----------|---------|----------------|
| ?    | TCP      | Ledger API | ? |
| ?    | TCP      | Admin API | ? |
| ?    | TCP      | P2P Network | ? |
| ?    | gRPC     | ? | ? |

**Firewall rules exactes:** `_________________________________`

---

### Question 3.3: Process d'Onboarding
**Étapes exactes pour rejoindre le devnet comme validator:**

1. [ ] Step 1: `_________________________________`
2. [ ] Step 2: `_________________________________`
3. [ ] Step 3: `_________________________________`
4. [ ] Step 4: `_________________________________`
5. [ ] Step 5: `_________________________________`

**Durée estimée du process:** `_________________________________`

**Validation/approval nécessaire?**
- [ ] Oui - par qui: `_________________________________`
- [ ] Non - automatique

---

### Question 3.4: Software & Versions
**Quelle version de Canton utiliser?**
- Canton version: `_________________________________`
- DAML SDK version: `_________________________________`
- Autres dépendances: `_________________________________`

**Méthode d'installation:**
- [ ] Docker compose fourni
- [ ] Binaries à télécharger
- [ ] Build from source

**Repository officiel:** `_________________________________`

---

## 🆔 SECTION 4: PARTY ID ALLOCATION

### Question 4.1: Obtention Canton Party ID
**Comment obtenir un Canton Party ID sur devnet?**
- [ ] Généré automatiquement au premier login OAuth2
- [ ] Demande manuelle via portail
- [ ] Assigné par le validator lors de l'onboarding

**Format du Party ID:**
```
Exemple: participant::12201300e204e8a...
Prefix: _________________________________
Suffix: _________________________________
```

---

### Question 4.2: Mapping User → Party ID
**Notre backend doit mapper:**
```
JWT subject (alice) → Canton Party ID (participant::1220...)
```

**Comment est géré ce mapping?**
- [ ] Claim JWT custom (ex: `canton_party_id`)
- [ ] Lookup API Canton Network
- [ ] Table de mapping locale (nous gérons)

**API disponible?** `_________________________________`

---

### Question 4.3: Multi-Party Support
**Un utilisateur peut-il avoir plusieurs Party IDs?**
- [ ] Oui - use case: `_________________________________`
- [ ] Non - strict 1:1 user:party

**Un Party ID peut-il être partagé entre users?**
- [ ] Oui - pour organisations
- [ ] Non - strict 1:1

---

## 🌐 SECTION 5: NETWORK ENDPOINTS

### Question 5.1: Ledger API
**URL du Ledger API pour devnet:**
```
gRPC endpoint: _________________________________
Port: _________________________________
TLS/SSL requis? _________________________________
```

**Authentification:**
- [ ] JWT Bearer token
- [ ] API Key
- [ ] mTLS certificate
- [ ] Autre: `_________________________________`

---

### Question 5.2: JSON API
**URL du JSON API (si disponible):**
```
HTTP endpoint: _________________________________
WebSocket endpoint (si applicable): _________________________________
```

**Rate limits:**
- Requests/second: `_________________________________`
- Burst limit: `_________________________________`

---

### Question 5.3: PQS (Participant Query Store)
**Canton Network fournit-il un PQS centralisé?**
- [ ] Oui - endpoint: `_________________________________`
- [ ] Non - chaque participant héberge le sien
- [ ] Service partagé entre validators

**Si nous hébergeons notre PQS:**
- Base de données recommandée: `_________________________________`
- Configuration requise: `_________________________________`

---

### Question 5.4: Explorers & Monitoring
**Block explorer devnet:**
- URL: `_________________________________`
- Supporte ClearportX custom contracts? _____

**Monitoring/Status page:**
- URL: `_________________________________`
- Métriques disponibles: `_________________________________`

---

## 📦 SECTION 6: DAR DEPLOYMENT

### Question 6.1: Upload DAR
**Comment uploader notre DAR (`clearportx-amm-1.0.1.dar`) sur devnet?**
- [ ] Via CLI: commande exacte `_________________________________`
- [ ] Via portail web
- [ ] Via API REST: endpoint `_________________________________`

**Validation du DAR:**
- Scan de sécurité automatique? _____
- Approval manuel requis? _____
- Délai de validation: `_________________________________`

---

### Question 6.2: Package ID
**Notre package ID actuel (local):**
```
0b50244f981d3023d595164fa94ecba0cb139ed9ae320c9627d8f881d611b0de
```

**Sur devnet, sera-t-il:**
- [ ] Identique (déterministe)
- [ ] Différent (généré par le network)

**Comment le récupérer après upload?**

---

### Question 6.3: Versioning
**Politique de mise à jour des DARs:**
- Peut-on uploader plusieurs versions? _____
- Migration path entre versions? `_________________________________`
- Rollback possible? _____

---

## 🎯 SECTION 7: TIMELINE & SUPPORT

### Question 7.1: Devnet Availability
**Le devnet est-il actuellement opérationnel?**
- [ ] Oui - URL: `_________________________________`
- [ ] En préparation - ETA: `_________________________________`
- [ ] Sur invitation uniquement

**Uptime SLA devnet:** `_________________________________`

---

### Question 7.2: Support Technique
**Canaux de support pour participants devnet:**
- [ ] Slack/Discord: `_________________________________`
- [ ] Email: `_________________________________`
- [ ] Ticketing system: `_________________________________`
- [ ] Office hours: `_________________________________`

**Temps de réponse moyen:** `_________________________________`

---

### Question 7.3: Launch Timeline ClearportX
**Notre planning proposé:**
1. Configuration devnet: 1-2 jours
2. Tests internes: 2-3 jours
3. Public launch: J+5

**Y a-t-il des périodes de maintenance devnet à éviter?**
- [ ] Oui - fenêtres: `_________________________________`
- [ ] Non - disponible 24/7

---

### Question 7.4: Communication & Marketing
**Peut-on communiquer publiquement sur notre déploiement?**
- [ ] Oui - aucune restriction
- [ ] Avec approval Canton Network
- [ ] Devnet confidentiel

**Souhaitez-vous co-annoncer le premier DEX sur Canton?**
- [ ] Oui - contact marketing: `_________________________________`
- [ ] Non

---

## ✅ RÉSUMÉ DES BESOINS CRITIQUES

**Pour déployer ClearportX sur devnet, nous avons BESOIN de:**

1. ✅ **Issuer URL OAuth2** exact
2. ✅ **Guide validator setup** complet
3. ✅ **Ledger API endpoint** + credentials
4. ✅ **Process Party ID allocation**
5. ✅ **Clarification CIP-56** (obligatoire ou non)

**Avec ces infos, nous pouvons lancer sous 48h!**

---

## 📞 CONTACT

**Équipe ClearportX:**
- Nom: `_________________________________`
- Email: `_________________________________`
- Telegram/Discord: `_________________________________`

**Disponibilité pour call de setup:**
- [ ] Immédiatement
- [ ] Cette semaine
- [ ] Semaine prochaine

**Timezone:** `_________________________________`

