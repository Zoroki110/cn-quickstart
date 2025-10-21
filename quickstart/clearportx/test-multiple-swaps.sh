#!/bin/bash
# Test multiple swaps and check all metrics

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
echo "Executing 5 test swaps..."

for i in {1..5}; do
  curl -s -X POST "http://localhost:8080/api/swap/atomic" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"poolId":"ETH-USDC","inputSymbol":"ETH","outputSymbol":"USDC","inputAmount":"0.01","minOutput":"18.0","maxPriceImpactBps":1000}' \
    | jq -r '"\(.inputSymbol) → \(.outputSymbol): \(.inputAmount) → \(.outputAmount)"'
  sleep 1
done

echo ""
echo "=== Checking Metrics ==="
curl -s http://localhost:8080/api/actuator/metrics/clearportx.swap.executed.total | jq '{name, count: .measurements[0].value}'

echo ""
echo "=== Prometheus Metrics Sample ==="
curl -s http://localhost:8080/api/actuator/prometheus | grep "^clearportx_swap" | head -15

