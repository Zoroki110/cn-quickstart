#!/bin/bash
set -e

echo "=== Testing Atomic Swap Endpoint ==="

# Get token
echo "1. Getting OAuth token..."
TOKEN=$(docker exec backend-service curl -s "http://keycloak:8082/realms/AppProvider/protocol/openid-connect/token" \
  -d "client_id=app-provider-unsafe" \
  -d "grant_type=password" \
  -d "username=alice" \
  -d "password=alicepass" 2>/dev/null | jq -r '.access_token // empty')

if [ -z "$TOKEN" ]; then
  echo "✗ Failed to get OAuth token"
  exit 1
fi

echo "✓ Token acquired"

# Test swap
echo ""
echo "2. Executing atomic swap..."
RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" -X POST "http://localhost:8080/api/swap/atomic" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "poolId": "ETH-USDC",
    "inputSymbol": "ETH",
    "outputSymbol": "USDC",
    "inputAmount": "0.01",
    "minOutput": "18.0",
    "maxPriceImpactBps": 1000
  }')

HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE:" | cut -d: -f2)
BODY=$(echo "$RESPONSE" | grep -v "HTTP_CODE:")

echo "HTTP Status: $HTTP_CODE"
echo "Response Body:"
echo "$BODY" | jq . 2>/dev/null || echo "$BODY"

# Check metrics
echo ""
echo "3. Checking metrics..."
METRIC_VALUE=$(curl -s http://localhost:8080/api/actuator/metrics/clearportx.swap.executed.total | jq -r '.measurements[0].value')
echo "Executed swaps count: $METRIC_VALUE"

