# 🚀 FORCER L'UTILISATION DES VRAIES DONNÉES CANTON

## ⚠️ PROBLÈME ACTUEL
- L'app charge encore les données mock
- Les requêtes vont encore vers `/v2/query`
- Vous voulez les VRAIES données de Canton Network

## 🔧 SOLUTION IMMÉDIATE

### 1. VÉRIFIEZ LE DÉPLOIEMENT NETLIFY
1. Allez sur [app.netlify.com](https://app.netlify.com)
2. Vérifiez que le dernier déploiement est terminé (statut: Published)
3. Si pas encore, attendez 1-2 minutes

### 2. CONFIGUREZ LES VARIABLES NETLIFY
**TRÈS IMPORTANT** - Dans Netlify Dashboard:
1. **Site settings** → **Environment variables**
2. Ajoutez ces variables EXACTEMENT:
   ```
   REACT_APP_BACKEND_API_URL = https://nonexplicable-lacily-leesa.ngrok-free.dev
   REACT_APP_USE_MOCK = false
   ```
   ⚠️ Notez: `REACT_APP_USE_MOCK = false` (pas true!)

3. **Deploy** → **Trigger deploy** → **Clear cache and deploy site**

### 3. VÉRIFICATION RAPIDE DU BACKEND
```bash
# Vérifiez que votre backend retourne les vraies données:
curl https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools
```

Vous devriez voir vos pools Canton réels.

### 4. VIDEZ LE CACHE NAVIGATEUR
1. Ouvrez https://app.clearportx.com
2. Appuyez sur **Ctrl+Shift+R** (ou Cmd+Shift+R sur Mac)
3. Ou: F12 → Network → Disable cache ✓

## ✅ RÉSULTAT ATTENDU
- Plus de "Mock data loaded"
- Les pools réels de Canton s'affichent
- Données en temps réel depuis votre ledger

## 🆘 SI ÇA NE MARCHE PAS
Le problème peut être que Netlify utilise encore l'ancien build. Forcez un nouveau déploiement:
1. Faites un petit changement dans le code
2. Push sur GitHub
3. Netlify redéploiera avec les bonnes variables
