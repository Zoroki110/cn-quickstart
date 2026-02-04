# 📋 SOLUTION COMPLÈTE - VISIBILITÉ DES POOLS AMM SUR CANTON

**Date**: 25 Octobre 2025  
**Problème résolu**: Les pools étaient créés sur le ledger mais invisibles via l'API backend  
**Temps de résolution**: 2+ jours → Maintenant RÉSOLU ✅

## 🔍 LE PROBLÈME INITIAL

L'API backend retournait toujours `[]` (tableau vide) même après avoir créé des pools avec succès sur le ledger Canton.

```bash
# Avant le fix
curl http://localhost:8080/api/pools
[]  # Toujours vide!
```

## 🎯 LES DEUX CAUSES PROFONDES

### 1. Non-déterminisme du Package Hash DAML
- Chaque `daml build` générait un hash différent pour le même code
- Le backend cherchait avec hash A, mais les pools étaient créés avec hash B
- **Exemple**: Backend cherchait `233b8f34ec8c5650...` mais les pools avaient `5ce4bf9f9cd097fa...`

### 2. Mauvaise Résolution du Party ID
- Le backend utilisait le nom court `"app-provider"` au lieu du party ID complet
- Canton nécessite le party ID complet: `"app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388"`

## ✅ LA SOLUTION EN 2 PARTIES

### Partie 1: Frozen Artifact Workflow
```bash
# 1. Créer un DAR "frozen" avec un hash fixe
cd /root/cn-quickstart/quickstart/clearportx
cp .daml/dist/clearportx-amm-1.0.4.dar artifacts/devnet/clearportx-amm-v1.0.4-frozen.dar

# 2. Modifier build.gradle.kts pour utiliser le DAR frozen
cd ../daml
sed -i 's|dar.from("$rootDir/clearportx/.*\.dar")|dar.from("$rootDir/clearportx/artifacts/devnet/clearportx-amm-v1.0.4-frozen.dar")|' build.gradle.kts

# 3. Régénérer les bindings Java avec le bon hash
../gradlew codeGenClearportX --rerun-tasks

# 4. Recompiler le backend
cd ../backend
rm -rf build/ .gradle/
../gradlew compileJava
```

### Partie 2: Configuration du Full Party ID
```bash
# INCORRECT ❌
export APP_PROVIDER_PARTY="app-provider"

# CORRECT ✅
export APP_PROVIDER_PARTY="app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388"
```

## 🚀 SCRIPT DE DÉMARRAGE POUR LE LIVE

Créez ce script `start-backend-live.sh`:

```bash
#!/bin/bash
set -e

echo "🚀 Starting ClearportX Backend for Live Deployment"

# 1. Kill any existing backend processes
pkill -9 -f "gradlew.*bootRun" || true
pkill -9 -f "java.*8080" || true

# 2. Get the full party ID from the ledger
FULL_PARTY_ID=$(grpcurl -plaintext \
  -d '{}' \
  localhost:5001 \
  com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties | \
  jq -r '.party_details[] | select(.party | startswith("app-provider::")) | .party' | head -1)

if [ -z "$FULL_PARTY_ID" ]; then
  echo "❌ ERROR: app-provider party not found on ledger!"
  exit 1
fi

echo "✅ Found app-provider: $FULL_PARTY_ID"

# 3. Set all required environment variables
export BACKEND_PORT=8080
export APP_PROVIDER_PARTY="$FULL_PARTY_ID"  # CRITICAL: Use full party ID!
export SPRING_PROFILES_ACTIVE=devnet
export CANTON_LEDGER_HOST=localhost
export CANTON_LEDGER_PORT=5001
export REGISTRY_BASE_URI="http://localhost:8090"

# 4. Start backend
cd /root/cn-quickstart/quickstart/backend
echo "Starting backend with party: $APP_PROVIDER_PARTY"
../gradlew bootRun
```

## 🔧 GUIDE DE DÉPANNAGE

### Symptôme: API retourne `[]` malgré les pools créés

**1. Vérifier le package hash:**
```bash
# Hash du DAR frozen
daml damlc inspect-dar artifacts/devnet/clearportx-amm-v1.0.4-frozen.dar --json | jq -r '.main_package_id'

# Hash utilisé par le backend (dans les logs)
grep "templateId" /tmp/backend*.log | tail -5
```
→ **Solution**: S'ils sont différents, refaire le Frozen Artifact Workflow

**2. Vérifier le party ID:**
```bash
# Party ID dans les logs backend
grep "Getting active contracts.*party" /tmp/backend*.log | tail -5

# Si c'est juste "app-provider", c'est FAUX!
```
→ **Solution**: Redémarrer avec le full party ID

**3. Vérifier la visibilité des pools:**
```bash
# Qui peut voir les pools?
cd /root/cn-quickstart/quickstart/clearportx
grep -A5 "signatory\|observer" daml/AMM/Pool.daml
```
→ Les pools sont visibles par: `poolOperator, poolParty, lpIssuer, issuerA, issuerB, protocolFeeReceiver`

## 📝 CHECKLIST POUR LE DÉPLOIEMENT LIVE

- [ ] **DAR Frozen**: Utiliser toujours le même DAR frozen (`artifacts/devnet/clearportx-amm-v1.0.4-frozen.dar`)
- [ ] **Full Party ID**: Toujours utiliser le party ID complet dans `APP_PROVIDER_PARTY`
- [ ] **PartyRegistry**: S'assurer que `PartyRegistryService` se rafraîchit (toutes les 30s)
- [ ] **Logs**: Activer les logs DEBUG pour `c.d.q.ledger.LedgerApi` et `c.d.q.s.PartyRegistryService`

## 🎉 VÉRIFICATION FINALE

```bash
# Test que tout fonctionne
curl http://localhost:8080/api/pools | jq .

# Résultat attendu:
[
  {
    "poolId": "ETH-USDC-01",
    "symbolA": "ETH",
    "symbolB": "USDC",
    "reserveA": "100.0000000000",
    "reserveB": "200000.0000000000",
    "totalLPSupply": "0.0000000000",
    "feeRate": "0.003"
  }
]
```

## 💡 POINTS CLÉS À RETENIR

1. **Canton est strict**: Il faut TOUJOURS utiliser les party IDs complets
2. **DAML package hash**: Doit être identique entre la création et la requête
3. **PartyRegistryService**: Résout les noms courts → IDs complets, mais l'API ledger a besoin du full ID
4. **Visibilité**: Les pools ne sont visibles que par les parties définies dans le template

## 🛡️ PRÉVENTION FUTURE

1. **CI/CD**: Toujours builder avec le même DAR frozen
2. **Config**: Stocker le full party ID dans la configuration, pas juste le nom
3. **Tests**: Ajouter des tests qui vérifient la visibilité des pools après création
4. **Monitoring**: Alertes si l'API retourne `[]` alors qu'il y a des pools sur le ledger

---

**Cette solution a été testée et validée le 25 octobre 2025. Elle résout définitivement le problème de visibilité des pools.**
