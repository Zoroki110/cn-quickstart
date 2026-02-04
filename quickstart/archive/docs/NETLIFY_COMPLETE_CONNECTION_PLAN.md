# 🎯 PLAN COMPLET: CONNEXION NETLIFY ↔ BACKEND VIA NGROK

## 📊 ÉTAT ACTUEL (26 Octobre 2025, 20h30)

### ✅ Ce qui fonctionne
- ✅ Backend Spring Boot actif sur port 8080
- ✅ Canton Network DevNet connecté (5001)
- ✅ Ngrok tunnel actif: `https://nonexplicable-lacily-leesa.ngrok-free.dev`
- ✅ CORS configuré correctement (OPTIONS → 200 OK)
- ✅ Endpoint `/api/pools` retourne 5 pools ETH-USDC
- ✅ Frontend v1.0.4 déployé sur Netlify
- ✅ Build TypeScript réussi

### ❌ Ce qui ne fonctionne pas
- ❌ Authentication vers `localhost:8082` → `ERR_CONNECTION_REFUSED`
- ❌ Pools non visibles sur l'interface (bloqué par auth)
- ❌ Login modal ne fonctionne pas

### 🔍 Diagnostic
Le frontend essaie de se connecter à Keycloak sur `http://localhost:8082` qui n'est pas accessible depuis Netlify. Le fichier `.env.production` ne contient pas de configuration Keycloak, donc le code utilise la valeur par défaut hardcodée.

---

## 🎯 PLAN D'ACTION EN 4 ÉTAPES

---

## ÉTAPE 1: Désactiver l'Authentication Frontend (30 min)

### Objectif
Permettre au frontend de fonctionner sans Keycloak pour la démo.

### Actions

#### 1.1 Modifier le service d'authentification

**Fichier:** `/root/canton-website/app/src/services/auth.ts`

**Ajouter en haut du fichier:**
```typescript
const DISABLE_AUTH = process.env.REACT_APP_DISABLE_AUTH === 'true';
```

**Modifier la méthode `login()` (ligne 20-45):**
```typescript
async login(username: string, password: string) {
  if (DISABLE_AUTH) {
    console.log('🔓 Auth disabled - using mock authentication');
    const mockTokens = {
      access_token: 'mock-jwt-token-for-demo',
      refresh_token: 'mock-refresh-token',
      expires_in: 3600
    };

    // Map username to app-provider party
    const partyMap: Record<string, string> = {
      'alice': 'app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388',
      'bob': 'app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388'
    };

    const party = partyMap[username] || username;
    this.persist(mockTokens, party);

    return { token: mockTokens.access_token, party };
  }

  // Existing Keycloak logic...
  const params = new URLSearchParams();
  // ... rest of existing code
}
```

**Modifier la méthode `isAuthenticated()` (ligne 80-82):**
```typescript
isAuthenticated(): boolean {
  if (DISABLE_AUTH) {
    console.log('🔓 Auth disabled - always authenticated');
    return true;
  }
  return !!this.getToken();
}
```

**Modifier la méthode `getParty()` (ligne 84-86):**
```typescript
getParty(): string | null {
  if (DISABLE_AUTH) {
    return 'app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388';
  }
  return localStorage.getItem('party');
}
```

#### 1.2 Modifier backendApi pour supporter l'auth désactivée

**Fichier:** `/root/canton-website/app/src/services/backendApi.ts`

**Ajouter en haut du fichier (ligne 4):**
```typescript
const DISABLE_AUTH = process.env.REACT_APP_DISABLE_AUTH === 'true';
```

**Modifier l'intercepteur JWT (lignes 86-102):**
```typescript
// JWT interceptor for protected endpoints
api.interceptors.request.use(
  (config) => {
    const token = authService.getToken();
    const isPublicEndpoint =
      config.url?.includes('/api/pools') ||
      config.url?.includes('/api/health') ||
      config.url?.includes('/api/tokens/');

    // Add JWT header only if authenticated OR auth disabled
    if (!isPublicEndpoint) {
      if (DISABLE_AUTH) {
        // Use mock token when auth is disabled
        config.headers.Authorization = 'Bearer mock-jwt-token-for-demo';
      } else if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      }
    }

    return config;
  },
  (error) => Promise.reject(error)
);
```

