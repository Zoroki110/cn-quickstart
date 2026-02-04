#!/bin/bash
set -e

echo "=== DIRECT POOL CREATION WITH FULL LOGGING ==="

# Use the frozen DAR
DAR_PATH="artifacts/devnet/clearportx-amm-1887fb51.dar"

# Create a simple init script that will show us EVERYTHING
cat > daml/DirectPoolCreate.daml << 'DAML_EOF'
module DirectPoolCreate where

import Daml.Script
import DA.Time
import qualified Token.Token as T
import qualified AMM.Pool as P

directCreate : Script ()
directCreate = script do
  debug "=== STARTING POOL CREATION ==="

  -- Allocate unique parties (will fail if already exist, that's OK)
  debug "Step 1: Allocating parties..."
  poolOp <- allocateParty "DIRECT_OP"
  debug $ "  Created poolOp: " <> show poolOp

  poolParty <- allocateParty "DIRECT_POOL"
  debug $ "  Created poolParty: " <> show poolParty

  ethIss <- allocateParty "DIRECT_ETH"
  debug $ "  Created ethIss: " <> show ethIss

  usdcIss <- allocateParty "DIRECT_USDC"
  debug $ "  Created usdcIss: " <> show usdcIss

  lpIss <- allocateParty "DIRECT_LP"
  debug $ "  Created lpIss: " <> show lpIss

  clx <- allocateParty "DIRECT_CLX"
  debug $ "  Created clx: " <> show clx

  debug "Step 2: Creating ETH token..."
  ethCid <- submit ethIss $ createCmd T.Token with
    issuer = ethIss
    owner = poolParty
    symbol = "ETH"
    amount = 100.0
  debug $ "  ETH token created: " <> show ethCid

  debug "Step 3: Creating USDC token..."
  usdcCid <- submit usdcIss $ createCmd T.Token with
    issuer = usdcIss
    owner = poolParty
    symbol = "USDC"
    amount = 200000.0
  debug $ "  USDC token created: " <> show usdcCid

  debug "Step 4: Creating POOL with submitMulti..."
  poolCid <- submitMulti [poolParty, poolOp] [] $ createCmd P.Pool with
    poolOperator = poolOp
    poolParty = poolParty
    lpIssuer = lpIss
    issuerA = ethIss
    issuerB = usdcIss
    symbolA = "ETH"
    symbolB = "USDC"
    feeBps = 30
    poolId = "direct-eth-usdc"
    maxTTL = hours 24
    totalLPSupply = 0.0
    reserveA = 100.0
    reserveB = 200000.0
    tokenACid = Some ethCid
    tokenBCid = Some usdcCid
    protocolFeeReceiver = clx
    maxInBps = 10000
    maxOutBps = 5000

  debug $ "  *** POOL CREATED SUCCESSFULLY: " <> show poolCid <> " ***"

  debug "Step 5: Verifying pool visibility to poolOp..."
  pools <- query @P.Pool poolOp
  debug $ "  poolOp sees " <> show (length pools) <> " pools"

  debug "Step 6: Verifying pool visibility to poolParty..."
  poolsParty <- query @P.Pool poolParty
  debug $ "  poolParty sees " <> show (length poolsParty) <> " pools"

  debug "=== ✓✓✓ POOL CREATION COMPLETE AND VERIFIED ✓✓✓ ==="
  pure ()
DAML_EOF

echo "Building DAR with DirectPoolCreate script..."
daml build

echo ""
echo "Running DirectPoolCreate script with FULL OUTPUT..."
daml script \
  --dar .daml/dist/clearportx-amm-1.0.3.dar \
  --script-name DirectPoolCreate:directCreate \
  --ledger-host localhost \
  --ledger-port 5001 \
  2>&1 | tee /tmp/direct-pool-create.log

echo ""
echo "=== CHECKING RESULT ==="
if grep -q "POOL CREATED SUCCESSFULLY" /tmp/direct-pool-create.log; then
  echo "✓ Pool creation SUCCEEDED!"
  echo ""
  echo "Getting the operator party ID..."
  OPERATOR_PARTY=$(daml ledger list-parties --host localhost --port 5001 | grep "DIRECT_OP" | awk '{print $3}' | tr -d "'")
  echo "Operator party: $OPERATOR_PARTY"
  echo ""
  echo "This is the party your backend should use: APP_PROVIDER_PARTY=DIRECT_OP"
else
  echo "✗ Pool creation FAILED - check logs above"
  exit 1
fi
