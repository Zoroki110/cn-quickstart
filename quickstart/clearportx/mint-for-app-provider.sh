#!/bin/bash
# Mint fragmented tokens for app_provider party to test token merge

set -e

PARTY="app_provider_quickstart-root-1::12201300e204e8a38492e7df0ca7cf67ec3fe3355407903a72323fd72da9f368a45d"

echo "Minting fragmented tokens for app_provider party..."
echo "Party: ${PARTY}"
echo ""

# Mint 3 BTC contracts (10 each = 30 BTC total)
echo "Minting 3 BTC contracts (10 BTC each)..."
for i in {1..3}; do
  daml script \
    --dar .daml/dist/clearportx-amm-1.0.1.dar \
    --script-name Main:mintTokens \
    --input-file <(echo "{\"owner\": \"${PARTY}\", \"symbol\": \"BTC\", \"amount\": \"10.0\"}") \
    --ledger-host localhost \
    --ledger-port 3901 \
    --max-inbound-message-size 10000000 >/dev/null 2>&1
  echo "  ✓ Minted BTC contract $i/3"
done

# Mint 3 USDT contracts (500 each = 1500 USDT total)
echo "Minting 3 USDT contracts (500 USDT each)..."
for i in {1..3}; do
  daml script \
    --dar .daml/dist/clearportx-amm-1.0.1.dar \
    --script-name Main:mintTokens \
    --input-file <(echo "{\"owner\": \"${PARTY}\", \"symbol\": \"USDT\", \"amount\": \"500.0\"}") \
    --ledger-host localhost \
    --ledger-port 3901 \
    --max-inbound-message-size 10000000 >/dev/null 2>&1
  echo "  ✓ Minted USDT contract $i/3"
done

echo ""
echo "✓ Minting complete! Waiting 10s for sync..."
sleep 10

echo ""
echo "Checking tokens..."
curl -s "http://localhost:8080/api/tokens/${PARTY}" | jq -r '.[] | "\(.symbol): \(.amount)"' | sort
