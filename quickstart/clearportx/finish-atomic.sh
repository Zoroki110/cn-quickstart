#!/bin/bash
set -e

cd /root/cn-quickstart/quickstart/clearportx

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
PARTY_OP="WIN_OP_${TIMESTAMP}"
PARTY_POOL="WIN_POOL_${TIMESTAMP}"
PARTY_ETH="WIN_ETH_${TIMESTAMP}"
PARTY_USDC="WIN_USDC_${TIMESTAMP}"
PARTY_LP="WIN_LP_${TIMESTAMP}"
PARTY_CLX="WIN_CLX_${TIMESTAMP}"

echo "===== FINAL ATOMIC PUSH ====="
echo "Timestamp: $TIMESTAMP"
echo "Parties: $PARTY_OP, $PARTY_POOL, etc."

# Create init script
cat > daml/WinInit.daml << 'EOFSCRIPT'
module WinInit where

import Daml.Script
import DA.Time
import qualified Token.Token as T
import qualified AMM.Pool as P

winInit : Script ()
winInit = script do
  poolOp <- allocateParty "WIN_OP_FINAL"
  poolParty <- allocateParty "WIN_POOL_FINAL"
  ethIss <- allocateParty "WIN_ETH_FINAL"
  usdcIss <- allocateParty "WIN_USDC_FINAL"
  lpIss <- allocateParty "WIN_LP_FINAL"
  clx <- allocateParty "WIN_CLX_FINAL"

  eth <- submit ethIss $ createCmd T.Token with
    issuer = ethIss
    owner = poolParty
    symbol = "ETH"
    amount = 100.0

  usdc <- submit usdcIss $ createCmd T.Token with
    issuer = usdcIss
    owner = poolParty
    symbol = "USDC"
    amount = 200000.0

  submitMulti [poolParty, poolOp] [] $ createCmd P.Pool with
    poolOperator = poolOp
    poolParty = poolParty
    lpIssuer = lpIss
    issuerA = ethIss
    issuerB = usdcIss
    symbolA = "ETH"
    symbolB = "USDC"
    feeBps = 30
    poolId = "eth-usdc-WIN"
    maxTTL = hours 24
    totalLPSupply = 0.0
    reserveA = 100.0
    reserveB = 200000.0
    tokenACid = Some eth
    tokenBCid = Some usdc
    protocolFeeReceiver = clx
    maxInBps = 10000
    maxOutBps = 5000

  debug "✓✓✓ WINNING POOL CREATED! ✓✓✓"
  pure ()
EOFSCRIPT

echo "Building DAR with WinInit..."
daml build

echo "Running WinInit script..."
daml script --dar .daml/dist/clearportx-amm-1.0.2.dar --script-name WinInit:winInit --ledger-host localhost --ledger-port 5001

echo "Getting operator party ID..."
FULL_PARTY_OP=$(daml ledger list-parties --host localhost --port 5001 | grep "WIN_OP_FINAL" | awk -F"'" '{print $2}')
echo "Operator: $FULL_PARTY_OP"

echo "Rebuilding backend..."
cd /root/cn-quickstart/quickstart
./gradlew :backend:clean :daml:build :backend:compileJava

echo "Starting backend..."
cd backend
SPRING_PROFILES_ACTIVE=devnet \
APP_PROVIDER_PARTY="WIN_OP_FINAL" \
LEDGER_API_HOST=localhost \
LEDGER_API_PORT=5001 \
BACKEND_PORT=8080 \
REGISTRY_BASE_URI=http://localhost:5012 \
PQS_JDBC_URL=jdbc:postgresql://localhost:5432/pqs \
PQS_USERNAME=pqs_user \
PQS_PASSWORD="" \
../gradlew bootRun > /tmp/backend-WIN.log 2>&1 &

echo "Waiting 35 seconds for backend startup..."
sleep 35

echo ""
echo "===== MOMENT OF TRUTH ====="
POOLS=$(curl -s "http://localhost:8080/api/pools")
echo "$POOLS" | jq '.'

if echo "$POOLS" | grep -q "WIN"; then
    echo ""
    echo "🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉"
    echo "   WE DID IT! POOL IS VISIBLE!"
    echo "🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉"
else
    echo "Still not visible. Checking logs..."
    tail -30 /tmp/backend-WIN.log | grep -E "templateId|Pool"
fi
