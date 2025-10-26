# 🚀 GUIDE DE DÉPLOIEMENT NETLIFY + BACKEND CANTON

## 📋 CONFIGURATION ACTUELLE

Votre frontend est déployé sur Netlify:
- **URL**: https://app.clearportx.com
- **GitHub**: github.com/Zoroki110/canton-website
- **Build**: `cd app && npm ci && npm run build`
- **Directory**: `app/build`

## 🔧 ÉTAPES DE CONFIGURATION

### 1️⃣ Variables d'environnement Netlify

Dans Netlify Dashboard → **Environment variables**, ajoutez:

```bash
# Backend API URL (sera l'URL ngrok ou serveur de production)
REACT_APP_BACKEND_API_URL=https://your-ngrok-url.ngrok-free.app

# Canton Configuration
REACT_APP_CANTON_API_URL=https://your-ngrok-url.ngrok-free.app
REACT_APP_PACKAGE_ID=5ce4bf9f9cd097fa7d4d63b821bd902869354ddf2726d70ed766ba507c3af1b4

# Disable mock data
REACT_APP_USE_MOCK_DATA=false
REACT_APP_ENV=production
```

### 2️⃣ Option A: Tunnel Ngrok (Test/Dev)

```bash
# Sur votre serveur local
cd /root/cn-quickstart/quickstart/clearportx

# Démarrer ngrok
./quick-ngrok-setup.sh

# Copier l'URL HTTPS (ex: https://abc123.ngrok-free.app)
# Mettre à jour REACT_APP_BACKEND_API_URL dans Netlify
```

### 3️⃣ Option B: Déploiement Cloud (Production)

#### Sur AWS EC2:
```bash
# 1. Créer une instance EC2 Ubuntu
# 2. Installer Java, Canton, etc.
# 3. Cloner votre backend
git clone https://github.com/YOUR-REPO/canton-backend.git

# 4. Configurer CORS pour app.clearportx.com
# 5. Démarrer le backend
./start-backend-production.sh

# 6. Configurer un domaine ou utiliser l'IP publique
# 7. Mettre à jour Netlify env var avec l'URL
```

#### Sur Google Cloud Run:
```dockerfile
# Dockerfile pour le backend
FROM openjdk:17-jdk-slim
COPY backend/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### 4️⃣ Netlify Functions Proxy (Alternative)

Créez `/root/canton-website/netlify/functions/api-proxy.js`:

```javascript
const axios = require('axios');

// Backend URL (from env var or hardcoded for now)
const BACKEND_URL = process.env.BACKEND_URL || 'http://your-backend:8080';

exports.handler = async (event, context) => {
  const path = event.path.replace('/.netlify/functions/api-proxy', '');
  const url = `${BACKEND_URL}${path}`;
  
  try {
    const response = await axios({
      method: event.httpMethod,
      url: url,
      headers: {
        ...event.headers,
        'host': undefined,
        'content-length': undefined
      },
      data: event.body ? JSON.parse(event.body) : undefined,
      params: event.queryStringParameters
    });
    
    return {
      statusCode: response.status,
      headers: {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': '*',
        'Access-Control-Allow-Methods': '*'
      },
      body: JSON.stringify(response.data)
    };
  } catch (error) {
    return {
      statusCode: error.response?.status || 500,
      body: JSON.stringify({ 
        error: error.message,
        details: error.response?.data 
      })
    };
  }
};
```

### 5️⃣ Mise à jour du Frontend

Dans `/root/canton-website/app/src/services/backendApi.ts`:

```typescript
// Utiliser les variables d'environnement Netlify
const BACKEND_URL = process.env.REACT_APP_BACKEND_API_URL || 
  (process.env.NODE_ENV === 'production' 
    ? '/.netlify/functions/api-proxy/api'  // Si using Netlify Functions
    : 'http://localhost:8080');
```

## 📱 FLUX DE DÉPLOIEMENT

```
1. Push code → GitHub
2. Netlify détecte le changement
3. Build automatique (npm ci && npm run build)
4. Deploy sur app.clearportx.com
5. Variables d'env utilisées pour l'API
```

## 🧪 TEST RAPIDE AVEC NGROK

```bash
# 1. Sur votre serveur Canton
cd /root/cn-quickstart/quickstart/clearportx
./start-backend-production.sh

# 2. Créer tunnel ngrok
ngrok http 8080

# 3. Dans Netlify Environment Variables
REACT_APP_BACKEND_API_URL=https://YOUR-NGROK.ngrok-free.app

# 4. Redéployer (Clear cache and deploy)
```

## 🔍 VÉRIFICATION

1. Allez sur https://app.clearportx.com
2. Ouvrez F12 → Network
3. Les requêtes API doivent aller vers:
   - Ngrok URL (si test)
   - Ou votre serveur de production

## 🚨 IMPORTANT

### CORS Headers Backend
Assurez-vous que votre backend accepte:
```java
allowedOrigins: 
- https://app.clearportx.com
- https://clearportx.netlify.app
- http://localhost:3000 (pour dev local)
```

### Variables Sensibles
Ne jamais mettre dans le code:
- JWT secrets
- Private keys
- Database passwords

Utilisez toujours les Netlify Environment Variables!

## 📊 ARCHITECTURE FINALE

```
┌─────────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐
│  app.clearportx.com │────▶│   Ngrok/Cloud       │────▶│  Canton Backend     │
│  (Netlify)          │     │   (Public URL)      │     │  (Your Server)      │
└─────────────────────┘     └─────────────────────┘     └─────────────────────┘
                                                                    │
                                                                    ▼
                                                         ┌─────────────────────┐
                                                         │  Canton DevNet      │
                                                         │  (Ledger)           │
                                                         └─────────────────────┘
```

## ✅ CHECKLIST

- [ ] Variables d'environnement configurées dans Netlify
- [ ] Backend accessible publiquement (ngrok ou cloud)
- [ ] CORS configuré pour app.clearportx.com
- [ ] Frontend utilise REACT_APP_BACKEND_API_URL
- [ ] Tests passent avec F12 open
- [ ] Pas d'erreurs CORS
- [ ] Pools visibles
- [ ] Login fonctionne

Une fois tout configuré, votre AMM DEX sera 100% fonctionnel en production! 🎉
