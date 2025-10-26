# 🔧 FIX CRITIQUE: Ngrok Warning Page

## 🎯 PROBLÈME IDENTIFIÉ

### Erreur Console
```
Uncaught (in promise) SyntaxError: Unexpected token '<', "<!DOCTYPE "... is not valid JSON
```

### Root Cause
Ngrok **free tier** affiche une page d'avertissement HTML avant de permettre l'accès à l'API:

```html
<!DOCTYPE html>
<html>
  <head><title>ngrok - Tunnel Warning</title></head>
  <body>
    <p>You are about to visit https://nonexplicable-lacily-leesa.ngrok-free.dev</p>
    <button>Visit Site</button>
  </body>
</html>
```

Le frontend essaie de parser cette page HTML comme JSON → **erreur de parsing**.

---

## ✅ SOLUTION APPLIQUÉE

### Commit: eb042129

**Fichier modifié:** `app/src/services/backendApi.ts`

**Changement (ligne 84):**
```typescript
headers: {
  'Content-Type': 'application/json',
  'ngrok-skip-browser-warning': 'true', // Skip ngrok warning page
}
```

### Comment ça marche?

Le header magique `ngrok-skip-browser-warning: true` indique à ngrok de **ne pas afficher** la page d'avertissement et de laisser passer directement les requêtes API.

---

## 🧪 TEST AVANT/APRÈS

### AVANT le fix

**Browser fetch:**
```javascript
fetch('https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools')
  .then(r => r.json())
  .then(d => console.log(d))
```

**Résultat:**
```
❌ SyntaxError: Unexpected token '<', "<!DOCTYPE "... is not valid JSON
```

**Response était:**
```html
<!DOCTYPE html>
<html>
  <head><title>ngrok - Tunnel Warning</title></head>
  ...
</html>
```

---

### APRÈS le fix

**Browser fetch (avec header):**
```javascript
fetch('https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools', {
  headers: { 'ngrok-skip-browser-warning': 'true' }
})
  .then(r => r.json())
  .then(d => console.log(d))
```

**Résultat attendu:**
```json
✅ [
  {
    "poolId": "ETH-USDC-01",
    "symbolA": "ETH",
    "symbolB": "USDC",
    "reserveA": "100.0000000000",
    "reserveB": "200000.0000000000",
    ...
  }
]
```

---

## 📊 STATUT DÉPLOIEMENT

### Code Changes
- [x] backendApi.ts modifié avec header `ngrok-skip-browser-warning`
- [x] Commit eb042129 créé
- [x] Push vers Zoroki110/canton-website
- [x] Netlify auto-build déclenché

### Build Netlify
- [ ] **Build en cours** (ETA: 2-3 minutes)
- [ ] Status: Published (À VÉRIFIER)
- [ ] Version: 1.0.6 ou supérieure

---

## 🎯 VÉRIFICATION (DANS 2-3 MIN)

### Test 1: Console Browser

**Ouvrir:** https://app.clearportx.com

**F12 → Console, puis taper:**
```javascript
fetch('https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools', {
  headers: { 'ngrok-skip-browser-warning': 'true' }
})
  .then(r => r.json())
  .then(d => console.log('Pools:', d.length, 'pools'))
```

**✅ Résultat attendu:**
```
Pools: 5 pools
```

**❌ Si erreur persist:**
- Vérifier que le build Netlify est terminé
- Vider cache browser (Ctrl+Shift+R)
- Attendre 1-2 minutes de plus

---

### Test 2: Network Tab

**F12 → Network → Rafraîchir page**

**Filtrer:** `pools`

**✅ Vérifier requête:**
```
GET https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools

Request Headers:
  ngrok-skip-browser-warning: true
  X-Party: app-provider::1220...
  Authorization: Bearer devnet-mock-token

Response:
  Status: 200 OK
  Content-Type: application/json
  Body: [5 pools en JSON]
```

**❌ Si toujours HTML:**
- Le build n'est pas encore déployé
- Attendre le build Netlify

---

### Test 3: Interface UI

**Page:** https://app.clearportx.com/swap

