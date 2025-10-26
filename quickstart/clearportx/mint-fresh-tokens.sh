#!/bin/bash
# Mint fresh tokens for Alice with the reset ledger

ALICE_PARTY="app_provider_quickstart-root-1::12201300e204e8a38492e7df0ca7cf67ec3fe3355407903a72323fd72da9f368a45d"

echo "🪙 Minting fresh tokens for Alice..."
echo "Party: $ALICE_PARTY"
echo ""

# Mint ETH
echo "1️⃣  Minting 1000 ETH..."
curl -X POST http://localhost:8080/api/tokens/mint \
  -H "Content-Type: application/json" \
  -d "{
    \"owner\": \"$ALICE_PARTY\",
    \"symbol\": \"ETH\",
    \"amount\": \"1000.0\"
  }" 2>/dev/null | python3 -m json.tool
echo ""

# Mint USDC
echo "2️⃣  Minting 100000 USDC..."
curl -X POST http://localhost:8080/api/tokens/mint \
  -H "Content-Type: application/json" \
  -d "{
    \"owner\": \"$ALICE_PARTY\",
    \"symbol\": \"USDC\",
    \"amount\": \"100000.0\"
  }" 2>/dev/null | python3 -m json.tool
echo ""

# Mint BTC
echo "3️⃣  Minting 10 BTC..."
curl -X POST http://localhost:8080/api/tokens/mint \
  -H "Content-Type: application/json" \
  -d "{
    \"owner\": \"$ALICE_PARTY\",
    \"symbol\": \"BTC\",
    \"amount\": \"10.0\"
  }" 2>/dev/null | python3 -m json.tool
echo ""

# Mint USDT
echo "4️⃣  Minting 50000 USDT..."
curl -X POST http://localhost:8080/api/tokens/mint \
  -H "Content-Type: application/json" \
  -d "{
    \"owner\": \"$ALICE_PARTY\",
    \"symbol\": \"USDT\",
    \"amount\": \"50000.0\"
  }" 2>/dev/null | python3 -m json.tool
echo ""

echo "✅ Done! Checking alice's tokens..."
echo ""
curl -s http://localhost:8080/api/tokens/$ALICE_PARTY | python3 -m json.tool | grep -E '"symbol"|"amount"' | head -20
echo ""
echo "🎉 Tokens minted successfully!"
echo ""
echo "Now refresh your browser and try a swap!"
