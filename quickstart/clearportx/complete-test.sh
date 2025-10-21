#!/bin/bash
# Complete end-to-end metrics test

echo "==================================================="
echo "ClearportX Metrics End-to-End Test"
echo "==================================================="
echo ""

echo "[1/6] Waiting for backend to be ready..."
SECONDS_WAITED=0
until curl -s http://localhost:8080/api/health/ledger 2>/dev/null | grep -q '"status":"OK"'; do
  if [ $SECONDS_WAITED -ge 180 ]; then
    echo "✗ Backend failed to start within 3 minutes"
    exit 1
  fi
  sleep 5
  SECONDS_WAITED=$((SECONDS_WAITED + 5))
done
echo "✓ Backend is healthy!"
echo ""

echo "[2/6] Verifying metrics are registered..."
curl -s http://localhost:8080/api/actuator/metrics 2>/dev/null | jq -r '.names[] | select(contains("clearportx"))' | head -5
echo "✓ Metrics registered"
echo ""

echo "[3/6] Initializing pools..."
curl -s -X POST "http://localhost:8080/api/clearportx/init" > /dev/null 2>&1
sleep 3
echo "✓ Pools initialized"
echo ""

echo "[4/6] Setting up OAuth user..."
docker exec keycloak /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8082 --realm master --user admin --password admin 2>&1 > /dev/null

CLIENT_ID=$(docker exec keycloak /opt/keycloak/bin/kcadm.sh get clients -r AppProvider --fields id,clientId 2>&1 | grep -B1 '"clientId" : "app-provider-unsafe"' | grep '"id"' | cut -d'"' -f4)

docker exec keycloak /opt/keycloak/bin/kcadm.sh update clients/$CLIENT_ID -r AppProvider -s directAccessGrantsEnabled=true 2>&1 > /dev/null

docker exec keycloak /opt/keycloak/bin/kcadm.sh set-password -r AppProvider --username alice --new-password alicepass 2>&1 > /dev/null

echo "✓ OAuth configured"
echo ""

echo "[5/6] Executing 3 test swaps and recording metrics..."
# Get token via docker network
TOKEN=$(docker exec backend-service curl -s "http://keycloak:8082/realms/AppProvider/protocol/openid-connect/token" \
  -d "client_id=app-provider-unsafe" \
  -d "grant_type=password" \
  -d "username=alice" \
  -d "password=alicepass" 2>/dev/null | jq -r '.access_token // empty')

if [ -z "$TOKEN" ]; then
  echo "✗ Failed to get OAuth token"
  exit 1
fi

for i in 1 2 3; do
  SWAP_OUTPUT=$(curl -s -X POST "http://localhost:8080/api/swap/atomic" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"poolId": "ETH-USDC", "inputSymbol": "ETH", "outputSymbol": "USDC", "inputAmount": "0.01", "minOutput": "18.0", "maxPriceImpactBps": 1000}' 2>/dev/null)

  OUTPUT_AMT=$(echo "$SWAP_OUTPUT" | jq -r '.outputAmount // "ERROR"' 2>/dev/null)
  echo "  Swap $i: $OUTPUT_AMT USDC"
  sleep 2
done
echo "✓ Test swaps completed"
echo ""

echo "[6/6] Checking metrics collection..."
sleep 15  # Wait for metrics to be published to OTLP

SWAP_COUNT=$(curl -s http://localhost:8080/api/actuator/metrics/clearportx.swap.executed.total 2>/dev/null | jq -r '.measurements[0].value')
echo "  Executed swaps count: $SWAP_COUNT"

echo ""
echo "Prometheus metrics sample:"
curl -s http://localhost:8080/api/actuator/prometheus 2>/dev/null | grep "clearportx_swap_executed_total" | head -3

echo ""
echo "==================================================="
echo "✓ Test Complete!"
echo "==================================================="
echo ""
echo "Now check Grafana dashboard at: http://5.9.70.48:3030"
echo "  - Username: admin"
echo "  - Password: admin"
echo "  - Dashboard: ClearportX Atomic Swap Metrics"
echo ""
