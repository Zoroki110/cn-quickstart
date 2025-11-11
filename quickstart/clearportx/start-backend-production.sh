#!/bin/bash
# Production startup script for ClearportX Backend with proper party resolution
# Created: October 25, 2025
# This script ensures pools are visible in the API

set -e
set -o pipefail

echo "=============================================="
echo "   ClearportX Backend Production Startup"
echo "=============================================="

# Configuration
LEDGER_HOST="${CANTON_LEDGER_HOST:-localhost}"
LEDGER_PORT="${CANTON_LEDGER_PORT:-5001}"
BACKEND_PORT="${BACKEND_PORT:-8080}"
SERVER_BIND_ADDRESS="${SERVER_BIND_ADDRESS:-0.0.0.0}"
# Determine Spring profiles. Default to oauth2,debug to keep debug endpoints available while OAuth2 is active.
# If SPRING_PROFILES_ACTIVE is provided by the caller, respect it, but ensure 'oauth2' is included so TokenProvider beans are available.
ENVIRONMENT="${SPRING_PROFILES_ACTIVE:-oauth2,debug}"
case ",$ENVIRONMENT," in
  *",oauth2,"*) ;; # already present
  *) ENVIRONMENT="oauth2,$ENVIRONMENT" ;;
esac

echo ""
echo "Configuration:"
echo "- Ledger: $LEDGER_HOST:$LEDGER_PORT"
echo "- Backend Port: $BACKEND_PORT"
echo "- Bind Address: $SERVER_BIND_ADDRESS"
echo "- Environment: $ENVIRONMENT"
echo ""

# Step 1: Clean up old processes
echo "[1/5] Cleaning up old processes..."
pkill -9 -f "gradlew.*bootRun" 2>/dev/null || true
pkill -9 -f "java.*$BACKEND_PORT" 2>/dev/null || true
sleep 2

# Step 2: Get the correct party ID
# Resolve app provider party only if not provided via env
echo "[2/5] Resolving app-provider party ID..."
if [ -n "$APP_PROVIDER_PARTY" ]; then
  FULL_PARTY_ID="$APP_PROVIDER_PARTY"
  echo "✅ Using APP_PROVIDER_PARTY from environment: $FULL_PARTY_ID"
else
  # Configure grpcurl authority for validator gateway on :8888
  GRPCURL_AUTH_FLAG=""
  if [ -n "$LEDGER_GRPC_AUTHORITY" ]; then
    GRPCURL_AUTH_FLAG="-authority $LEDGER_GRPC_AUTHORITY"
  elif [ "$LEDGER_PORT" = "8888" ]; then
    LEDGER_GRPC_AUTHORITY="${LEDGER_GRPC_AUTHORITY:-grpc-ledger-api.localhost}"
    GRPCURL_AUTH_FLAG="-authority $LEDGER_GRPC_AUTHORITY"
  fi
  # Try app-provider-4f1df03a:: first (DAML script party), fallback to app-provider::
  FULL_PARTY_ID=$(timeout 10s grpcurl $GRPCURL_AUTH_FLAG -plaintext \
    -d '{}' \
    $LEDGER_HOST:$LEDGER_PORT \
    com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties 2>/dev/null | \
    jq -r '.party_details[] | select(.party | startswith("app-provider-4f1df03a::")) | .party' | head -1)

  if [ -z "$FULL_PARTY_ID" ]; then
    # Fallback to app-provider:: if -4f1df03a:: not found
    FULL_PARTY_ID=$(timeout 10s grpcurl $GRPCURL_AUTH_FLAG -plaintext \
      -d '{}' \
      $LEDGER_HOST:$LEDGER_PORT \
      com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties 2>/dev/null | \
      jq -r '.party_details[] | select(.party | startswith("app-provider::")) | .party' | head -1)
  fi

  if [ -z "$FULL_PARTY_ID" ]; then
    echo "❌ ERROR: app-provider party not found on ledger and APP_PROVIDER_PARTY not set!"
    exit 1
  fi
  echo "✅ Found party: $FULL_PARTY_ID"
