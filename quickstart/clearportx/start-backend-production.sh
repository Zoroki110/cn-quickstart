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
# Determine Spring profiles. Default to devnet,debug (no oauth2/shared-secret unless explicitly provided)
ENVIRONMENT="${SPRING_PROFILES_ACTIVE:-devnet,debug}"

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
  echo "Using APP_PROVIDER_PARTY from environment: $FULL_PARTY_ID"
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
# Enable template schema debug for devnet by default (can be overridden)
if [[ ",$ENVIRONMENT," == *",devnet,"* ]]; then
  export FEATURE_ENABLE_TEMPLATE_SCHEMA_DEBUG="${FEATURE_ENABLE_TEMPLATE_SCHEMA_DEBUG:-true}"
fi
# Optional override for gRPC virtual host when using gateway on :8888
export LEDGER_GRPC_AUTHORITY="${LEDGER_GRPC_AUTHORITY}"
export LEDGER_HOST="$LEDGER_HOST"
export LEDGER_PORT="$LEDGER_PORT"
# Registry routing defaults for DevNet (Amulet via Scan, CBTC via Utilities)
export LEDGER_REGISTRY_DEFAULT_BASE_URI="${LEDGER_REGISTRY_DEFAULT_BASE_URI:-https://scan.sv-1.dev.global.canton.network.sync.global}"
export LEDGER_REGISTRY_BASE_URI="${LEDGER_REGISTRY_BASE_URI:-$LEDGER_REGISTRY_DEFAULT_BASE_URI}"
export LEDGER_REGISTRY_BY_ADMIN="${LEDGER_REGISTRY_BY_ADMIN:-{\"cbtc-network::12202a83c6f4082217c175e29bc53da5f2703ba2675778ab99217a5a881a949203ff\":{\"baseUri\":\"https://api.utilities.digitalasset-dev.com\",\"kind\":\"UTILITIES_TOKEN_STANDARD\"}}}"
export REGISTRY_BASE_URI="$LEDGER_REGISTRY_BASE_URI"
# OAuth2 variables only if oauth2 profile is present
if [[ ",$ENVIRONMENT," == *",oauth2,"* ]]; then
  export AUTH_APP_PROVIDER_ISSUER_URL="http://keycloak.localhost:8082/realms/splice"
  export AUTH_APP_PROVIDER_BACKEND_CLIENT_ID="${AUTH_APP_PROVIDER_BACKEND_CLIENT_ID:-validator-app}"
  export AUTH_APP_PROVIDER_BACKEND_SECRET="${AUTH_APP_PROVIDER_BACKEND_SECRET:-JF53at3KPMCjsbvG99mXDP8OOlvR6dP8}"
  export AUTH_APP_PROVIDER_BACKEND_OIDC_CLIENT_ID="${AUTH_APP_PROVIDER_BACKEND_OIDC_CLIENT_ID:-validator-app}"
  export SPRING_APPLICATION_JSON='{"security":{"issuer-url":"'"$AUTH_APP_PROVIDER_ISSUER_URL"'"}}'
  export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="$AUTH_APP_PROVIDER_ISSUER_URL"
  export SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI="$AUTH_APP_PROVIDER_ISSUER_URL"
  export SPRING_CONFIG_ADDITIONAL_LOCATION="/tmp/clearportx-oauth2-override.yml"
else
  unset SPRING_APPLICATION_JSON
  unset SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
  unset SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI
  SPRING_CONFIG_ADDITIONAL_LOCATION=""
fi

# Log the configuration
cat > /tmp/backend-config.env << EOF
# ClearportX Backend Configuration - $(date)
BACKEND_PORT=$BACKEND_PORT
APP_PROVIDER_PARTY=$APP_PROVIDER_PARTY
SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE
FEATURE_ENABLE_TEMPLATE_SCHEMA_DEBUG=$FEATURE_ENABLE_TEMPLATE_SCHEMA_DEBUG
CANTON_LEDGER_HOST=$CANTON_LEDGER_HOST
CANTON_LEDGER_PORT=$CANTON_LEDGER_PORT
REGISTRY_BASE_URI=$REGISTRY_BASE_URI
LEDGER_REGISTRY_BASE_URI=$LEDGER_REGISTRY_BASE_URI
LEDGER_REGISTRY_BY_ADMIN=$LEDGER_REGISTRY_BY_ADMIN
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

# Ensure manual drain+credit bindings are applied (Transcode workaround)
echo "[5a/5] Applying manual drain+credit bindings overlay..."
MANUAL_DIR="/root/cn-quickstart/quickstart/clearportx/manual-drain-credit-backup"
TARGET_GEN_DIR="build/generated-daml-bindings"
mkdir -p "$TARGET_GEN_DIR/daml"
mkdir -p "$TARGET_GEN_DIR/clearportx_amm_drain_credit/amm/pool"
mkdir -p "$TARGET_GEN_DIR/clearportx_amm_drain_credit"
cp "$MANUAL_DIR/Daml.manual.java" "$TARGET_GEN_DIR/daml/Daml.java"
cp "$MANUAL_DIR/Pool.manual.java" "$TARGET_GEN_DIR/clearportx_amm_drain_credit/amm/pool/Pool.java"
cp "$MANUAL_DIR/Identifiers.manual.java" "$TARGET_GEN_DIR/clearportx_amm_drain_credit/Identifiers.java"
echo "✅ Manual bindings copied into $TARGET_GEN_DIR"

