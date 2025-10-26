#!/bin/bash
set -e

echo "🎯 CREATING POOL WITH FROZEN DAR 1887fb51..."

# THE FROZEN DAR - DO NOT TOUCH OR REBUILD!
FROZEN_DAR="artifacts/devnet/clearportx-amm-1887fb51.dar"

# Create temp script file
cat > /tmp/create_pool_script.daml << 'EOF'
module CreatePoolScript where
import Daml.Script
import DA.Time (hours)
import qualified Token.Token as T
import qualified AMM.Pool as P

createPool : Script ()
createPool = script do
  poolOp <- allocateParty "BLOC3_OP"
  poolParty <- allocateParty "BLOC3_POOL"
  ethIss <- allocateParty "BLOC3_ETH"
  usdcIss <- allocateParty "BLOC3_USDC"
  lpIss <- allocateParty "BLOC3_LP"
  clx <- allocateParty "BLOC3_CLX"

  eth <- submit ethIss $ createCmd T.Token with
    issuer = ethIss; owner = poolParty; symbol = "ETH"; amount = 100.0

  usdc <- submit usdcIss $ createCmd T.Token with
    issuer = usdcIss; owner = poolParty; symbol = "USDC"; amount = 200000.0

  pool <- submitMulti [poolParty, poolOp] [] $ createCmd P.Pool with
    poolOperator = poolOp
    poolParty = poolParty
    lpIssuer = lpIss
    issuerA = ethIss
    issuerB = usdcIss
    symbolA = "ETH"
    symbolB = "USDC"
    feeBps = 30
    poolId = "bloc3-pool"
    maxTTL = hours 24
    totalLPSupply = 0.0
    reserveA = 100.0
    reserveB = 200000.0
    tokenACid = Some eth
    tokenBCid = Some usdc
    protocolFeeReceiver = clx
    maxInBps = 10000
    maxOutBps = 5000

  pools <- query @P.Pool poolOp
  debug $ "✓✓✓ SUCCESS! PoolOp sees " <> show (length pools) <> " pool(s)"
  pure ()
EOF

# Upload the frozen DAR (will skip if already there)
daml ledger upload-dar --host localhost --port 5001 --max-inbound-message-size 10000000 "$FROZEN_DAR" 2>&1 | grep -v "KNOWN_DAR_VERSION" || echo "DAR uploaded"

# Run the script
daml script --dar "$FROZEN_DAR" --script-name CreatePoolScript:createPool --ledger-host localhost --ledger-port 5001 --input-file /tmp/create_pool_script.daml

echo "✓ Pool creation script executed"
echo "Backend should use: APP_PROVIDER_PARTY=BLOC3_OP"