#### 1.3 Commiter les changements

```bash
cd /root/canton-website
git add app/src/services/auth.ts app/src/services/backendApi.ts
git commit -m "feat: Add REACT_APP_DISABLE_AUTH support for Netlify deployment

- Add auth bypass mode when REACT_APP_DISABLE_AUTH=true
- Mock JWT tokens for demo purposes
- Always authenticated in bypass mode
- Maps demo users to app-provider party

This allows frontend to work on Netlify without Keycloak access."

git push origin main
```

---

## ÉTAPE 2: Mettre à Jour les Variables d'Environnement (10 min)

### 2.1 Fichier `.env.production`

**Fichier:** `/root/canton-website/app/.env.production`

**Remplacer tout le contenu par:**
```bash
# ========================================
# CLEARPORTX PRODUCTION CONFIGURATION
# Netlify Deployment via ngrok Tunnel
# Updated: 2025-10-26
# ========================================

# Backend Configuration
REACT_APP_BACKEND_API_URL=https://nonexplicable-lacily-leesa.ngrok-free.dev
REACT_APP_CANTON_API_URL=https://nonexplicable-lacily-leesa.ngrok-free.dev

# Authentication Configuration
REACT_APP_DISABLE_AUTH=true

# Feature Flags
REACT_APP_USE_MOCK=false
REACT_APP_USE_MOCK_DATA=false

# Build Configuration
GENERATE_SOURCEMAP=false
CI=false
NODE_ENV=production

# ========================================
# NOTES:
# - ngrok URL changes on restart!
# - When ngrok restarts, update this file
# - Then redeploy to Netlify
# ========================================
```

**Commiter:**
```bash
git add app/.env.production
git commit -m "config: Update production env for Netlify with auth disabled"
git push origin main
```

### 2.2 Variables Netlify Dashboard

