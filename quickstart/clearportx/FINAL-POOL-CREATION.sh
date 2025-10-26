#!/bin/bash
set -e

echo "🎯 FINAL POOL CREATION - NO REBUILD ALLOWED!"
echo "=========================================="

# Use the FROZEN DAR - DO NOT REBUILD!
FROZEN_DAR="artifacts/devnet/clearportx-amm-1887fb51.dar"
EXPECTED_HASH="1887fb51d82eb9f8809b84f7d62f9b95aa554090c8ad6ee3a06ba2e12663f365"

echo "✓ Using frozen DAR: $FROZEN_DAR"
echo "✓ Expected package hash: $EXPECTED_HASH"

# Verify the DAR exists
if [ ! -f "$FROZEN_DAR" ]; then
  echo "❌ ERROR: Frozen DAR not found!"
  exit 1
fi

# Verify it's already uploaded
echo "Step 1: Verifying DAR is uploaded to DevNet..."
# Upload it (will skip if already there)
daml ledger upload-dar --host localhost --port 5001 --max-inbound-message-size 10000000 "$FROZEN_DAR" || echo "DAR already uploaded (OK)"

# Create new unique parties
TIMESTAMP=$(date +%s)
echo "Step 2: Creating pool with timestamp $TIMESTAMP..."

daml script --dar "$FROZEN_DAR" --ledger-host localhost --ledger-port 5001 <<'DAML_SCRIPT'
module TempScript where
import Daml.Script
import DA.Time
import qualified Token.Token as T
import qualified AMM.Pool as P

tempCreate : Script ()
tempCreate = script do
  poolOp <- allocateParty "WINNING_OP"
  poolParty <- allocateParty "WINNING_POOL"
  ethIss <- allocateParty "WINNING_ETH"
  usdcIss <- allocateParty "WINNING_USDC"
  lpIss <- allocateParty "WINNING_LP"
  clx <- allocateParty "WINNING_CLX"

  eth <- submit ethIss $ createCmd T.Token with
    issuer = ethIss; owner = poolParty; symbol = "ETH"; amount = 100.0

  usdc <- submit usdcIss $ createCmd T.Token with
    issuer = usdcIss; owner = poolParty; symbol = "USDC"; amount = 200000.0

  pool <- submitMulti [poolParty, poolOp] [] $ createCmd P.Pool with
    poolOperator = poolOp; poolParty = poolParty; lpIssuer = lpIss
    issuerA = ethIss; issuerB = usdcIss; symbolA = "ETH"; symbolB = "USDC"
    feeBps = 30; poolId = "winning-pool"; maxTTL = hours 24
    totalLPSupply = 0.0; reserveA = 100.0; reserveB = 200000.0
    tokenACid = Some eth; tokenBCid = Some usdc
    protocolFeeReceiver = clx; maxInBps = 10000; maxOutBps = 5000

  pools <- query @P.Pool poolOp
  debug $ "✓✓✓ POOL CREATED! poolOp sees " <> show (length pools) <> " pool(s)"
  pure ()
DAML_SCRIPT

echo ""
echo "Step 3: Getting operator party ID..."
OPERATOR_PARTY=$(daml ledger list-parties --host localhost --port 5001 | grep "WINNING_OP" | awk '{print $3}' | tr -d "'")
echo "✓ Operator party: $OPERATOR_PARTY"

echo ""
echo "🎉 POOL CREATED WITH FROZEN DAR!"
echo "=========================================="
echo "Backend should use: APP_PROVIDER_PARTY=WINNING_OP"
echo "Package hash: $EXPECTED_HASH"
