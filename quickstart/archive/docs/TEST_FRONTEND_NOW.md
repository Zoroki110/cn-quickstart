# Test Frontend ClearportX - Instructions Rapides

**Status:** Frontend démarré sur http://localhost:3001
**Backend:** Running sur http://localhost:8080
**Date:** 2025-10-21

---

## État Actuel

✅ **Frontend:** Compilé avec succès, running sur port 3001
✅ **Backend:** Running, health OK
⚠️  **OAuth:** Pas encore activé (endpoints publics en cours de configuration)
⚠️  **Tokens:** Utilise actuellement les mocks (pas les vraies données backend)

---

## Test Rapide de l'UI

### 1. Ouvrir le Frontend

```bash
# Dans votre navigateur
http://localhost:3001
```

### 2. Navigation

Vous devriez voir:
- **Header:** Logo ClearportX + Navigation (Swap, Pools, Liquidity, History)
- **Bouton:** "Connect Wallet" (ne fonctionne pas encore - OAuth en cours)
- **Page Swap:** Interface de swap avec sélection de tokens

### 3. Tester l'Interface Swap

**Valeurs Attendues (MOCKS):**
- USDC: **10000.50**
- ETH: **25.75**
- BTC: **2.5**
- USDT: **5000.0**

**Actions:**
1. Cliquez sur "From" → Sélectionnez **ETH**
2. Cliquez sur "To" → Sélectionnez **USDC**
3. Entrez montant: **0.01**
4. Vous devriez voir un quote calculé (~30 USDC si le prix ETH est ~3000)

**Problème actuel:**
- Les balances affichées sont les **mocks** (10000.50 USDC)
- Pas les vraies balances du backend (devrait être différent)
- Le swap lui-même appelle le **vrai backend** mais échouera car besoin d'authentification

---

## Prochaines Étapes pour Tests Complets

### Option 1: Désactiver OAuth Temporairement

Modifier le backend pour permettre les swaps sans auth (TESTING ONLY):

```java
// Dans OAuth2Config.java, ligne 77-80:
.requestMatchers("/api/**").permitAll()  // TEMPORARY: All API endpoints public
```

Puis:
- Redémarrer backend
- Tester swap complet depuis l'UI
- Vérifier que les balances changent

### Option 2: Activer OAuth Complet

1. **Vérifier Keycloak:**
```bash
curl http://localhost:8082/realms/AppProvider/.well-known/openid-configuration
```

2. **Si Keycloak ne tourne pas:**
```bash
cd /root/cn-quickstart/quickstart
docker compose up keycloak -d
```

3. **Tester Login depuis Frontend:**
- Cliquer "Connect Wallet"
- Username: `alice`
- Password: `alicepass`
- Vérifier JWT stocké dans localStorage

4. **Après Login:**
- Les tokens devraient charger du backend
- Le swap devrait fonctionner avec authentification

### Option 3: Endpoint Public Tokens (En cours)

Nous avons créé `/api/tokens/{party}` mais il est bloqué par OAuth2Config.

**Fix nécessaire:**
```java
// OAuth2Config.java ligne 78:
.requestMatchers("/api/tokens/**").permitAll()  // Note: /** pas juste /*
```

---

## Vérification Backend

### 1. Health Check
```bash
curl http://localhost:8080/api/health/ledger | jq
```

**Attendu:**
```json
{
  "status": "OK",
  "poolsActive": 33,  // Note: Bug connu, devrait être 3
  "synced": true
}
```

### 2. Pools (devrait être public)
```bash
curl http://localhost:8080/api/pools
```

**Problème actuel:** Retourne `Unauthorized`
**Cause:** OAuth2Config `.anyRequest().authenticated()` prend le dessus sur `@PreAuthorize("permitAll()")`

### 3. Tokens Alice (devrait être public)
```bash
curl http://localhost:8080/api/tokens/alice
```

**Problème actuel:** Retourne `Unauthorized`
**Cause:** Même raison que ci-dessus

---

## Solutions Immédiates

### Solution A: Tests avec Mocks (RAPIDE - 5 min)

**Avantage:** Tester l'UI immédiatement sans backend
**Action:**
1. Garder le frontend tel quel (utilise mockCantonApi)
2. Tester navigation, sélection tokens, calcul de quote
3. Le bouton "Swap Tokens" ne fonctionnera pas (besoin backend)

