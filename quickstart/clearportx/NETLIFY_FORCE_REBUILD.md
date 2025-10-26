# 🚨 FORCER NETLIFY À RECONSTRUIRE COMPLÈTEMENT

## ❌ Si "Deploy project without cache" ne fonctionne pas

### SOLUTION 1: CHANGEZ LE BUILD COMMAND
1. **Site settings** → **Build & deploy** → **Build settings**
2. Changez le Build command de:
   ```
   cd app && npm run build
   ```
   À:
   ```
   cd app && rm -rf node_modules .cache && npm install && npm run build
   ```
3. **Save** et redéployez

### SOLUTION 2: VARIABLES D'ENVIRONNEMENT
1. **Site settings** → **Environment variables**
2. Ajoutez ces variables:
   ```
   REACT_APP_BACKEND_API_URL = https://nonexplicable-lacily-leesa.ngrok-free.dev
   DISABLE_ESLINT_PLUGIN = true
   GENERATE_SOURCEMAP = false
   CI = false
   ```
3. Ajoutez une variable temporaire pour forcer le rebuild:
   ```
   CACHE_BUST = 1
   ```
4. Redéployez

### SOLUTION 3: CHANGEZ LE NOM DU SITE
1. **Site settings** → **General** → **Site details**
2. Changez le nom du site (ex: ajouter "-v2")
3. Cela force souvent un rebuild complet

### SOLUTION 4: NETLIFY CLI (Local)
Si vous avez accès à un terminal local:
```bash
npm install -g netlify-cli
netlify login
netlify link
netlify deploy --prod --build
```

### SOLUTION 5: CRÉEZ UN NOUVEAU SITE
1. **New site from Git**
2. Choisissez le même repo
3. Ajoutez immédiatement:
   ```
   REACT_APP_BACKEND_API_URL = https://nonexplicable-lacily-leesa.ngrok-free.dev
   ```
4. Deploy

## 🔍 VÉRIFICATION
Après le déploiement, dans la console (F12):
- Vous devriez voir: "Build version: 1.0.1"
- PAS de références à mockCantonApi ou realCantonApi
