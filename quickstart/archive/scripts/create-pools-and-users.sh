#!/bin/bash
set -e

echo "==================================================="
echo "  Create Pools & Give Tokens to Alice and Bob"
echo "==================================================="
echo ""

# Configuration
PARTICIPANT="localhost"
PORT="5001"
APP_PROVIDER="app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388"

# Check if Alice and Bob exist
echo "[1/4] Checking if Alice and Bob parties exist..."
ALICE_CHECK=$(daml ledger list-parties --host $PARTICIPANT --port $PORT 2>/dev/null | grep -i "alice" || echo "")
BOB_CHECK=$(daml ledger list-parties --host $PARTICIPANT --port $PORT 2>/dev/null | grep -i "bob" || echo "")

if [ -z "$ALICE_CHECK" ]; then
    echo "  Creating Alice party..."
    daml ledger allocate-party Alice --host $PARTICIPANT --port $PORT
fi

if [ -z "$BOB_CHECK" ]; then
    echo "  Creating Bob party..."
    daml ledger allocate-party Bob --host $PARTICIPANT --port $PORT
fi

echo "✅ Alice and Bob parties exist"
echo ""

echo "[2/4] Creating new pools via backend API..."
echo ""

# Pool 2: CANTON-USDC (ratio 1:2000)
echo "  Creating CANTON-USDC pool..."
curl -s -X POST http://localhost:8080/api/pool/create \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER" \
  -d '{
    "poolId": "CANTON-USDC-01",
    "symbolA": "CANTON",
    "symbolB": "USDC",
    "amountA": "100.0000000000",
    "amountB": "200000.0000000000",
    "feeBps": 30
  }' 2>&1 | head -5 || echo "  (Pool might already exist or endpoint not available)"

# Pool 3: CANTON-CBTC (ratio 1:0.05)
echo "  Creating CANTON-CBTC pool..."
curl -s -X POST http://localhost:8080/api/pool/create \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER" \
  -d '{
    "poolId": "CANTON-CBTC-01",
    "symbolA": "CANTON",
    "symbolB": "CBTC",
    "amountA": "100.0000000000",
    "amountB": "5.0000000000",
    "feeBps": 30
  }' 2>&1 | head -5 || echo "  (Pool might already exist or endpoint not available)"

# Pool 4: CBTC-USDC (ratio 1:40000)
echo "  Creating CBTC-USDC pool..."
curl -s -X POST http://localhost:8080/api/pool/create \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER" \
  -d '{
    "poolId": "CBTC-USDC-01",
    "symbolA": "CBTC",
    "symbolB": "USDC",
    "amountA": "5.0000000000",
    "amountB": "200000.0000000000",
    "feeBps": 30
  }' 2>&1 | head -5 || echo "  (Pool might already exist or endpoint not available)"

echo "✅ Pools creation requested"
echo ""

echo "[3/4] Minting tokens for Alice via backend API..."
echo ""

# Get Alice party ID
ALICE_ID=$(daml ledger list-parties --host $PARTICIPANT --port $PORT 2>/dev/null | grep -i "alice" | awk '{print $1}' | head -1)
echo "  Alice party: $ALICE_ID"

# Mint tokens for Alice (if mint endpoint exists)
# Alice gets: 10 ETH, 50000 USDC, 20 CANTON, 0.5 CBTC
curl -s -X POST http://localhost:8080/api/token/mint \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER" \
  -d "{
    \"owner\": \"$ALICE_ID\",
    \"symbol\": \"ETH\",
    \"amount\": \"10.0000000000\"
  }" 2>&1 | head -3 || echo "  (Mint endpoint might not exist yet)"

curl -s -X POST http://localhost:8080/api/token/mint \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER" \
  -d "{
    \"owner\": \"$ALICE_ID\",
    \"symbol\": \"USDC\",
    \"amount\": \"50000.0000000000\"
  }" 2>&1 | head -3 || echo "  (Mint endpoint might not exist yet)"

curl -s -X POST http://localhost:8080/api/token/mint \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER" \
  -d "{
    \"owner\": \"$ALICE_ID\",
    \"symbol\": \"CANTON\",
    \"amount\": \"20.0000000000\"
  }" 2>&1 | head -3 || echo "  (Mint endpoint might not exist yet)"

curl -s -X POST http://localhost:8080/api/token/mint \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER" \
  -d "{
    \"owner\": \"$ALICE_ID\",
    \"symbol\": \"CBTC\",
    \"amount\": \"0.5000000000\"
  }" 2>&1 | head -3 || echo "  (Mint endpoint might not exist yet)"

echo "✅ Alice tokens minting requested"
echo ""

echo "[4/4] Minting tokens for Bob via backend API..."
echo ""

# Get Bob party ID
BOB_ID=$(daml ledger list-parties --host $PARTICIPANT --port $PORT 2>/dev/null | grep -i "bob" | awk '{print $1}' | head -1)
echo "  Bob party: $BOB_ID"

# Mint tokens for Bob (if mint endpoint exists)
# Bob gets: 15 ETH, 30000 USDC, 15 CANTON, 0.3 CBTC
curl -s -X POST http://localhost:8080/api/token/mint \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER" \
  -d "{
    \"owner\": \"$BOB_ID\",
    \"symbol\": \"ETH\",
    \"amount\": \"15.0000000000\"
  }" 2>&1 | head -3 || echo "  (Mint endpoint might not exist yet)"

curl -s -X POST http://localhost:8080/api/token/mint \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER" \
  -d "{
    \"owner\": \"$BOB_ID\",
    \"symbol\": \"USDC\",
    \"amount\": \"30000.0000000000\"
  }" 2>&1 | head -3 || echo "  (Mint endpoint might not exist yet)"

curl -s -X POST http://localhost:8080/api/token/mint \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER" \
  -d "{
    \"owner\": \"$BOB_ID\",
    \"symbol\": \"CANTON\",
    \"amount\": \"15.0000000000\"
  }" 2>&1 | head -3 || echo "  (Mint endpoint might not exist yet)"

curl -s -X POST http://localhost:8080/api/token/mint \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER" \
  -d "{
    \"owner\": \"$BOB_ID\",
    \"symbol\": \"CBTC\",
    \"amount\": \"0.3000000000\"
  }" 2>&1 | head -3 || echo "  (Mint endpoint might not exist yet)"

echo "✅ Bob tokens minting requested"
echo ""

echo "==================================================="
echo "  Setup Complete!"
echo "==================================================="
echo ""
echo "Pools (visible to all without login):"
echo "  1. ETH-USDC-01    (100 ETH / 200000 USDC)"
echo "  2. CANTON-USDC-01 (100 CANTON / 200000 USDC)"
echo "  3. CANTON-CBTC-01 (100 CANTON / 5 CBTC)"
echo "  4. CBTC-USDC-01   (5 CBTC / 200000 USDC)"
echo ""
echo "Users with tokens:"
echo "  Alice: $ALICE_ID"
echo "    - 10 ETH, 50000 USDC, 20 CANTON, 0.5 CBTC"
echo ""
echo "  Bob: $BOB_ID"
echo "    - 15 ETH, 30000 USDC, 15 CANTON, 0.3 CBTC"
echo ""
echo "Next steps:"
echo "  - Verify pools: curl http://localhost:8080/api/pools"
echo "  - Verify Alice tokens: curl http://localhost:8080/api/tokens/$ALICE_ID"
echo "  - Verify Bob tokens: curl http://localhost:8080/api/tokens/$BOB_ID"
echo "  - Test swap on frontend: https://clearportx.netlify.app/swap"
echo ""