**Ce que vous pouvez tester:**
- ✅ UI charge correctement
- ✅ Navigation fonctionne
- ✅ Sélection de tokens fonctionne
- ✅ Calcul de quote fonctionne (client-side AMM math)
- ❌ Swap réel ne fonctionne pas (besoin authentification)

### Solution B: Désactiver OAuth Totalement (RAPIDE - 15 min)

**Fichier:** `OAuth2Config.java` ligne 77-81

**Avant:**
```java
.requestMatchers("/api/pools").permitAll()
.requestMatchers("/api/tokens/*").permitAll()
.requestMatchers("/api/tokens").authenticated()
.requestMatchers("/admin/**").hasRole("ADMIN")
.anyRequest().authenticated()
```

**Après:**
```java
.anyRequest().permitAll()  // DANGER: ALL endpoints public!
```

**Puis:**
1. Recompiler: `./gradlew :backend:compileJava`
2. Redémarrer backend
3. Tester `/api/tokens/alice` → devrait retourner JSON
4. Tester swap complet depuis UI

**⚠️  DANGER:** Tous les endpoints deviennent publics! Ne PAS faire en production!

### Solution C: Activer OAuth Correctement (LONG - 1-2h)

1. Démarrer Keycloak
2. Configurer users (alice, bob, charlie)
3. Tester login depuis frontend
4. Vérifier JWT valide
5. Tester swaps authentifiés

---

## Données Attendues Backend vs Mocks

### Mocks (actuellement affichés):
```typescript
USDC: 10000.50
ETH: 25.75
BTC: 2.5
USDT: 5000.0
```

### Backend Alice (vraies données Canton - à vérifier):
Devrait être différent selon les contrats Token réels sur le ledger.

Pour voir les vraies données:
```bash
# Quand /api/tokens/alice sera accessible:
curl http://localhost:8080/api/tokens/alice | jq

# Format attendu:
[
  {
    "symbol": "USDC",
    "quantity": "10000.5000000000",  # 10 décimales
    "contractId": "00abc123..."
  },
  ...
]
```

---

## Commandes Utiles

### Frontend
```bash
# Logs en direct
tail -f /tmp/frontend.log

# Tuer et redémarrer
lsof -ti:3001 | xargs kill -9
cd /root/canton-website/app && npm start

# Voir la page
curl -s http://localhost:3001 | head -20
```

### Backend
```bash
# Logs en direct
tail -f /tmp/backend-new.log

# Health check
curl -s http://localhost:8080/api/health/ledger | jq -r '.status'

# Tester endpoint (quand public)
curl -s http://localhost:8080/api/pools | jq 'length'
```

---

## Résumé de l'État

| Composant | État | URL | Notes |
|-----------|------|-----|-------|
| Frontend | ✅ Running | http://localhost:3001 | React dev server, compiled successfully |
| Backend | ✅ Running | http://localhost:8080 | Spring Boot, health OK |
| Keycloak | ❓ Unknown | http://localhost:8082 | Nécessaire pour OAuth |
| Canton Ledger | ✅ Running | - | Synced, 632 contracts |
| Prometheus | ✅ Running | http://localhost:14011 | Metrics scraping |
| Grafana | ✅ Running | http://localhost:14012 | Dashboard ClearportX |

| Feature | État | Blocage |
|---------|------|---------|
| UI Navigation | ✅ Works | - |
| Token Selection | ✅ Works | - |
| Quote Calculation | ✅ Works | Client-side math |
| Display Tokens | ⚠️  Mocks | Backend endpoint 401 Unauthorized |
| Display Pools | ⚠️  Mocks | Backend endpoint 401 Unauthorized |
| Execute Swap | ❌ Blocked | Needs authentication (401) |
| Add Liquidity | ❌ Blocked | Needs authentication (401) |
| OAuth Login | ❌ Not working | Keycloak status unknown |

---

## Recommandation Immédiate

**Pour tester l'UI maintenant:**
1. Ouvrir http://localhost:3001 dans le navigateur
2. Naviguer entre les pages (Swap, Pools, Liquidity)
3. Tester sélection de tokens et calcul de quote
4. Accepter que les balances affichées sont des mocks

**Pour tester le backend complet:**
Choisir **Solution B** (désactiver OAuth temporairement):
- Modifier `.anyRequest().permitAll()` dans OAuth2Config.java
- Redémarrer backend
- Tester swaps complets depuis l'UI
- **Revenir à `.anyRequest().authenticated()` après les tests!**

---

**Créé:** 2025-10-21 21:38 UTC
**Frontend:** http://localhost:3001 ✅
**Backend:** http://localhost:8080 ✅
