#!/bin/bash
# Create a test user in Keycloak for swap testing
set -e

KEYCLOAK_URL="http://keycloak.localhost:8082"
REALM="AppUser"
USERNAME="${1:-alice}"
PASSWORD="${2:-alicepass}"

echo "========================================="
echo "Creating Keycloak Test User"
echo "========================================="

# Get admin token
echo "[INFO] Getting Keycloak admin token..."
ADMIN_TOKEN=$(curl -s -X POST -H "Host: keycloak.localhost" \
  "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" \
  -d "grant_type=password" \
  -d "username=admin" \
  -d "password=admin" | jq -r '.access_token')

if [ "$ADMIN_TOKEN" == "null" ] || [ -z "$ADMIN_TOKEN" ]; then
  echo "[ERROR] Failed to get admin token"
  exit 1
fi

echo "[SUCCESS] Admin token acquired"

# Create user
echo "[INFO] Creating user '$USERNAME' in realm '$REALM'..."
USER_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
  -H "Host: keycloak.localhost" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
  -d '{
    "username": "'"$USERNAME"'",
    "email": "'"$USERNAME"'@test.localhost",
    "firstName": "'"$USERNAME"'",
    "lastName": "Test User",
    "enabled": true,
    "credentials": [{
      "type": "password",
      "value": "'"$PASSWORD"'",
      "temporary": false
    }]
  }')

HTTP_CODE=$(echo "$USER_RESPONSE" | tail -n1)

if [ "$HTTP_CODE" == "201" ]; then
  echo "[SUCCESS] User '$USERNAME' created successfully"
elif [ "$HTTP_CODE" == "409" ]; then
  echo "[INFO] User '$USERNAME' already exists"
else
  echo "[ERROR] Failed to create user. HTTP code: $HTTP_CODE"
  echo "$USER_RESPONSE"
  exit 1
fi

# Test getting user token
echo "[INFO] Testing user login..."
USER_TOKEN=$(curl -s -X POST -H "Host: keycloak.localhost" \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -d "client_id=app-provider-backend-oidc" \
  -d "grant_type=password" \
  -d "username=$USERNAME" \
  -d "password=$PASSWORD" | jq -r '.access_token')

if [ "$USER_TOKEN" == "null" ] || [ -z "$USER_TOKEN" ]; then
  echo "[ERROR] Failed to get user token"
  exit 1
fi

echo "[SUCCESS] User can authenticate successfully!"
echo ""
echo "========================================="
echo "User Created Successfully!"
echo "========================================="
echo "Username: $USERNAME"
echo "Password: $PASSWORD"
echo "Realm: $REALM"
echo "Client ID: app-provider-backend-oidc"
echo ""
echo "You can now use this user for testing swaps."
echo "========================================="
