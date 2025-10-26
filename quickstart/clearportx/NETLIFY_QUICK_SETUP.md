# 🚀 GUIDE RAPIDE: CONNECTER NETLIFY À VOTRE BACKEND CANTON

## 📍 OÙ ÊTES-VOUS MAINTENANT

- **Frontend**: https://app.clearportx.com (Netlify)
- **Backend**: localhost:8080 (Votre serveur Canton)
- **Problème**: Le frontend ne peut pas accéder à localhost

## 🛠️ SOLUTION EN 5 MINUTES

### 1️⃣ Exposer votre backend avec ngrok

```bash
cd /root/cn-quickstart/quickstart/clearportx
chmod +x setup-for-netlify.sh
./setup-for-netlify.sh
```

Vous obtiendrez une URL comme: `https://abc123.ngrok-free.app`

### 2️⃣ Configurer Netlify

1. Allez sur [app.netlify.com](https://app.netlify.com)
2. Sélectionnez votre site **clearportx-amm**
3. Allez dans: **Site configuration** → **Environment variables**

### 3️⃣ Ajouter les variables d'environnement

Cliquez sur **"Add a variable"** et ajoutez:

| Key | Value |
|-----|-------|
| `REACT_APP_BACKEND_API_URL` | `https://YOUR-NGROK-URL.ngrok-free.app` |
| `REACT_APP_USE_MOCK_DATA` | `false` |
| `REACT_APP_CANTON_API_URL` | `https://YOUR-NGROK-URL.ngrok-free.app` |

### 4️⃣ Redéployer

1. Allez dans **Deploys**
2. Cliquez sur **"Trigger deploy"**
3. Choisissez **"Clear cache and deploy"**

### 5️⃣ Tester

1. Attendez que le déploiement soit terminé (2-3 minutes)
2. Allez sur https://app.clearportx.com
3. Ouvrez F12 → Network
4. Vous devriez voir les requêtes vers votre ngrok URL

## 🎯 VÉRIFICATION RAPIDE

✅ **Succès si vous voyez:**
- 5 pools ETH-USDC dans l'interface
- Requêtes API vers ngrok dans F12
- Pas d'erreurs CORS

❌ **Si ça ne marche pas:**
- Vérifiez que ngrok est toujours running
- Vérifiez l'URL dans les variables Netlify
- Clear cache du navigateur (Ctrl+Shift+R)

## 📊 CE QUI SE PASSE

```
app.clearportx.com → ngrok.io → localhost:8080 → Canton DevNet
    (Netlify)        (Tunnel)     (Backend)        (Ledger)
```

## 💡 TIPS

- **Ngrok gratuit**: L'URL change à chaque redémarrage
- **Solution**: Créez un compte ngrok pour une URL fixe
- **Alternative**: Déployez le backend sur AWS/Google Cloud

## 🆘 SUPPORT

Si vous êtes bloqué:
1. Vérifiez http://localhost:4040 (ngrok dashboard)
2. Vérifiez les logs: `tail -f /tmp/backend-production.log`
3. Testez l'API directement: `curl https://YOUR-NGROK.ngrok-free.app/api/pools`

**C'est tout! Votre AMM DEX est maintenant accessible mondialement!** 🌍