fi

# Step 3: Skip local DAR pin (runtime uses ledger-installed packages)
echo "[3/5] Skipping local DAR pinning (using ledger packages)"
cd /root/cn-quickstart/quickstart/clearportx

# Step 4: Set environment variables
echo "[4/5] Setting environment variables..."
export BACKEND_PORT="$BACKEND_PORT"
export APP_PROVIDER_PARTY="$FULL_PARTY_ID"  # CRITICAL: Must be full party ID!
export SPRING_PROFILES_ACTIVE="$ENVIRONMENT"
export CANTON_LEDGER_HOST="$LEDGER_HOST"
export CANTON_LEDGER_PORT="$LEDGER_PORT"
# Optional override for gRPC virtual host when using gateway on :8888
export LEDGER_GRPC_AUTHORITY="${LEDGER_GRPC_AUTHORITY}"
# Also export Spring-native env names so LedgerConfig picks them up under oauth2 profile
export LEDGER_HOST="$LEDGER_HOST"
export LEDGER_PORT="$LEDGER_PORT"
export REGISTRY_BASE_URI="${REGISTRY_BASE_URI:-http://localhost:8090}"
# Force issuer to keycloak.localhost to match Keycloak metadata (avoid localhost mismatch)
export AUTH_APP_PROVIDER_ISSUER_URL="http://keycloak.localhost:8082/realms/splice"
export AUTH_APP_PROVIDER_BACKEND_CLIENT_ID="${AUTH_APP_PROVIDER_BACKEND_CLIENT_ID:-validator-app}"
export AUTH_APP_PROVIDER_BACKEND_SECRET="${AUTH_APP_PROVIDER_BACKEND_SECRET:-JF53at3KPMCjsbvG99mXDP8OOlvR6dP8}"
export AUTH_APP_PROVIDER_BACKEND_OIDC_CLIENT_ID="${AUTH_APP_PROVIDER_BACKEND_OIDC_CLIENT_ID:-validator-app}"
export SPRING_APPLICATION_JSON='{"security":{"issuer-url":"'"$AUTH_APP_PROVIDER_ISSUER_URL"'"}}'
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="$AUTH_APP_PROVIDER_ISSUER_URL"
export SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI="$AUTH_APP_PROVIDER_ISSUER_URL"
export SPRING_CONFIG_ADDITIONAL_LOCATION="/tmp/clearportx-oauth2-override.yml"

# Log the configuration
cat > /tmp/backend-config.env << EOF
# ClearportX Backend Configuration - $(date)
BACKEND_PORT=$BACKEND_PORT
APP_PROVIDER_PARTY=$APP_PROVIDER_PARTY
SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE
CANTON_LEDGER_HOST=$CANTON_LEDGER_HOST
CANTON_LEDGER_PORT=$CANTON_LEDGER_PORT
REGISTRY_BASE_URI=$REGISTRY_BASE_URI
PACKAGE_HASH=$PACKAGE_HASH
AUTH_APP_PROVIDER_ISSUER_URL=$AUTH_APP_PROVIDER_ISSUER_URL
AUTH_APP_PROVIDER_BACKEND_CLIENT_ID=$AUTH_APP_PROVIDER_BACKEND_CLIENT_ID
AUTH_APP_PROVIDER_BACKEND_OIDC_CLIENT_ID=$AUTH_APP_PROVIDER_BACKEND_OIDC_CLIENT_ID
SPRING_APPLICATION_JSON=$SPRING_APPLICATION_JSON
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=$SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI=$SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI
SPRING_CONFIG_ADDITIONAL_LOCATION=$SPRING_CONFIG_ADDITIONAL_LOCATION
LEDGER_GRPC_AUTHORITY=$LEDGER_GRPC_AUTHORITY
EOF

echo "✅ Configuration saved to /tmp/backend-config.env"

