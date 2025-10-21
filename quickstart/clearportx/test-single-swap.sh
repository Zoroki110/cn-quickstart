#!/bin/bash
# Test single swap with full output

TOKEN=$(docker exec backend-service curl -s "http://keycloak:8082/realms/AppProvider/protocol/openid-connect/token" \
  -d "client_id=app-provider-unsafe" \
  -d "grant_type=password" \
  -d "username=alice" \
  -d "password=alicepass" 2>/dev/null | jq -r '.access_token // empty')

if [ -z "$TOKEN" ]; then
  echo "✗ Failed to get token"
  exit 1
fi

echo "✓ Token acquired: ${TOKEN:0:30}..."
echo ""
echo "Testing single swap..."
curl -s -X POST "http://localhost:8080/api/swap/atomic" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"poolId": "ETH-USDC", "inputSymbol": "ETH", "outputSymbol": "USDC", "inputAmount": "0.01", "minOutput": "18.0", "maxPriceImpactBps": 1000}' | jq .
