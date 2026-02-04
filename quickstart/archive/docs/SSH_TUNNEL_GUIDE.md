# 🔐 GUIDE SSH TUNNEL - CLEARPORTX DEX

**Date:** 23 Octobre 2025  
**Pour:** Tests locaux avec OAuth2 sécurisé

---

## 🎯 OBJECTIF

Accéder au DEX ClearportX depuis votre Mac via un tunnel SSH sécurisé, avec authentification OAuth2 via Keycloak.

---

## 📋 ÉTAPES DE CONFIGURATION

### 1. Fermer Cursor (Important!)

Cursor utilise le port 4001 sur votre Mac. **Fermez complètement Cursor** avant de continuer.

```bash
# Vérifier qu'aucun processus n'utilise le port 4001
lsof -i :4001
# Devrait ne rien retourner après avoir fermé Cursor
```

### 2. Lancer le Tunnel SSH

Ouvrez un terminal sur votre Mac et exécutez:

```bash
ssh -L 4001:localhost:3001 \
    -L 4080:localhost:8080 \
    -L 4082:localhost:8082 \
    root@5.9.70.48
```

**Explication des ports:**
- `4001` → Frontend (nginx proxy vers React app)
- `4080` → Backend API (Spring Boot avec OAuth2)
- `4082` → Keycloak (Serveur OAuth2)

**Laisser cette session SSH ouverte** pendant vos tests!

### 3. Vérifier que les Tunnels Fonctionnent

Dans un **nouveau terminal** sur votre Mac:

```bash
# Test 1: Frontend accessible
curl -I http://localhost:4001
# Devrait retourner: HTTP/1.1 200

# Test 2: Backend API accessible  
curl http://localhost:4080/api/health/ledger
# Devrait retourner JSON avec "status"

# Test 3: Keycloak accessible
curl -I http://localhost:4082
# Devrait retourner: HTTP/1.1 200
```

---

## 🌐 ACCÈS À L'APPLICATION

### URL Frontend
**http://localhost:4001**

Ouvrez cette URL dans votre navigateur (Chrome/Firefox/Safari).

---

## 🔐 LOGIN OAUTH2

### Credentials de Test

Quand l'application demande de vous connecter:

- **Username:** `alice`
- **Password:** `alice123`

### Ce qui va se passer:

1. Vous cliquez sur "Login" dans le frontend
2. Vous êtes redirigé vers Keycloak (http://localhost:4082)
3. Vous entrez: alice / alice123
4. Vous êtes redirigé vers le frontend avec un JWT token
5. Le frontend charge vos tokens et pools

---

## ✅ VÉRIFICATIONS POST-LOGIN

Après connexion, vous devriez voir:

### Vos Tokens (Balances)
- **ETH:** ~165,020
- **USDC:** ~165,010,000
- **BTC:** ~8,250
- **USDT:** ~165,005,000

### Pools Actifs
1. **ETH/USDC** - Liquidité: ~100 ETH / ~198,232 USDC
2. **ETH/USDT** - Liquidité: 100 ETH / 300,000 USDT
3. **BTC/USDC** - Liquidité: 10 BTC / 200,000 USDC

---

## 🧪 TESTS À EFFECTUER

Référez-vous au fichier [TEST_CHECKLIST.md](TEST_CHECKLIST.md) pour la liste complète des tests.

**Tests rapides:**
1. ✅ Les balances s'affichent correctement
2. ✅ Les pools ont de la liquidité
3. ✅ Swap 1 ETH → USDC fonctionne
4. ✅ Le nouveau balance se met à jour

---

## 🆘 DÉPANNAGE

### Problème: "Connection refused" sur localhost:4001

**Solution:**
```bash
# Vérifier que le tunnel SSH est actif
ps aux | grep "ssh -L"

# Si pas de résultat, relancer le tunnel:
ssh -L 4001:localhost:3001 -L 4080:localhost:8080 -L 4082:localhost:8082 root@5.9.70.48
```

### Problème: "Address already in use" sur port 4001

**Solution:**
```bash
# Vérifier quel processus utilise le port
lsof -i :4001

# Si c'est Cursor, fermez-le complètement
# Ou utilisez des ports alternatifs:
ssh -L 5001:localhost:3001 -L 5080:localhost:8080 -L 5082:localhost:8082 root@5.9.70.48
# Puis ouvrez http://localhost:5001
```

### Problème: Frontend charge mais "Unauthorized" partout

**Solution:**
```bash
# Les JWT tokens expirent après 5 minutes
# Déconnectez-vous et reconnectez-vous:
# 1. Cliquez "Logout" dans le frontend
# 2. Cliquez "Login"
# 3. Re-entrez: alice / alice123
```

### Problème: Keycloak ne répond pas

**Solution:**
```bash
# Sur le serveur (dans votre session SSH):
docker ps | grep keycloak
# Devrait montrer: keycloak - Up X days (healthy)

# Si pas healthy:
docker restart keycloak
# Attendre 30 secondes puis re-tester
```

---

## 📊 ARCHITECTURE

```
Votre Mac                    Tunnel SSH                  Serveur (5.9.70.48)
─────────                    ──────────                  ───────────────────

localhost:4001  ─────────────────────────>  nginx:3001 ─> Frontend (React)
                                                              │
localhost:4080  ─────────────────────────>  backend:8080 <──┘
                                                 │
localhost:4082  ─────────────────────────>  keycloak:8082 <─┘
                                             (OAuth2 Server)
```

---

## ✅ RÉSUMÉ

**Commande SSH:**
```bash
ssh -L 4001:localhost:3001 -L 4080:localhost:8080 -L 4082:localhost:8082 root@5.9.70.48
```

**Frontend:** http://localhost:4001  
**Login:** alice / alice123  
**Backend API:** http://localhost:4080/api  
**Keycloak:** http://localhost:4082

**Status:** ✅ Système opérationnel et sécurisé avec OAuth2!

