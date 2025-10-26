#!/bin/bash
# Script to mint test tokens for alice using the backend API

set -e

BACKEND_URL="http://localhost:8080"

echo "🪙 Minting tokens for alice..."
echo ""

# Mint 10 ETH for alice
echo "1️⃣  Minting 10.0 ETH..."
curl -X POST "$BACKEND_URL/api/clearportx/mint-demo" \
  -H 'Content-Type: application/json' \
  -d '{
    "owner": "alice",
    "symbol": "ETH",
    "amount": "10.0"
  }' | jq '.'
echo ""

# Mint 10000 USDC for alice
echo "2️⃣  Minting 10000.0 USDC..."
curl -X POST "$BACKEND_URL/api/clearportx/mint-demo" \
  -H 'Content-Type: application/json' \
  -d '{
    "owner": "alice",
    "symbol": "USDC",
    "amount": "10000.0"
  }' | jq '.'
echo ""

# Mint 1 WBTC for alice
echo "3️⃣  Minting 1.0 WBTC..."
curl -X POST "$BACKEND_URL/api/clearportx/mint-demo" \
  -H 'Content-Type: application/json' \
  -d '{
    "owner": "alice",
    "symbol": "WBTC",
    "amount": "1.0"
  }' | jq '.'
echo ""

# Mint 5000 USDT for alice
echo "4️⃣  Minting 5000.0 USDT..."
curl -X POST "$BACKEND_URL/api/clearportx/mint-demo" \
  -H 'Content-Type: application/json' \
  -d '{
    "owner": "alice",
    "symbol": "USDT",
    "amount": "5000.0"
  }' | jq '.'
echo ""

echo "✅ Done! Checking alice's tokens..."
echo ""

# Check alice's tokens
curl -s "$BACKEND_URL/api/tokens/alice" | jq '.'

echo ""
echo "🎉 Tokens minted successfully for alice!"
echo ""
echo "Now refresh your browser at http://localhost:4001"
echo "- Swap page should show alice's token balances"
echo "- Liquidity page should show available tokens"
