# 🚀 GUIDE COMPLET: CONNEXION NETLIFY + NGROK

## 📋 Votre situation actuelle
- ✅ Compte ngrok créé  
- ✅ Authtoken obtenu: `34a2CG3Lq6eG0eSpBqVhRREy3nM_5crrbgVKX4omcZomFEKP6`
- ✅ Ngrok tunnel actif: `https://nonexplicable-lacily-leesa.ngrok-free.dev`
- ❌ Frontend Netlify ne voit pas les pools (erreur CORS)

## 🔧 ÉTAPE 1: Configurer ngrok avec votre authtoken

```bash
# Dans votre terminal où ngrok tourne
ngrok config add-authtoken 34a2CG3Lq6eG0eSpBqVhRREy3nM_5crrbgVKX4omcZomFEKP6
```

## 🔧 ÉTAPE 2: Corriger le problème CORS backend

```bash
cd /root/cn-quickstart/quickstart/clearportx

# Rendre le script exécutable et l'exécuter
chmod +x fix-cors-backend.sh
./fix-cors-backend.sh
```

## 🔧 ÉTAPE 3: Redémarrer le backend avec CORS corrigé

```bash
# Définir les origines autorisées (incluant votre URL ngrok)
export CORS_ALLOWED_ORIGINS='https://app.clearportx.com,https://nonexplicable-lacily-leesa.ngrok-free.app,https://nonexplicable-lacily-leesa.ngrok-free.dev,http://localhost:3000'

# Redémarrer le backend
cd /root/cn-quickstart/quickstart/clearportx
./start-backend-production.sh
```

## 🌐 ÉTAPE 4: Configurer Netlify

### Option A: Variables d'environnement (Recommandé)
1. Allez sur [Netlify Dashboard](https://app.netlify.com)
2. Sélectionnez votre site
3. **Site settings** → **Environment variables**
4. Ajoutez ces variables:
   ```
   REACT_APP_BACKEND_API_URL = https://nonexplicable-lacily-leesa.ngrok-free.dev
   REACT_APP_CANTON_API_URL = https://nonexplicable-lacily-leesa.ngrok-free.dev
   ```
5. **Trigger deploy** pour redéployer avec les nouvelles variables

### Option B: Build local et déployer
```bash
cd /root/canton-website
export REACT_APP_BACKEND_API_URL=https://nonexplicable-lacily-leesa.ngrok-free.dev
export REACT_APP_CANTON_API_URL=https://nonexplicable-lacily-leesa.ngrok-free.dev
npm run build
netlify deploy --prod --dir=build
```

## 🧪 ÉTAPE 5: Créer des données de test

```bash
cd /root/cn-quickstart/quickstart/clearportx
./create-test-data.sh
```

## ✅ VÉRIFICATION

1. Ouvrez https://app.clearportx.com/swap
2. Ouvrez la console (F12)
3. Vous devriez voir:
   - Les requêtes vont vers votre URL ngrok
   - Pas d'erreurs CORS
   - Les pools s'affichent

## ⚠️ IMPORTANT

- **L'URL ngrok change** à chaque redémarrage de ngrok!
- Quand ngrok redémarre:
  1. Notez la nouvelle URL
  2. Mettez à jour `CORS_ALLOWED_ORIGINS` et redémarrez le backend
  3. Mettez à jour les variables Netlify et redéployez

## 🆘 DÉPANNAGE

### Erreur CORS persiste?
```bash
# Vérifier que le backend a bien redémarré
curl https://nonexplicable-lacily-leesa.ngrok-free.dev/api/health

# Vérifier les headers CORS
curl -I -X OPTIONS https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools \
  -H "Origin: https://app.clearportx.com" \
  -H "Access-Control-Request-Method: GET"
```

### Pas de pools?
```bash
# Vérifier que les pools existent
curl https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools | jq .
```

### Frontend ne se met pas à jour?
- Videz le cache navigateur (Ctrl+Shift+R)
- Vérifiez dans Network tab (F12) que les requêtes vont vers ngrok

## 📝 NOTES

Pour une solution permanente, considérez:
- Un domaine personnalisé ngrok (payant)
- Déployer le backend sur un serveur cloud
- Utiliser Canton Network cloud services
