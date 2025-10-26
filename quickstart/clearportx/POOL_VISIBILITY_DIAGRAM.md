# 🎯 DIAGRAMME DU PROBLÈME ET DE LA SOLUTION

## ❌ AVANT (Le Problème)

```
┌─────────────────────┐         ┌─────────────────────┐
│   DAML Scripts      │         │    Backend API      │
│                     │         │                     │
│ daml build ➜        │         │ APP_PROVIDER_PARTY= │
│ Hash: 5ce4bf9f...  │         │ "app-provider"      │
│                     │         │                     │
│ Creates Pool with:  │         │ Queries with:       │
│ - Hash: 5ce4bf9f    │         │ - Hash: 233b8f34 ❌ │
│ - Party: app-prov.. │         │ - Party: "app-pr.." │
└─────────┬───────────┘         └──────────┬──────────┘
          │                                │
          ▼                                ▼
    ┌─────────────────────────────────────────────┐
    │            Canton Ledger                     │
    │                                              │
    │  Pool Contract:                              │
    │  - Package: 5ce4bf9f...                      │
    │  - Owner: app-provider::1220414f85e7...      │
    │                                              │
    │  Backend Query:                              │
    │  - Looking for: 233b8f34... ❌ MISMATCH      │
    │  - Party: "app-provider" ❌ INCOMPLETE       │
    │                                              │
    │  Result: NO POOLS FOUND []                   │
    └──────────────────────────────────────────────┘

PROBLÈMES:
1. Package Hash Mismatch (5ce4bf9f ≠ 233b8f34)
2. Party ID Incomplet ("app-provider" ≠ full ID)
```

## ✅ APRÈS (La Solution)

```
┌─────────────────────┐         ┌─────────────────────┐
│  Frozen DAR         │         │    Backend API      │
│                     │         │                     │
│ Fixed Hash:         │ ◀─────▶ │ Uses same DAR:      │
│ 5ce4bf9f...        │         │ Hash: 5ce4bf9f... ✅│
│                     │         │                     │
│ Creates Pool with:  │         │ APP_PROVIDER_PARTY= │
│ - Hash: 5ce4bf9f    │         │ "app-provider::1220 │
│ - Party: app-prov.. │         │  414f85e7..." ✅    │
└─────────┬───────────┘         └──────────┬──────────┘
          │                                │
          ▼                                ▼
    ┌─────────────────────────────────────────────┐
    │            Canton Ledger                     │
    │                                              │
    │  Pool Contract:                              │
    │  - Package: 5ce4bf9f... ✅                   │
    │  - Owner: app-provider::1220414f85e7... ✅   │
    │                                              │
    │  Backend Query:                              │
    │  - Looking for: 5ce4bf9f... ✅ MATCH         │
    │  - Party: app-provider::1220414f85e7... ✅   │
    │                                              │
    │  Result: POOLS FOUND! ✅                     │
    │  [{"poolId": "ETH-USDC-01", ...}]           │
    └──────────────────────────────────────────────┘

SOLUTIONS:
1. Frozen Artifact = Hash Fixe (5ce4bf9f)
2. Full Party ID = Résolution Correcte
```

## 🔄 FLUX DE RÉSOLUTION DES PARTIES

```
                   PartyRegistryService
                          │
    "app-provider" ───────┼─────────▶ "app-provider::1220414f85e7..."
         (nom)            │                    (ID complet)
                          │
                   ┌──────┴──────┐
                   │   Cache      │
                   │             │
                   │ alice → ID   │
                   │ bob → ID     │
                   │ app-prov → ID│
                   └─────────────┘
                          ▲
                          │ Refresh toutes les 30s
                          │
                   Canton Ledger
```

## 📊 COMPARAISON DES CONFIGURATIONS

| Configuration | ❌ Incorrect | ✅ Correct |
|--------------|--------------|------------|
| **DAR File** | `.daml/dist/clearportx-amm-1.0.4.dar` | `artifacts/devnet/clearportx-amm-v1.0.4-frozen.dar` |
| **Package Hash** | Change à chaque build | Fixe: `5ce4bf9f...` |
| **APP_PROVIDER_PARTY** | `"app-provider"` | `"app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388"` |
| **API Response** | `[]` | `[{"poolId": "ETH-USDC-01", ...}]` |

## 🚨 SIGNAUX D'ALERTE

Si vous voyez ces logs, vous avez le problème:

```
# Hash mismatch
"Getting active contracts" ... "templateId":"Identifier(233b8f34..." 
"Fetched 0 active contracts for AMM.Pool:Pool"

# Party incomplet  
"party":"app-provider"  # Devrait être le full ID
```
