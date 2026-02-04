#!/bin/bash
set -e  # Exit on any error

echo "================================"
echo "ATOMIC POOL SETUP FOR DEVNET"
echo "================================"

cd /root/cn-quickstart/quickstart/clearportx

# Step 1: Clean and build DAR ONCE
echo "Step 1: Building DAR..."
daml clean
daml build

# Step 2: Record package hash
echo "Step 2: Recording package hash..."
PKG_HASH=$(jar tf .daml/dist/clearportx-amm-1.0.2.dar | grep "\.dalf$" | head -1 | xargs -I {} sh -c 'jar xf .daml/dist/clearportx-amm-1.0.2.dar {}; sha256sum {} | cut -d" " -f1')
echo "Package hash: $PKG_HASH"
mkdir -p artifacts/devnet
echo "$PKG_HASH" > artifacts/devnet/PACKAGE_ID.txt
cp .daml/dist/clearportx-amm-1.0.2.dar artifacts/devnet/clearportx-amm-${PKG_HASH:0:8}.dar

# Step 3: Upload DAR to DevNet
echo "Step 3: Uploading DAR to DevNet..."
daml ledger upload-dar --host localhost --port 5001 --max-inbound-message-size 10000000 .daml/dist/clearportx-amm-1.0.2.dar

# Step 4: Create unique timestamped parties
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
PARTY_OP="ATOMIC_OP_${TIMESTAMP}"
PARTY_POOL="ATOMIC_POOL_${TIMESTAMP}"
PARTY_ETH="ATOMIC_ETH_${TIMESTAMP}"
PARTY_USDC="ATOMIC_USDC_${TIMESTAMP}"
PARTY_LP="ATOMIC_LP_${TIMESTAMP}"
PARTY_CLX="ATOMIC_CLX_${TIMESTAMP}"

echo "Step 4: Creating parties with timestamp $TIMESTAMP..."
echo "  - $PARTY_OP"
echo "  - $PARTY_POOL"
echo "  - $PARTY_ETH"
echo "  - $PARTY_USDC"
echo "  - $PARTY_LP"
echo "  - $PARTY_CLX"

# Step 5: Create inline init script
cat > /tmp/atomic_init.daml << EOF
module AtomicInit where

import Daml.Script
import DA.Time
import qualified Token.Token as T
import qualified AMM.Pool as P

atomicInit : Script ()
atomicInit = script do
  poolOp <- allocateParty "${PARTY_OP}"
  poolParty <- allocateParty "${PARTY_POOL}"
  ethIss <- allocateParty "${PARTY_ETH}"
  usdcIss <- allocateParty "${PARTY_USDC}"
  lpIss <- allocateParty "${PARTY_LP}"
  clx <- allocateParty "${PARTY_CLX}"

  eth <- submit ethIss \$ createCmd T.Token with
    issuer = ethIss
    owner = poolParty
    symbol = "ETH"
    amount = 100.0

  usdc <- submit usdcIss \$ createCmd T.Token with
    issuer = usdcIss
    owner = poolParty
    symbol = "USDC"
    amount = 200000.0

  submitMulti [poolParty, poolOp] [] \$ createCmd P.Pool with
    poolOperator = poolOp
    poolParty = poolParty
    lpIssuer = lpIss
    issuerA = ethIss
    issuerB = usdcIss
    symbolA = "ETH"
    symbolB = "USDC"
    feeBps = 30
    poolId = "eth-usdc-atomic-${TIMESTAMP}"
    maxTTL = hours 24
    totalLPSupply = 0.0
    reserveA = 100.0
    reserveB = 200000.0
    tokenACid = Some eth
    tokenBCid = Some usdc
    protocolFeeReceiver = clx
    maxInBps = 10000
    maxOutBps = 5000

  debug "✓ Pool created atomically!"
  pure ()
EOF

# Copy the inline script to daml directory
cp /tmp/atomic_init.daml daml/AtomicInit.daml

# Rebuild DAR with the new script (same package hash should be maintained)
echo "Step 5: Rebuilding DAR with init script..."
daml build

# Step 6: Run the init script
echo "Step 6: Running pool initialization..."
daml script --dar .daml/dist/clearportx-amm-1.0.2.dar --script-name AtomicInit:atomicInit --ledger-host localhost --ledger-port 5001

# Step 7: Get the full party ID for the operator
FULL_PARTY_OP=$(daml ledger list-parties --host localhost --port 5001 | grep "$PARTY_OP" | awk -F"'" '{print $2}')
echo "Step 7: Operator party: $FULL_PARTY_OP"
echo "$FULL_PARTY_OP" > artifacts/devnet/OPERATOR_PARTY.txt

# Step 8: Rebuild backend with matching DAR
echo "Step 8: Rebuilding backend..."
cd /root/cn-quickstart/quickstart
./gradlew :backend:clean :daml:build :backend:compileJava

# Step 9: Start backend
echo "Step 9: Starting backend with operator $PARTY_OP..."
cd backend
SPRING_PROFILES_ACTIVE=devnet \
APP_PROVIDER_PARTY="$PARTY_OP" \
LEDGER_API_HOST=localhost \
LEDGER_API_PORT=5001 \
BACKEND_PORT=8080 \
REGISTRY_BASE_URI=http://localhost:5012 \
PQS_JDBC_URL=jdbc:postgresql://localhost:5432/pqs \
PQS_USERNAME=pqs_user \
PQS_PASSWORD="" \
../gradlew bootRun > /tmp/backend-atomic.log 2>&1 &

BACKEND_PID=$!
echo "Backend started with PID $BACKEND_PID"

# Step 10: Wait for backend startup
echo "Step 10: Waiting for backend to start (30s)..."
sleep 30

# Step 11: Test pool visibility
echo "Step 11: Testing pool visibility..."
POOLS=$(curl -s "http://localhost:8080/api/pools")
echo "Pools response: $POOLS"

if echo "$POOLS" | grep -q "eth-usdc-atomic"; then
    echo ""
    echo "================================"
    echo "✓✓✓ SUCCESS! POOL IS VISIBLE! ✓✓✓"
    echo "================================"
    echo ""
    echo "Pool details:"
    echo "$POOLS" | jq '.'
else
    echo ""
    echo "❌ Pool not visible yet. Checking logs..."
    tail -50 /tmp/backend-atomic.log | grep -E "Getting active contracts|templateId.*Pool"
    exit 1
fi

echo ""
echo "Setup complete! Ready for swaps."
echo "Operator party: $PARTY_OP"
echo "Package hash: $PKG_HASH"
