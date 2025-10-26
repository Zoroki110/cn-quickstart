# Guide de Test E2E - ClearportX DevNet

## État Actuel ✅

**Backend:** Running sur http://localhost:8080
- Pool ETH-USDC visible ✅
- Endpoints /api/pools, /api/tokens fonctionnels ✅
- Volume 24h tracking actif ✅

**Frontend:** Déployé sur https://clearportx.netlify.app
- Tokens chargés depuis les pools ✅
- Balances affichées (même si zéro) ✅
- Volume 24h réel (pas mock) ✅

## Ce Qui Fonctionne Déjà

1. **Pools visibles sans login** - ✅ PARFAIT
   - Ouvre https://clearportx.netlify.app/pools
   - Le pool ETH-USDC s'affiche
   - Pas besoin de se connecter

2. **Tokens extraits des pools** - ✅ PARFAIT
   - Va sur /swap
   - Les dropdowns montrent ETH et USDC (depuis le pool)
   - Les balances s'affichent (0 pour app-provider)

3. **Volume 24h réel** - ✅ PARFAIT
   - Le backend calcule depuis Prometheus
   - Plus de mock data

## Ce Qui Manque Pour Test E2E Complet

### Option 1: Créer Pools et Tokens Manuellement (RAPIDE - 10 min)

**Étape 1: Créer les parties Alice et Bob**
```bash
daml ledger allocate-party Alice --host localhost --port 5001
daml ledger allocate-party Bob --host localhost --port 5001
```

**Étape 2: Rebuild DAR avec script de setup**
```bash
cd /root/cn-quickstart/quickstart/clearportx
daml build
```

**Étape 3: Exécuter le script DAML** (si ça compile)
```bash
daml script \\
  --dar .daml/dist/clearportx-amm-1.0.4.dar \\
  --script-name CreatePoolsAndTestUsers:createPoolsAndTestUsers \\
  --ledger-host localhost \\
  --ledger-port 5001
```

### Option 2: Tester avec app-provider uniquement (TRÈS RAPIDE - 2 min)

Puisque app-provider a déjà créé le pool ETH-USDC, on peut tester:

**Test 1: Vérifier que les pools s'affichent**
```bash
curl http://localhost:8080/api/pools | jq
```

**Test 2: Vérifier les tokens de app-provider**
```bash
curl "http://localhost:8080/api/tokens/app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388" | jq
```

**Test 3: Frontend affiche bien les tokens avec balance 0**
- https://clearportx.netlify.app/swap
- Vérifier que ETH et USDC apparaissent
- Vérifier que la balance affiche "0"

### Option 3: Ajouter /api/admin/mint endpoint au backend (MOYEN - 30 min)

Problème rencontré: `LedgerApi.createContract()` n'existe pas tel quel.
Il faudrait:
1. Étudier comment SwapController crée des contrats
2. Adapter pour créer Token et Pool
3. Rebuild + restart backend
4. Tester

## Recommandation

**Pour l'instant, on a déjà fait l'essentiel!**

✅ Pools visibles sans login
✅ Tokens chargés depuis pools
✅ Balances affichées (0 si vide)
✅ Volume 24h tracking réel

**Pour un test E2E complet avec swap:**
- Il faut des utilisateurs avec des tokens
- La façon la plus simple: Rebuild le DAR avec le script CreatePoolsAndTestUsers.daml
- Ou bien: Ajouter l'endpoint /api/admin/mint (mais c'est plus complexe)

## Test Visuel Rapide (2 min)

1. Va sur https://clearportx.netlify.app
2. Page Pools → vois-tu ETH-USDC? ✅
3. Page Swap → vois-tu ETH et USDC dans les dropdowns? ✅
4. Les balances affichent "0"? ✅
5. Le volume24h affiche "0.00" (pas "mock")? ✅

**Si oui à tout → MISSION ACCOMPLIE!** 🚀

Le frontend est complet comme tu voulais. Pour faire des vrais swaps, il faut juste des users avec tokens.

## Next Step

Tu veux qu'on:
A) Rebuild le DAR et créer Alice/Bob avec tokens?
B) Tester juste visuellement le frontend (déjà fait)?
C) Ajouter l'endpoint /api/admin/mint pour faciliter les tests futurs?