**✅ Vérifier:**
- [ ] Sélecteur "Select Pool" fonctionne
- [ ] **Au moins 1 pool ETH-USDC visible** dans la liste
- [ ] Réserves affichées: 100 ETH / 200,000 USDC
- [ ] Fee rate: 0.3%

**Note:** Comme il y a 5 pools identiques (`poolId: ETH-USDC-01`), après déduplication il n'en reste qu'**1 seul** affiché.

---

## 📝 TIMELINE

```
21:00 - Problème identifié (ngrok warning page)
21:02 - Fix appliqué (ngrok-skip-browser-warning header)
21:03 - Commit eb042129 créé et poussé
21:04 - Netlify build triggered
21:06 - 🎯 BUILD TERMINÉ (VÉRIFIER MAINTENANT!)
21:07 - 🎯 POOLS DOIVENT ÊTRE VISIBLES!
```

---

## 🔧 SI PROBLÈMES PERSISTENT

### Problème: Toujours erreur JSON parsing

**Cause possible:** Build Netlify pas encore déployé

**Solution:**
```bash
# Vérifier le dernier commit
cd /root/canton-website
git log --oneline -1
# Doit montrer: eb042129 fix: add ngrok-skip-browser-warning

# Vérifier sur Netlify Dashboard
# → Deploys → Doit montrer "Published"
```

### Problème: Header pas envoyé

**Vérifier dans Network tab:**
- Request Headers doit contenir `ngrok-skip-browser-warning: true`
- Si absent → Build pas encore déployé ou cache browser

**Solution:**
```
1. Vider cache: Ctrl+Shift+Delete
2. Hard refresh: Ctrl+Shift+R
3. Ou ouvrir en navigation privée
```

### Problème: Pools toujours pas visibles

**Debug étape par étape:**

```javascript
// 1. Test fetch brut
fetch('https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools', {
  headers: { 'ngrok-skip-browser-warning': 'true' }
})
  .then(r => r.text())
  .then(t => console.log('Raw response:', t.substring(0, 100)))

// Si commence par "[{" → JSON OK ✅
// Si commence par "<!DOCTYPE" → HTML warning page ❌
```

---

## 🎉 SUCCÈS ATTENDU

### Console Log Final
```
ClearportX Build Info: Object
Backend URL configured: https://nonexplicable-lacily-leesa.ngrok-free.dev
Build version: 1.0.6
🔓 Auth disabled - using mock authentication
👤 Adding X-Party header: app-provider::1220...
✅ Pools loaded: 1 unique pool (ETH-USDC)
```

### Network Tab Final
```
GET /api/pools
├─ Status: 200 OK
├─ Request Headers:
│  ├─ ngrok-skip-browser-warning: true
│  ├─ X-Party: app-provider::1220...
│  └─ Authorization: Bearer devnet-mock-token
└─ Response: JSON avec 5 pools
```

### UI Final
```
┌────────────────────────────────────┐
│ Swap                               │
│                                    │
│ From: ETH                          │
│ Amount: [____]                     │
│                                    │
│ To: USDC                           │
│ Amount: [____]                     │
│                                    │
│ Pool: ETH-USDC-01 ▼               │
│   └─ ETH-USDC-01                  │
│      100 ETH / 200,000 USDC       │
│      Fee: 0.3%                     │
│                                    │
│ [Swap Now]                         │
└────────────────────────────────────┘
```

---

## 🚀 PROCHAINES ÉTAPES

Une fois que les pools s'affichent:

1. **Tester un swap** (il échouera car pas de tokens pour alice, mais l'UI doit fonctionner)
2. **Créer de vrais tokens** pour alice avec des scripts DAML
3. **Tester un vrai swap end-to-end**
4. **Ajouter plus de pools** (ETH-DAI, USDC-DAI, etc.)

---

## 📚 RÉFÉRENCES

**Ngrok Documentation:**
- Warning page: https://ngrok.com/docs/http#warning-page
- Skip header: `ngrok-skip-browser-warning: true`

**Related Issues:**
- Ngrok free tier limitations
- Browser CORS with ngrok
- JSON parsing errors with HTML responses

---

**STATUS:** 🔄 **WAITING FOR NETLIFY BUILD (~2-3 MIN)**

**NEXT:** Rafraîchir https://app.clearportx.com et vérifier que les pools s'affichent! 🎯
