#!/bin/bash

echo "🔍 VÉRIFICATION DES DONNÉES RÉELLES CANTON"
echo "=========================================="
echo ""

# Vérifier que le backend est accessible
echo "1️⃣ Vérification du backend..."
BACKEND_STATUS=$(curl -s -o /dev/null -w "%{http_code}" https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools)

if [ "$BACKEND_STATUS" = "200" ]; then
    echo "✅ Backend accessible via ngrok"
else
    echo "❌ Backend non accessible (HTTP $BACKEND_STATUS)"
    exit 1
fi

# Récupérer les pools
echo ""
echo "2️⃣ Récupération des pools Canton..."
POOLS=$(curl -s https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools)
POOL_COUNT=$(echo "$POOLS" | jq '. | length')

echo "✅ Trouvé $POOL_COUNT pool(s)"
echo ""
echo "📊 Détails du premier pool:"
echo "$POOLS" | jq '.[0]' | jq '{
    "Pool ID": .poolId,
    "Paire": (.symbolA + "/" + .symbolB),
    "Réserves": {
        (.symbolA): .reserveA,
        (.symbolB): .reserveB
    },
    "Frais": (.feeRate | tonumber * 100 | tostring + "%")
}'

echo ""
echo "3️⃣ Test d'un utilisateur..."
USER_TOKENS=$(curl -s "https://nonexplicable-lacily-leesa.ngrok-free.dev/api/tokens/alice@clearportx" 2>/dev/null || echo "[]")
TOKEN_COUNT=$(echo "$USER_TOKENS" | jq '. | length' 2>/dev/null || echo "0")

if [ "$TOKEN_COUNT" -gt 0 ]; then
    echo "✅ Alice a $TOKEN_COUNT token(s)"
else
    echo "⚠️  Aucun token trouvé pour Alice (normal si pas encore créé)"
fi

echo ""
echo "✅ VÉRIFICATION COMPLÈTE!"
echo ""
echo "📋 PROCHAINES ÉTAPES:"
echo "1. Configurez Netlify avec: REACT_APP_BACKEND_API_URL=https://nonexplicable-lacily-leesa.ngrok-free.dev"
echo "2. Redéployez avec 'Clear cache and deploy'"
echo "3. Testez sur https://app.clearportx.com"
