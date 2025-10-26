#!/bin/bash
set -e

echo "🔧 Refreshing ETH-USDC pool canonicals by adding fresh liquidity..."
echo ""

# Use curl to add liquidity via the backend API
# This will merge fresh tokens into the pool and update its tokenACid/tokenBCid
RESPONSE=$(curl -s -X POST "http://localhost:8080/api/liquidity/add/direct" \
  -H "Content-Type: application/json" \
  -H "X-Party-Id: app_provider_quickstart-root-1::12201300e204e8a38492e7df0ca7cf67ec3fe3355407903a72323fd72da9f368a45d" \
  -d '{
    "poolId": "ETH-USDC",
    "amountA": "0.5",
    "amountB": "1500",
    "minLPTokens": "1",
    "slippageToleranceBps": 500
  }' 2>&1 || echo "API call failed")

echo "$RESPONSE"

if echo "$RESPONSE" | grep -q "lpTokenAmount"; then
    echo ""
    echo "✅ Pool canonicals refreshed successfully!"
    echo ""
    echo "You can now try swaps in the frontend."
    echo "The pool will now have fresh tokenACid/tokenBCid that are active."
else
    echo ""
    echo "⚠️ Liquidity add may have failed. Check the response above."
    echo ""
    echo "Alternative: Try adding liquidity manually via the frontend UI."
    echo "This will also refresh the pool's token CIDs."
fi