1. Allez sur [Netlify Dashboard](https://app.netlify.com)
2. Sélectionnez votre site **app.clearportx.com**
3. **Site settings** → **Environment variables**
4. **Configurer ces variables:**

```
REACT_APP_BACKEND_API_URL = https://nonexplicable-lacily-leesa.ngrok-free.dev
REACT_APP_CANTON_API_URL = https://nonexplicable-lacily-leesa.ngrok-free.dev
REACT_APP_DISABLE_AUTH = true
REACT_APP_USE_MOCK = false
REACT_APP_USE_MOCK_DATA = false
GENERATE_SOURCEMAP = false
CI = false
```

5. **Supprimer ces variables** si elles existent:
   - ❌ Toute variable KEYCLOAK
   - ❌ REACT_APP_KEYCLOAK_URL
   - ❌ REACT_APP_KEYCLOAK_REALM

---

## ÉTAPE 3: Rebuild et Redéployer sur Netlify (15 min)

### 3.1 Build local (optionnel - pour vérifier)

```bash
cd /root/canton-website/app

# Nettoyer
rm -rf build/ node_modules/.cache

# Installer les dépendances si nécessaire
npm install

# Build avec la config de production
npm run build

# Vérifier que le build contient les bonnes valeurs
grep -r "DISABLE_AUTH" build/static/js/*.js
# Devrait montrer: REACT_APP_DISABLE_AUTH:"true"
```

### 3.2 Déployer sur Netlify

#### Option A: Auto-deploy via Git (Recommandé)

Netlify détecte automatiquement les commits sur `main`:

```bash
# Vérifier que tout est poussé
cd /root/canton-website
git status
git log --oneline -3

# Netlify va détecter le nouveau commit et déclencher un build
```

**Surveiller le build:**
1. Allez sur Netlify Dashboard → Deploys
2. Vous devriez voir un nouveau deploy en cours
3. Attendez que le statut passe à **Published** (~2-3 minutes)

#### Option B: Deploy manuel

```bash
cd /root/canton-website/app

# Build
npm run build

# Deploy avec Netlify CLI
npx netlify deploy --prod --dir=build
```

### 3.3 Vérifier le déploiement

**Dans Netlify Dashboard:**
- ✅ Deploy status: **Published**
- ✅ Deploy log: "Build script success"
- ✅ Pas d'erreurs TypeScript
- ✅ Version: 1.0.5 (ou supérieure)

---

## ÉTAPE 4: Vérification Complète de la Connexion (15 min)

### 4.1 Vérifier le Backend

```bash
# 1. Backend est actif
curl http://localhost:8080/api/health
# → {"status":"UP"}

# 2. Pools sont visibles
curl http://localhost:8080/api/pools
# → [{"poolId":"ETH-USDC-01",...}]

# 3. Ngrok fonctionne
curl https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools
# → [{"poolId":"ETH-USDC-01",...}]

# 4. CORS fonctionne
curl -I -X OPTIONS https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools \
  -H "Origin: https://app.clearportx.com" \
  -H "Access-Control-Request-Method: GET"
# → HTTP/2 200
# → Access-Control-Allow-Origin: https://app.clearportx.com
```

### 4.2 Vérifier le Frontend

**Ouvrir:** https://app.clearportx.com

**1. Console Browser (F12 → Console):**

✅ **Messages attendus:**
```
ClearportX Build Info: {...}
Backend URL configured: https://nonexplicable-lacily-leesa.ngrok-free.dev
Build version: 1.0.5
🔓 Auth disabled - using mock authentication
🔓 Auth disabled - always authenticated
Getting tokens for app-provider::1220...
Aggregated 0 tokens into 0 unique tokens
```

❌ **Messages à NE PLUS VOIR:**
```
❌ localhost:8082/realms/AppProvider... ERR_CONNECTION_REFUSED
❌ Login failed
❌ Failed to load resource: 500
```

**2. Network Tab (F12 → Network):**

✅ **Requêtes attendues:**
```
GET https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools
  Status: 200 OK
  Response: [{"poolId":"ETH-USDC-01",...}]
  Headers: Access-Control-Allow-Origin: https://app.clearportx.com

GET https://nonexplicable-lacily-leesa.ngrok-free.dev/api/tokens/app-provider::1220...
  Status: 200 OK
  Response: []
```

❌ **Requêtes à NE PLUS VOIR:**
```
❌ POST http://localhost:8082/realms/AppProvider/protocol/openid-connect/token
❌ POST https://nonexplicable-lacily-leesa.ngrok-free.dev/v2/query
```

**3. Interface Utilisateur:**

✅ **Éléments attendus:**
- Header avec "ClearportX DEX"
- Page "Swap" chargée
- **Pools visibles** dans la liste (ETH-USDC-01)
- Réserves affichées: "100 ETH / 200,000 USDC"
- Pas de message d'erreur de connexion

❌ **Si les pools ne s'affichent pas:**
- Vider le cache: Ctrl+Shift+R (Windows) ou Cmd+Shift+R (Mac)
- Vérifier la console pour des erreurs
- Vérifier que le build Netlify est terminé

### 4.3 Tester le Flow Complet

**Scénario 1: Visualisation des Pools**
1. Ouvrir https://app.clearportx.com/swap
2. ✅ Voir 5 pools ETH-USDC affichés
3. ✅ Réserves correctes: 100 ETH / 200,000 USDC
4. ✅ Fee rate: 0.3%

**Scénario 2: Connexion (Mock)**
1. Cliquer sur "Connect Wallet"
2. Entrer: `alice` / `alice123`
3. ✅ Connexion réussie sans erreur
4. ✅ Header montre: "alice" ou "app-provider::1220..."
5. ✅ Console montre: "🔓 Auth disabled - using mock authentication"

**Scénario 3: API Calls**
1. Ouvrir Network tab
2. Rafraîchir la page
3. ✅ Voir requête GET /api/pools → 200 OK
4. ✅ Voir requête GET /api/tokens/... → 200 OK
5. ✅ Headers CORS présents sur toutes les réponses

---

## 📋 CHECKLIST DE VÉRIFICATION FINALE

### Backend (Canton Network + Spring Boot)
- [ ] Canton DevNet actif (port 5001)
- [ ] Backend Spring Boot actif (port 8080)
- [ ] 5 pools visibles via `curl http://localhost:8080/api/pools`
- [ ] Ngrok tunnel actif sur `https://nonexplicable-lacily-leesa.ngrok-free.dev`
- [ ] CORS configuré avec `https://app.clearportx.com`
- [ ] OPTIONS preflight retourne 200 OK

### Frontend (Netlify)
- [ ] Build v1.0.5+ déployé sur Netlify
- [ ] Pas d'erreurs TypeScript dans le build log
- [ ] Variables d'environnement configurées dans Netlify Dashboard
- [ ] `REACT_APP_DISABLE_AUTH=true` présent
- [ ] Pas de `REACT_APP_KEYCLOAK_URL` dans les variables

### Tests Browser
- [ ] Aucune erreur `localhost:8082` dans la console
- [ ] Aucune erreur `ERR_CONNECTION_REFUSED`
- [ ] Aucune erreur CORS
- [ ] Pools visibles sur https://app.clearportx.com/swap
- [ ] Network tab montre requêtes vers ngrok URL
- [ ] Toutes les requêtes retournent 200 OK

---

## 🔧 DÉPANNAGE

### Problème: Pools toujours invisibles après déploiement

**Vérifications:**
```bash
# 1. Vérifier que Netlify a bien rebuild
# → Dashboard Netlify → Deploys → Voir le dernier deploy
# → Chercher dans les logs: "REACT_APP_DISABLE_AUTH"

# 2. Vider le cache browser
# → Ctrl+Shift+Delete → Clear cache
# → Ou Ctrl+Shift+R pour hard refresh

# 3. Vérifier les variables Netlify
# → Site settings → Environment variables
# → Confirmer REACT_APP_DISABLE_AUTH=true

# 4. Redéclencher un deploy
# → Deploys → Trigger deploy → Clear cache and deploy site
```

### Problème: Erreur "localhost:8082" persiste

**Cause:** Le build Netlify utilise encore l'ancien code.

**Solution:**
```bash
# 1. Vérifier que les commits sont poussés
cd /root/canton-website
git log --oneline -3
# → Doit montrer le commit "Add REACT_APP_DISABLE_AUTH support"

# 2. Forcer un nouveau build sur Netlify
# → Deploys → Trigger deploy → Clear cache and deploy site

# 3. Attendre 2-3 minutes pour le build complet

# 4. Vérifier dans les logs Netlify:
# → Chercher "REACT_APP_DISABLE_AUTH=true"
```

### Problème: CORS 403 errors

**Solution:**
```bash
# Vérifier la config backend
cd /root/cn-quickstart/quickstart/backend
grep -A 10 "allowed-origins" src/main/java/com/digitalasset/quickstart/security/DevNetSecurityConfig.java

# Doit contenir:
# "https://app.clearportx.com",
# "https://nonexplicable-lacily-leesa.ngrok-free.dev"

# Si manquant, ajouter et redémarrer:
pkill -9 -f "java.*quickstart"
cd /root/cn-quickstart/quickstart/clearportx
./start-backend-production.sh
```

### Problème: Ngrok URL a changé

**Quand ngrok redémarre, son URL change!**

**Actions:**
```bash
# 1. Noter la nouvelle URL ngrok
ngrok http 8080
# → Nouvelle URL: https://different-url.ngrok-free.dev

# 2. Mettre à jour DevNetSecurityConfig.java
# → Remplacer l'ancienne URL par la nouvelle (ligne 41)

# 3. Mettre à jour .env.production
cd /root/canton-website/app
# → Remplacer REACT_APP_BACKEND_API_URL

# 4. Mettre à jour Netlify variables
# → Dashboard → Environment variables
# → REACT_APP_BACKEND_API_URL = nouvelle URL

# 5. Rebuild backend
pkill -9 -f "java.*quickstart"
cd /root/cn-quickstart/quickstart/clearportx
./start-backend-production.sh

# 6. Redeploy Netlify
# → Trigger deploy
```

---

## 📊 ARCHITECTURE FINALE

```
┌─────────────────────────────────────────────────────────────┐
│                     UTILISATEUR BROWSER                      │
│                  https://app.clearportx.com                  │
└────────────────────────┬────────────────────────────────────┘
                         │
                         │ HTTPS
                         │
┌────────────────────────▼────────────────────────────────────┐
│                      NETLIFY CDN                             │
│                   (Frontend React)                           │
│  - Build version: 1.0.5                                      │
│  - REACT_APP_DISABLE_AUTH=true                               │
│  - REACT_APP_BACKEND_API_URL=ngrok                           │
└────────────────────────┬────────────────────────────────────┘
                         │
                         │ HTTPS
                         │
┌────────────────────────▼────────────────────────────────────┐
│                      NGROK TUNNEL                            │
│   https://nonexplicable-lacily-leesa.ngrok-free.dev         │
│   → Forwards to localhost:8080                               │
└────────────────────────┬────────────────────────────────────┘
                         │
                         │ HTTP (local)
                         │
┌────────────────────────▼────────────────────────────────────┐
│              SPRING BOOT BACKEND (Port 8080)                 │
│  - DevNetSecurityConfig (CORS enabled)                       │
│  - OAuth2 DISABLED (permitAll)                               │
│  - Endpoints: /api/pools, /api/swap, /api/liquidity         │
└────────────────────────┬────────────────────────────────────┘
                         │
                         │ gRPC
                         │
┌────────────────────────▼────────────────────────────────────┐
│         CANTON NETWORK DEVNET (Port 5001)                    │
│  - 14 Super Validators                                       │
│  - app-provider party                                        │
│  - 5 ETH-USDC Pools active                                   │
│  - DAR: clearportx-amm-1.0.4                                 │
└─────────────────────────────────────────────────────────────┘
```

---

## 🎯 RÉSUMÉ DU PLAN

### Temps Total Estimé: ~70 minutes

1. **Étape 1 (30 min):** Modifier auth.ts et backendApi.ts pour supporter DISABLE_AUTH
2. **Étape 2 (10 min):** Mettre à jour .env.production et variables Netlify
3. **Étape 3 (15 min):** Rebuild et déployer sur Netlify
4. **Étape 4 (15 min):** Vérification complète de bout en bout

### Résultat Attendu

✅ **Frontend Netlify** → Peut charger et afficher les pools
✅ **Pas d'erreurs d'auth** → Mode mock activé
✅ **Communication ngrok** → Tunnel fonctionne correctement
✅ **Backend répond** → Tous les endpoints accessibles
✅ **CORS configuré** → Pas de blocages navigateur
✅ **Canton Network** → Pools visibles et actifs

---

## 📝 NOTES IMPORTANTES

### Limitations Actuelles

1. **Authentication désactivée** - Mode démo uniquement
   - Pour production réelle: configurer Keycloak public
   - Ou utiliser Canton Loop wallet

2. **ngrok URL temporaire** - Change à chaque redémarrage
   - Pour production: domaine personnalisé ngrok (payant)
   - Ou déployer backend sur cloud (AWS, GCP, Azure)

3. **Tokens vides** - app-provider n'a pas de tokens
   - Pour tester les swaps: créer de vrais tokens pour alice/bob
   - Utiliser scripts: mint-tokens-alice.daml

### Prochaines Étapes (Après Connexion Réussie)

1. **Créer de vrais tokens** pour tester les swaps
2. **Tester les endpoints swap** (/api/swap/atomic)
3. **Activer OAuth2** avec Keycloak public
4. **Déployer backend** sur infrastructure permanente
5. **Configurer Canton Loop** pour wallet non-custodial

---

## ✅ SUCCÈS ATTENDU

Après avoir suivi ce plan, vous devriez voir:

🎉 **https://app.clearportx.com/swap**
- ✅ Pools ETH-USDC visibles
- ✅ Aucune erreur dans la console
- ✅ Login fonctionne en mode mock
- ✅ Interface complètement fonctionnelle

**VOTRE AMM DEX EST MAINTENANT CONNECTÉ ET OPÉRATIONNEL!** 🚀
