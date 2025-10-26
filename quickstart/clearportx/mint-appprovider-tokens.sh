#!/bin/bash
# Script to mint test tokens for AppProvider (the actual party on Canton)

set -e

BACKEND_URL="http://localhost:8080"
OWNER="AppProvider"  # Use the real Canton party

echo "🪙 Minting tokens for $OWNER..."
echo ""

# Mint 10 ETH
echo "1️⃣  Minting 10.0 ETH..."
curl -X POST "$BACKEND_URL/api/clearportx/mint-demo" \
  -H 'Content-Type: application/json' \
  -d "{
    \"owner\": \"$OWNER\",
    \"symbol\": \"ETH\",
    \"amount\": \"10.0\"
  }" | jq '.'
echo ""

# Mint 10000 USDC
echo "2️⃣  Minting 10000.0 USDC..."
curl -X POST "$BACKEND_URL/api/clearportx/mint-demo" \
  -H 'Content-Type: application/json' \
  -d "{
    \"owner\": \"$OWNER\",
    \"symbol\": \"USDC\",
    \"amount\": \"10000.0\"
  }" | jq '.'
echo ""

# Mint 1 WBTC
echo "3️⃣  Minting 1.0 WBTC..."
curl -X POST "$BACKEND_URL/api/clearportx/mint-demo" \
  -H 'Content-Type: application/json' \
  -d "{
    \"owner\": \"$OWNER\",
    \"symbol\": \"WBTC\",
    \"amount\": \"1.0\"
  }" | jq '.'
echo ""

# Mint 5000 USDT
echo "4️⃣  Minting 5000.0 USDT..."
curl -X POST "$BACKEND_URL/api/clearportx/mint-demo" \
  -H 'Content-Type: application/json' \
  -d "{
    \"owner\": \"$OWNER\",
    \"symbol\": \"USDT\",
    \"amount\": \"5000.0\"
  }" | jq '.'
echo ""

echo "✅ Done! Checking $OWNER's tokens..."
echo ""

# Check tokens
curl -s "$BACKEND_URL/api/tokens/$OWNER" | jq '.'

echo ""
echo "🎉 Tokens minted successfully for $OWNER!"
echo ""
echo "📝 Note: OAuth login uses 'alice' but tokens are owned by 'AppProvider'"
echo "    You need to update the frontend to use 'AppProvider' instead of 'alice'"
echo ""
echo "To see tokens in frontend, update SwapInterface.tsx and LiquidityInterface.tsx:"
echo "    Change: backendApi.getTokens('alice')"
echo "    To:     backendApi.getTokens('AppProvider')"