# Step 5: Start backend
echo "[5/5] Starting backend..."
cd /root/cn-quickstart/quickstart/backend

# Write override config to avoid missing env placeholders
cat > "$SPRING_CONFIG_ADDITIONAL_LOCATION" << EOF
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: $AUTH_APP_PROVIDER_ISSUER_URL
      client:
        registration: { }
        provider:
          AppProvider:
            issuer-uri: $AUTH_APP_PROVIDER_ISSUER_URL
security:
  issuer-url: $AUTH_APP_PROVIDER_ISSUER_URL
EOF

# Create a startup verification script
cat > /tmp/verify-backend.sh << 'EOF'
#!/bin/bash
echo "Waiting for backend to start..."
for i in {1..30}; do
  if curl -s http://localhost:${BACKEND_PORT:-8080}/actuator/health >/dev/null 2>&1; then
    echo "✅ Backend is healthy!"
    
    # Test pool visibility
    POOLS=$(curl -s http://localhost:${BACKEND_PORT:-8080}/api/pools)
    if [ "$POOLS" != "[]" ] && [ -n "$POOLS" ]; then
      echo "✅ Pools are visible!"
      echo "$POOLS" | jq . | head -20
    else
      echo "⚠️  No pools visible yet (might need to create some)"
    fi
    exit 0
  fi
  echo -n "."
  sleep 2
done
echo ""
echo "❌ Backend failed to start within 60 seconds"
exit 1
EOF

chmod +x /tmp/verify-backend.sh

# Start backend with logging
echo ""
echo "Starting backend with command:"
echo "  SPRING_PROFILES_ACTIVE=$ENVIRONMENT SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING=true SPRING_CONFIG_ADDITIONAL_LOCATION=$SPRING_CONFIG_ADDITIONAL_LOCATION SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI=$SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=$SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI JAVA_TOOL_OPTIONS=\"-Dspring.security.oauth2.client.provider.appprovider.issuer-uri=$SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI -Dspring.security.oauth2.resourceserver.jwt.issuer-uri=$SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI\" ../gradlew bootRun"
echo ""
echo "Logs will be written to: /tmp/backend-production.log"
echo ""

# Start in background and verify
nohup env SPRING_PROFILES_ACTIVE="$ENVIRONMENT" SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING=true SPRING_MAIN_ALLOW_CIRCULAR_REFERENCES=true \
  SPRING_CONFIG_ADDITIONAL_LOCATION="$SPRING_CONFIG_ADDITIONAL_LOCATION" \
  SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI="$SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI" \
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="$SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI" \
  LEDGER_GRPC_AUTHORITY="$LEDGER_GRPC_AUTHORITY" \
  JAVA_TOOL_OPTIONS="-Dserver.port=$BACKEND_PORT -Dserver.address=$SERVER_BIND_ADDRESS -Dspring.security.oauth2.client.provider.appprovider.issuer-uri=$SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI -Dspring.security.oauth2.resourceserver.jwt.issuer-uri=$SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI -Dspring.main.allow-circular-references=true ${LEDGER_GRPC_AUTHORITY:+-Dledger.grpc-authority=$LEDGER_GRPC_AUTHORITY}" \
  ../gradlew clean bootRun > /tmp/backend-production.log 2>&1 &
BACKEND_PID=$!
echo "Backend PID: $BACKEND_PID"

# Run verification
sleep 10
/tmp/verify-backend.sh

echo ""
echo "=============================================="
echo "   Backend Started Successfully!"
echo "=============================================="
echo ""
echo "API Endpoints:"
echo "- Health: http://localhost:$BACKEND_PORT/actuator/health"
echo "- Pools:  http://localhost:$BACKEND_PORT/api/pools"
echo "- Tokens: http://localhost:$BACKEND_PORT/api/tokens/{party}"
echo ""
echo "To view logs:"
echo "  tail -f /tmp/backend-production.log"
echo ""
echo "To stop:"
echo "  kill $BACKEND_PID"
echo ""
