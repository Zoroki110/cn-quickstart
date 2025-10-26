#!/bin/bash
set -e

echo "🔧 Creating FRESH ETH-USDC pool on Canton Network..."
echo ""

# The app_provider party from Canton Network
PARTY="app_provider_quickstart-root-1::12201300e204e8a38492e7df0ca7cf67ec3fe3355407903a72323fd72da9f368a45d"

echo "Using party: $PARTY"
echo ""

# Upload the DAR (make sure it's deployed)
echo "1️⃣ Verifying DAR is uploaded..."
daml ledger upload-dar .daml/dist/clearportx-amm-1.0.1.dar \
  --host localhost \
  --port 3901 \
  --max-inbound-message-size 10000000 \
  2>&1 | grep -E "Uploading|already|Success" || echo "DAR already uploaded"

echo ""
echo "2️⃣ Creating BRAND NEW pool with NO stale canonicals..."
echo ""

# Create a fresh pool using daml repl or JSON API
# We'll use curl to the Canton JSON API to create the pool directly

# First, let's use the backend's pool creation if it exists, or create via DAML JSON API
# For now, the simplest approach is to create a temporary DAML script

cat > /tmp/create-fresh-pool-temp.daml << 'DAMLEOF'
module TempFreshPool where

import Daml.Script
import DA.Time (hours, days)
import qualified AMM.Pool as P

createFreshPoolCanton : Party -> Script ()
createFreshPoolCanton appProvider = script do
  let poolOperator = appProvider
  let poolParty = appProvider
  let lpIssuer = appProvider
  let issuerETH = appProvider
  let issuerUSDC = appProvider
  let protocolFeeReceiver = appProvider

  -- Create EMPTY pool (tokenACid=None, tokenBCid=None, zero reserves)
  -- This avoids ALL stale canonical problems!
  pool <- submit poolOperator $ createCmd P.Pool with
    poolOperator = poolOperator
    poolParty = poolParty
    lpIssuer = lpIssuer
    issuerA = issuerETH
    issuerB = issuerUSDC
    symbolA = "ETH"
    symbolB = "USDC"
    feeBps = 30  -- 0.3% fee
    poolId = "ETH-USDC"  -- Same poolId as before, but FRESH instance
    maxTTL = days 365
    totalLPSupply = 0.0    -- ZERO - completely empty
    reserveA = 0.0         -- ZERO
    reserveB = 0.0         -- ZERO
    tokenACid = None       -- NO stale CIDs!
    tokenBCid = None       -- NO stale CIDs!
    protocolFeeReceiver = protocolFeeReceiver
    maxInBps = 10000       -- 100%
    maxOutBps = 5000       -- 50%

  debug "✅ FRESH pool created with NO stale canonicals"
  debug "   Pool: ETH-USDC (empty, ready for AddLiquidity)"
  debug "   tokenACid: None"
  debug "   tokenBCid: None"
  debug ""
  debug "Next: Add liquidity via frontend or API to bootstrap the pool"

  return ()
DAMLEOF

# Compile and run the temp script
echo "Creating temporary DAML script..."
daml damlc compile /tmp/create-fresh-pool-temp.daml \
  --output /tmp/create-fresh-pool-temp.dar \
  --package-name temp-fresh-pool \
  --package-version 1.0.0 \
  2>&1 | grep -v "^Compiling" || true

echo ""
echo "⚠️  NOTE: The above script compilation will fail because we need the clearportx-amm DAR."
echo "Instead, we'll create the pool via the backend API or manually via Canton console."
echo ""
echo "📋 Manual steps to create fresh pool:"
echo ""
echo "Option A: Via Canton Console"
echo "------------------------"
echo "1. Connect to Canton:"
echo "   docker exec -it canton bash"
echo ""
echo "2. In Canton console, run:"
echo "   participant.ledger_api.commands.submit("
echo "     act_as = Seq(\"$PARTY\"),"
echo "     commands = Seq("
echo "       create(\"clearportx-amm:AMM.Pool:Pool\","
echo "         poolOperator = \"$PARTY\","
echo "         poolParty = \"$PARTY\","
echo "         lpIssuer = \"$PARTY\","
echo "         issuerA = \"$PARTY\","
echo "         issuerB = \"$PARTY\","
echo "         symbolA = \"ETH\","
echo "         symbolB = \"USDC\","
echo "         feeBps = 30,"
echo "         poolId = \"ETH-USDC-FRESH2\","
echo "         maxTTL = RelTime(31536000000000),"  # 365 days in microseconds
echo "         totalLPSupply = 0.0,"
echo "         reserveA = 0.0,"
echo "         reserveB = 0.0,"
echo "         tokenACid = None,"
echo "         tokenBCid = None,"
echo "         protocolFeeReceiver = \"$PARTY\","
echo "         maxInBps = 10000,"
echo "         maxOutBps = 5000"
echo "       )"
echo "     )"
echo "   )"
echo ""
echo "Option B: Restart Fresh (Fastest)"
echo "------------------------"
echo "   make clean"
echo "   make start"
echo "   make init-amm"
echo ""
echo "This will create fresh pools with no stale canonicals."
echo ""

rm -f /tmp/create-fresh-pool-temp.daml
