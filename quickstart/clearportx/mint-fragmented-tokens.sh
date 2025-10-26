#!/bin/bash
# Mint fragmented tokens for Alice to test token merge functionality
# This creates multiple small token contracts to simulate fragmentation

set -e

ALICE_PARTY="122001f4e9f7d5cfe0ca993d87b18b9d50f3a79fe32d29f1eff55f766d21fda8c102::1220d3dbf61c24a83b9e27ae62bd55f98bfa948e7fb1bbb3f91c65e6e62b0f53dfeaf3"

echo "Minting fragmented tokens for Alice..."
echo "Party: ${ALICE_PARTY}"
echo ""

# Mint 5 BTC contracts (50 each = 250 BTC total)
for i in {1..5}; do
  echo "Minting BTC contract $i/5 (50 BTC)..."
  daml ledger allocate-party alice$i \
    --host localhost --port 3901 >/dev/null 2>&1 || true

  daml script \
    --dar .daml/dist/clearportx-amm-1.0.1.dar \
    --script-name Main:mintTokens \
    --input-file <(cat <<EOF
{
  "owner": "${ALICE_PARTY}",
  "symbol": "BTC",
  "amount": "50.0"
}
EOF
) \
    --ledger-host localhost \
    --ledger-port 3901 \
    --max-inbound-message-size 10000000 2>&1 | grep -E "SUCCESS|ERROR" || echo "  ✓ Minted 50 BTC"
done

echo ""

# Mint 5 USDT contracts (1000 each = 5000 USDT total)
for i in {1..5}; do
  echo "Minting USDT contract $i/5 (1000 USDT)..."
  daml script \
    --dar .daml/dist/clearportx-amm-1.0.1.dar \
    --script-name Main:mintTokens \
    --input-file <(cat <<EOF
{
  "owner": "${ALICE_PARTY}",
  "symbol": "USDT",
  "amount": "1000.0"
}
EOF
) \
    --ledger-host localhost \
    --ledger-port 3901 \
    --max-inbound-message-size 10000000 2>&1 | grep -E "SUCCESS|ERROR" || echo "  ✓ Minted 1000 USDT"
done

echo ""
echo "✓ Minting complete!"
echo ""
echo "Checking fragmentation..."
sleep 3
curl -s "http://localhost:8080/api/tokens/${ALICE_PARTY}" | jq -r '.[] | "\(.symbol): \(.amount)"' | sort
