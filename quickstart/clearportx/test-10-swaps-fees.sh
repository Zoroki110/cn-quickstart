#!/bin/bash
# Test 10 swaps to verify fee collection

TOKEN=$(docker exec backend-service curl -s "http://keycloak:8082/realms/AppProvider/protocol/openid-connect/token" \
  -d "client_id=app-provider-unsafe" \
  -d "grant_type=password" \
  -d "username=alice" \
  -d "password=alicepass" 2>/dev/null | jq -r '.access_token // empty')

if [ -z "$TOKEN" ]; then
  echo "✗ Failed to get token"
  exit 1
fi

echo "✓ Token acquired"
echo ""
echo "🚀 Executing 10 swaps of 0.1 ETH each..."
echo "============================================================"

SUCCESS=0
FAILED=0

for i in {1..10}; do
  RESULT=$(curl -s -X POST "http://localhost:8080/api/swap/atomic" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "inputSymbol":"ETH",
      "outputSymbol":"USDC",
      "inputAmount":"0.1",
      "minOutput":"180.0",
      "maxPriceImpactBps":1000,
      "deadlineSeconds":300
    }')
  
  if echo "$RESULT" | jq -e '.receiptCid' >/dev/null 2>&1; then
    AMOUNT_IN=$(echo "$RESULT" | jq -r '.amountIn')
    AMOUNT_OUT=$(echo "$RESULT" | jq -r '.amountOut')
    echo "✅ Swap $i/10: $AMOUNT_IN ETH → $AMOUNT_OUT USDC"
    SUCCESS=$((SUCCESS + 1))
  else
    echo "❌ Swap $i/10 failed"
    FAILED=$((FAILED + 1))
  fi
  
  sleep 2
done

echo ""
echo "============================================================"
echo "📊 SUMMARY"
echo "============================================================"
echo "✅ Successful: $SUCCESS/10"
echo "❌ Failed: $FAILED/10"
echo ""

if [ $SUCCESS -gt 0 ]; then
  TOTAL_ETH=$(echo "$SUCCESS * 0.1" | bc)
  echo "💸 EXPECTED FEES:"
  echo "   Total ETH swapped: $TOTAL_ETH ETH"
  printf "   Total fee (0.3%%): %.6f ETH\n" $(echo "$TOTAL_ETH * 0.003" | bc -l)
  printf "   Protocol (25%%): %.6f ETH\n" $(echo "$TOTAL_ETH * 0.003 * 0.25" | bc -l)
  printf "   LP (75%%): %.6f ETH\n" $(echo "$TOTAL_ETH * 0.003 * 0.75" | bc -l)
fi

echo ""
echo "🔍 Check Grafana dashboard to verify fees incremented!"
echo "============================================================"
