#!/bin/bash
# Script to mint test tokens for the REAL Canton party ID

set -e

BACKEND_URL="http://localhost:8080"
PARTY_ID="app_provider_quickstart-root-1::12201300e204e8a38492e7df0ca7cf67ec3fe3355407903a72323fd72da9f368a45d"

echo "🪙 Minting tokens for Canton party..."
echo "Party ID: $PARTY_ID"
echo ""

# Mint 10 ETH
echo "1️⃣  Minting 10.0 ETH..."
curl -s -X POST "$BACKEND_URL/api/clearportx/mint-demo" \
  -H 'Content-Type: application/json' \
  -d "{
    \"owner\": \"$PARTY_ID\",
    \"symbol\": \"ETH\",
    \"amount\": \"10.0\"
  }" | jq -r 'if .status == "SUCCESS" then "✅ Minted \(.amount) \(.symbol) - CID: \(.tokenCid[0:20])..." else "❌ Failed: \(.error)" end'

# Mint 10000 USDC
echo "2️⃣  Minting 10000.0 USDC..."
curl -s -X POST "$BACKEND_URL/api/clearportx/mint-demo" \
  -H 'Content-Type: application/json' \
  -d "{
    \"owner\": \"$PARTY_ID\",
    \"symbol\": \"USDC\",
    \"amount\": \"10000.0\"
  }" | jq -r 'if .status == "SUCCESS" then "✅ Minted \(.amount) \(.symbol) - CID: \(.tokenCid[0:20])..." else "❌ Failed: \(.error)" end'

# Mint 1 WBTC
echo "3️⃣  Minting 1.0 WBTC..."
curl -s -X POST "$BACKEND_URL/api/clearportx/mint-demo" \
  -H 'Content-Type: application/json' \
  -d "{
    \"owner\": \"$PARTY_ID\",
    \"symbol\": \"WBTC\",
    \"amount\": \"1.0\"
  }" | jq -r 'if .status == "SUCCESS" then "✅ Minted \(.amount) \(.symbol) - CID: \(.tokenCid[0:20])..." else "❌ Failed: \(.error)" end'

# Mint 5000 USDT
echo "4️⃣  Minting 5000.0 USDT..."
curl -s -X POST "$BACKEND_URL/api/clearportx/mint-demo" \
  -H 'Content-Type: application/json' \
  -d "{
    \"owner\": \"$PARTY_ID\",
    \"symbol\": \"USDT\",
    \"amount\": \"5000.0\"
  }" | jq -r 'if .status == "SUCCESS" then "✅ Minted \(.amount) \(.symbol) - CID: \(.tokenCid[0:20])..." else "❌ Failed: \(.error)" end'

echo ""
echo "5️⃣  Checking tokens for party..."
TOKEN_COUNT=$(curl -s "$BACKEND_URL/api/tokens/$PARTY_ID" | jq '. | length')
echo "✅ Party now has $TOKEN_COUNT tokens"

echo ""
echo "6️⃣  Token details:"
curl -s "$BACKEND_URL/api/tokens/$PARTY_ID" | jq -r '.[] | "  - \(.symbol): \(.amount)"'

echo ""
echo "🎉 Token minting complete!"
echo ""
echo "📝 Frontend mapping: 'alice' → Real Canton Party ID"
echo "   When you use 'alice' in the UI, it will automatically use this party"
echo ""
echo "Next steps:"
echo "  1. Restart SSH tunnel on your local machine"
echo "  2. Refresh browser at http://localhost:4001"
echo "  3. Go to Swap page - you should see alice's tokens!"
echo "  4. Try executing a swap: 0.1 ETH → USDC"