if [[ ",$ENVIRONMENT," == *",oauth2,"* ]]; then
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
fi

# Create a startup verification script
cat > /tmp/verify-backend.sh << 'EOF'
#!/bin/bash
echo "Waiting for backend to start..."
for i in {1..30}; do
  if curl -s http://localhost:${BACKEND_PORT:-8080}/actuator/health >/dev/null 2>&1; then
    echo ""
    echo "✅ Backend is healthy!"
    echo "   Health:  http://localhost:${BACKEND_PORT:-8080}/actuator/health"
    echo "   Profile: ${SPRING_PROFILES_ACTIVE:-unknown}"
    echo "   Party:   ${APP_PROVIDER_PARTY:-unknown}"
    echo ""

    # Show active holding pool (if any)
    HOLDING_POOLS=$(curl -s http://localhost:${BACKEND_PORT:-8080}/api/holding-pools)
    if [ "$HOLDING_POOLS" != "[]" ] && [ -n "$HOLDING_POOLS" ]; then
      ACTIVE_POOL=$(echo "$HOLDING_POOLS" | jq -c '[.[] | select(.status=="Active")][0]')
      if [ "$ACTIVE_POOL" != "null" ]; then
        echo "✅ Active holding pool:"
        echo "$ACTIVE_POOL" | jq .
      else
        COUNT=$(echo "$HOLDING_POOLS" | jq 'length')
        echo "⚠️  No active holding pool found (total: $COUNT)"
      fi
    else
      echo "⚠️  No holding pools visible for this party"
      echo "   Hint: set APP_PROVIDER_PARTY=ClearportX-DEX-1::... to see operator pools"
    fi

    # Public pools (read-only)
    PUBLIC_POOLS=$(curl -s http://localhost:${BACKEND_PORT:-8080}/api/pools)
    if [ "$PUBLIC_POOLS" != "[]" ] && [ -n "$PUBLIC_POOLS" ]; then
      PUBLIC_COUNT=$(echo "$PUBLIC_POOLS" | jq 'length')
      echo ""
      echo "ℹ️  Public pools visible: $PUBLIC_COUNT (from /api/pools)"
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
if [[ ",$ENVIRONMENT," == *",oauth2,"* ]]; then
  echo "  SPRING_PROFILES_ACTIVE=$ENVIRONMENT SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING=true SPRING_CONFIG_ADDITIONAL_LOCATION=$SPRING_CONFIG_ADDITIONAL_LOCATION SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI=$SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=$SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI JAVA_TOOL_OPTIONS=\"-Dspring.security.oauth2.client.provider.appprovider.issuer-uri=$SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI -Dspring.security.oauth2.resourceserver.jwt.issuer-uri=$SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI\" ../gradlew bootRun"
else
  echo "  SPRING_PROFILES_ACTIVE=$ENVIRONMENT ../gradlew bootRun"
fi
echo ""
echo "Logs will be written to: /tmp/backend-production.log"
echo ""

# Start in background and verify
if [[ ",$ENVIRONMENT," == *",oauth2,"* ]]; then
  nohup env SPRING_PROFILES_ACTIVE="$ENVIRONMENT" SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING=true SPRING_MAIN_ALLOW_CIRCULAR_REFERENCES=true \
    SPRING_CONFIG_ADDITIONAL_LOCATION="$SPRING_CONFIG_ADDITIONAL_LOCATION" \
    SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI="$SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI" \
    SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="$SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI" \
    LEDGER_GRPC_AUTHORITY="$LEDGER_GRPC_AUTHORITY" \
    JAVA_TOOL_OPTIONS="-Dserver.port=$BACKEND_PORT -Dserver.address=$SERVER_BIND_ADDRESS -Dspring.security.oauth2.client.provider.appprovider.issuer-uri=$SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_APPPROVIDER_ISSUER_URI -Dspring.security.oauth2.resourceserver.jwt.issuer-uri=$SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI -Dspring.main.allow-circular-references=true ${LEDGER_GRPC_AUTHORITY:+-Dledger.grpc-authority=$LEDGER_GRPC_AUTHORITY}" \
    ../gradlew bootRun > /tmp/backend-production.log 2>&1 &
else
  nohup env SPRING_PROFILES_ACTIVE="$ENVIRONMENT" SPRING_MAIN_ALLOW_CIRCULAR_REFERENCES=true \
    LEDGER_GRPC_AUTHORITY="$LEDGER_GRPC_AUTHORITY" \
    JAVA_TOOL_OPTIONS="-Dserver.port=$BACKEND_PORT -Dserver.address=$SERVER_BIND_ADDRESS -Dspring.main.allow-circular-references=true ${LEDGER_GRPC_AUTHORITY:+-Dledger.grpc-authority=$LEDGER_GRPC_AUTHORITY}" \
    ../gradlew bootRun > /tmp/backend-production.log 2>&1 &
fi
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
echo "Endpoints:"
echo "- Health:        http://localhost:$BACKEND_PORT/actuator/health"
echo "- Pools (public):        http://localhost:$BACKEND_PORT/api/pools"
echo "- Tokens (public):       http://localhost:$BACKEND_PORT/api/tokens/{party}"
echo "- Wallet tokens:         http://localhost:$BACKEND_PORT/api/wallet/tokens/{party}"
echo "- Wallet LP tokens:      http://localhost:$BACKEND_PORT/api/wallet/lp-tokens/{party}"
echo "- Holdings:              http://localhost:$BACKEND_PORT/api/holdings/{partyId}"
echo "- Holdings UTXOs:        http://localhost:$BACKEND_PORT/api/holdings/{partyId}/utxos?ownerOnly=true"
echo "- Holding pools:         http://localhost:$BACKEND_PORT/api/holding-pools"
echo "- Holding pool by CID:   http://localhost:$BACKEND_PORT/api/holding-pools/{cid}"
echo "- Holding pool bootstrap: http://localhost:$BACKEND_PORT/api/holding-pools/{cid}/bootstrap"
echo "- Holding pool archive:   http://localhost:$BACKEND_PORT/api/holding-pools/{cid}/archive"
echo "- DevNet bootstrap TIs:  http://localhost:$BACKEND_PORT/api/devnet/bootstrap-tis"
echo "- DevNet mint:           http://localhost:$BACKEND_PORT/api/devnet/mint"
echo "- DevNet CBTC offers:    http://localhost:$BACKEND_PORT/api/devnet/cbtc/offers"
echo "- DevNet CBTC offer accept: http://localhost:$BACKEND_PORT/api/devnet/cbtc/offers/{offerCid}/accept"
echo "- DevNet CBTC offer probe:  http://localhost:$BACKEND_PORT/api/devnet/cbtc/offers/{offerCid}/probe-accept"
echo "- DevNet CBTC registry probe: http://localhost:$BACKEND_PORT/api/devnet/cbtc/registry/probe"
echo "- DevNet TI debug:       http://localhost:$BACKEND_PORT/api/devnet/transfer-instructions/debug"
echo "- DevNet TI pending:     http://localhost:$BACKEND_PORT/api/devnet/transfer-instructions/pending"
echo "- DevNet TI raw-acs:     http://localhost:$BACKEND_PORT/api/devnet/transfer-instructions/raw-acs"
echo "- DevNet TI inspect:     http://localhost:$BACKEND_PORT/api/devnet/transfer-instructions/inspect"
echo "- DevNet TI outgoing:    http://localhost:$BACKEND_PORT/api/devnet/transfer-instructions/outgoing"
echo "- DevNet TI by-cid:      http://localhost:$BACKEND_PORT/api/devnet/transfer-instructions/by-cid"
echo "- DevNet TI visibility:  http://localhost:$BACKEND_PORT/api/devnet/transfer-instruction/visibility"
echo "- DevNet TI accept:      http://localhost:$BACKEND_PORT/api/devnet/transfer-instruction/accept"
echo "- DevNet TI accept ctx:  http://localhost:$BACKEND_PORT/api/devnet/transfer-instruction/accept-with-context"
echo "- DevNet TI registry ping:   http://localhost:$BACKEND_PORT/api/devnet/transfer-instruction/registry/ping"
echo "- DevNet TI registry config: http://localhost:$BACKEND_PORT/api/devnet/transfer-instruction/registry/config"
echo "- DevNet TI registry probe:  http://localhost:$BACKEND_PORT/api/devnet/transfer-instruction/registry/probe"
echo "- DevNet holdings select: http://localhost:$BACKEND_PORT/api/devnet/holdings/select"
echo "- DevNet holdings templates: http://localhost:$BACKEND_PORT/api/devnet/holdings/templates"
echo "- DevNet templates schema:   http://localhost:$BACKEND_PORT/api/devnet/templates/schema"
echo "- DevNet templates choices:  http://localhost:$BACKEND_PORT/api/devnet/templates/choices"
echo "- DevNet payout amulet:  http://localhost:$BACKEND_PORT/api/devnet/payout/amulet"
echo "- DevNet payout cbtc:    http://localhost:$BACKEND_PORT/api/devnet/payout/cbtc"
echo ""
echo "To view logs:"
echo "  tail -f /tmp/backend-production.log"
echo ""
echo "To stop:"
echo "  kill $BACKEND_PID"
echo ""
