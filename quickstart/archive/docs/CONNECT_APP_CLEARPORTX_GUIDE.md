# 🔌 GUIDE DE CONNEXION APP.CLEARPORTX.COM ↔️ BACKEND CANTON

## 🏗️ ARCHITECTURE ACTUELLE

```
┌─────────────────────────┐         ┌─────────────────────────┐
│   app.clearportx.com    │         │   Votre Backend Local   │
│   (Frontend Public)     │   ???   │   (localhost:8080)      │
│                         │ ──────► │                         │
│  - React/TypeScript     │         │  - Spring Boot Java     │
│  - Déployé en ligne     │         │  - Canton DevNet API    │
│  - https://...          │         │  - 5 Pools ETH-USDC    │
└─────────────────────────┘         └─────────────────────────┘
                                              │
                                              ▼
                                    ┌─────────────────────────┐
                                    │   Canton DevNet         │
                                    │   (localhost:5001)      │
                                    │                         │
                                    │  - Validator Running    │
                                    │  - Smart Contracts      │
                                    │  - Real Ledger State    │
                                    └─────────────────────────┘
```

## 🚇 SOLUTION: TUNNEL NGROK

```
┌─────────────────────────┐         ┌─────────────────────────┐
│   app.clearportx.com    │         │      NGROK TUNNEL       │
│                         │ ──────► │  https://abc.ngrok.io   │
│  Configure Backend URL: │  HTTPS  │                         │
│  https://abc.ngrok.io   │         └───────────┬─────────────┘
└─────────────────────────┘                     │
                                                │
                                                ▼
                                    ┌─────────────────────────┐
                                    │   localhost:8080        │
                                    │   (Votre Backend)       │
                                    │                         │
                                    │  ✅ CORS: Accepte       │
                                    │     app.clearportx.com  │
                                    └─────────────────────────┘
```

## 📋 ÉTAPES DE CONFIGURATION

### 1️⃣ Créer le tunnel ngrok
```bash
cd /root/cn-quickstart/quickstart/clearportx
./quick-ngrok-setup.sh
```
→ Notez l'URL HTTPS fournie (ex: `https://abc123.ngrok-free.app`)

### 2️⃣ Créer les utilisateurs Canton
```bash
./create-canton-users.sh
```
Cela créera:
- alice@clearportx
- bob@clearportx  
- charlie@clearportx

### 3️⃣ Configurer le frontend

**Option A: Via Console (F12)**
```javascript
// Dans la console de app.clearportx.com
localStorage.setItem('BACKEND_URL', 'https://YOUR-NGROK-URL.ngrok-free.app');
location.reload();
```

**Option B: Via l'interface**
Si votre app a un menu de configuration, entrez l'URL ngrok là.

### 4️⃣ Tester la connexion

1. **Ouvrez F12 → Network Tab**
2. **Rechargez la page**
3. **Vérifiez que les requêtes vont vers ngrok:**
   ```
   GET https://abc123.ngrok-free.app/api/pools
   GET https://abc123.ngrok-free.app/api/tokens/alice
   ```

4. **Vérifiez les headers CORS:**
   ```
   Access-Control-Allow-Origin: https://app.clearportx.com
   Access-Control-Allow-Credentials: true
   ```

## 👤 UTILISATEURS DE TEST

### Existants sur DevNet:
- **app-provider**: A créé les 5 pools ETH-USDC

### À créer avec le script:
- **alice@clearportx**: 100 ETH + 50,000 USDC
- **bob@clearportx**: 50 ETH + 100,000 USDC
- **charlie@clearportx**: 200 ETH + 25,000 USDC

## 🧪 TEST COMPLET

1. **Login**: Utilisez "alice@clearportx"
2. **Voir les pools**: 5 pools ETH-USDC doivent apparaître
3. **Voir les balances**: Alice doit avoir ses tokens
4. **Faire un swap**: 1 ETH → ~1,974 USDC
5. **Vérifier F12**: La transaction doit passer par ngrok

## 🔍 TROUBLESHOOTING

### ❌ "Network Error" ou "CORS Error"
- Vérifiez que ngrok est running
- Vérifiez l'URL dans localStorage
- Vérifiez que le backend est démarré

### ❌ "No pools found"
- Le backend n'est pas connecté au bon ledger
- Redémarrez avec: `./start-backend-production.sh`

### ❌ "User not found"
- Créez les utilisateurs: `./create-canton-users.sh`
- Ou utilisez "app-provider" qui existe déjà

## ✅ SUCCÈS!

Quand tout fonctionne:
- ✅ Pools visibles dans l'UI
- ✅ Balances correctes affichées
- ✅ Swaps exécutables
- ✅ F12 montre les requêtes vers ngrok
- ✅ Pas d'erreurs CORS

**Votre AMM DEX est maintenant accessible depuis n'importe où dans le monde via app.clearportx.com!** 🎉
