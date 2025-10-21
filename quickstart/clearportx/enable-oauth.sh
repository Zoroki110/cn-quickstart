#!/bin/bash
# Enable OAuth direct access grants

docker exec keycloak /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8082 --realm master --user admin --password admin 2>&1 > /dev/null

CLIENT_ID=$(docker exec keycloak /opt/keycloak/bin/kcadm.sh get clients -r AppProvider --fields id,clientId 2>&1 | grep -B1 '"clientId" : "app-provider-unsafe"' | grep '"id"' | cut -d'"' -f4)

echo "Client ID: $CLIENT_ID"

docker exec keycloak /opt/keycloak/bin/kcadm.sh update clients/$CLIENT_ID -r AppProvider -s directAccessGrantsEnabled=true 2>&1

echo "✓ Direct access grants enabled!"

# Test OAuth
echo ""
echo "Testing OAuth..."
TOKEN=$(curl -s "http://localhost:8082/realms/AppProvider/protocol/openid-connect/token" \
  -d "client_id=app-provider-unsafe" \
  -d "grant_type=password" \
  -d "username=alice" \
  -d "password=alicepass" 2>&1 | jq -r '.access_token // empty')

if [ -n "$TOKEN" ]; then
  echo "✓ OAuth working! Token: ${TOKEN:0:50}..."
else
  echo "✗ OAuth failed"
fi
