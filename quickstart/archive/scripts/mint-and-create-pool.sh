#!/bin/bash
set -e

echo "🔧 Creating FRESH pool with brand new tokens (no stale CIDs)"
echo ""

# Party from Canton Network
PARTY="app_provider_quickstart-root-1::12201300e204e8a38492e7df0ca7cf67ec3fe3355407903a72323fd72da9f368a45d"

# Step 1: Mint fresh tokens for the pool
echo "1️⃣ Minting fresh ETH and USDC tokens..."

cat > /tmp/mint-fresh-pool-tokens.daml << 'EOF'
module MintFreshPoolTokens where

import Daml.Script
import qualified Token.Token as T
import DA.Time (days)
import qualified AMM.Pool as P

-- Mint fresh tokens and create empty pool
mintAndCreateFreshPool : Party -> Script ()
mintAndCreateFreshPool appProvider = script do
  debug "🪙 Minting fresh tokens for new pool..."

  -- Mint fresh ETH token (owned by pool party)
  ethToken <- submit appProvider $ createCmd T.Token with
    issuer = appProvider
    owner = appProvider  -- Pool party owns initial liquidity
    symbol = "ETH"
    amount = 100.0

  debug "✅ Minted 100 ETH"

  -- Mint fresh USDC token (owned by pool party)
  usdcToken <- submit appProvider $ createCmd T.Token with
    issuer = appProvider
    owner = appProvider
    symbol = "USDC"
    amount = 300000.0

  debug "✅ Minted 300,000 USDC"

  -- Create EMPTY pool (will add liquidity next)
  pool <- submit appProvider $ createCmd P.Pool with
    poolOperator = appProvider
    poolParty = appProvider
    lpIssuer = appProvider
    issuerA = appProvider
    issuerB = appProvider
    symbolA = "ETH"
    symbolB = "USDC"
    feeBps = 30  -- 0.3% fee
    poolId = "ETH-USDC"
    maxTTL = days 365
    totalLPSupply = 0.0    -- Empty pool
    reserveA = 0.0
    reserveB = 0.0
    tokenACid = None       -- Will be set by AddLiquidity
    tokenBCid = None
    protocolFeeReceiver = appProvider
    maxInBps = 10000
    maxOutBps = 5000

  debug "✅ Created EMPTY pool: ETH-USDC"
  debug "   Status: Ready for AddLiquidity"
  debug "   tokenACid: None (will be set on first add)"
  debug "   tokenBCid: None"

  -- Now add the initial liquidity to set fresh canonicals
  now <- getTime
  (lpToken, poolWithLiquidity) <- submitMulti [appProvider, appProvider, appProvider] [] $
    exerciseCmd pool P.AddLiquidity with
      provider = appProvider
      tokenACid = ethToken
      tokenBCid = usdcToken
      amountA = 100.0
      amountB = 300000.0
      minLPTokens = 0.0
      deadline = addRelTime now (days 1)

  debug "✅ Pool bootstrapped with FRESH liquidity!"
  debug "   100 ETH + 300,000 USDC"
  debug "   Price: 1 ETH = 3,000 USDC"
  debug "   Pool now has ACTIVE token canonicals (no stale CIDs!)"
  debug ""
  debug "🎉 FRESH POOL READY FOR SWAPS!"

  return ()
EOF

# Run the script
echo "2️⃣ Running DAML script to create pool..."
echo ""

daml script \
  --dar .daml/dist/clearportx-amm-1.0.1.dar \
  --script-name MintFreshPoolTokens:mintAndCreateFreshPool \
  --input-file <(echo "\"$PARTY\"") \
  --ledger-host localhost \
  --ledger-port 3901

echo ""
echo "✅ Done! Check the output above for success."
echo ""
echo "Next steps:"
echo "1. Verify pool exists: curl -s http://localhost:8080/api/pools | jq '.[] | select(.poolId==\"ETH-USDC\")'"
echo "2. Try a swap in the frontend: 0.1 ETH → USDC"
echo ""

# Cleanup
rm -f /tmp/mint-fresh-pool-tokens.daml
