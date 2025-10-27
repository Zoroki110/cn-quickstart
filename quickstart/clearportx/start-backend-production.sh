#!/bin/bash
# Production startup script for ClearportX Backend with proper party resolution
# Created: October 25, 2025
# This script ensures pools are visible in the API

set -e

echo "=============================================="
echo "   ClearportX Backend Production Startup"
echo "=============================================="

# Configuration
LEDGER_HOST="${CANTON_LEDGER_HOST:-localhost}"
LEDGER_PORT="${CANTON_LEDGER_PORT:-5001}"
BACKEND_PORT="${BACKEND_PORT:-8080}"
ENVIRONMENT="${SPRING_PROFILES_ACTIVE:-devnet}"

echo ""
echo "Configuration:"
echo "- Ledger: $LEDGER_HOST:$LEDGER_PORT"
echo "- Backend Port: $BACKEND_PORT"
echo "- Environment: $ENVIRONMENT"
echo ""

# Step 1: Clean up old processes
echo "[1/5] Cleaning up old processes..."
pkill -9 -f "gradlew.*bootRun" 2>/dev/null || true
pkill -9 -f "java.*$BACKEND_PORT" 2>/dev/null || true
sleep 2

# Step 2: Get the correct party ID
echo "[2/5] Resolving app-provider party ID..."
# Try app-provider-4f1df03a:: first (DAML script party), fallback to app-provider::
FULL_PARTY_ID=$(grpcurl -plaintext \
  -d '{}' \
  $LEDGER_HOST:$LEDGER_PORT \
  com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties 2>/dev/null | \
  jq -r '.party_details[] | select(.party | startswith("app-provider-4f1df03a::")) | .party' | head -1)

if [ -z "$FULL_PARTY_ID" ]; then
  # Fallback to app-provider:: if -4f1df03a:: not found
  FULL_PARTY_ID=$(grpcurl -plaintext \
    -d '{}' \
    $LEDGER_HOST:$LEDGER_PORT \
    com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties 2>/dev/null | \
    jq -r '.party_details[] | select(.party | startswith("app-provider::")) | .party' | head -1)
fi

if [ -z "$FULL_PARTY_ID" ]; then
  echo "❌ ERROR: app-provider party not found on ledger!"
  echo ""
  echo "Available parties:"
  grpcurl -plaintext -d '{}' $LEDGER_HOST:$LEDGER_PORT \
    com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties | \
    jq -r '.party_details[].party' | grep -E "(app|provider)" || echo "None found"
  exit 1
fi

echo "✅ Found party: $FULL_PARTY_ID"

# Step 3: Verify package hash
echo "[3/5] Verifying package hash..."
cd /root/cn-quickstart/quickstart/clearportx
if [ -f "artifacts/devnet/clearportx-amm-production-v1.0.2-frozen.dar" ]; then
  PACKAGE_HASH=$(daml damlc inspect-dar artifacts/devnet/clearportx-amm-production-v1.0.2-frozen.dar --json 2>/dev/null | \
    jq -r '.main_package_id' | head -1)
  echo "✅ Using PRODUCTION v1.0.2 frozen DAR with hash: $PACKAGE_HASH"
else
  echo "❌ ERROR: Production frozen DAR not found!"
  echo "Expected: artifacts/devnet/clearportx-amm-production-v1.0.2-frozen.dar"
  exit 1
fi

# Step 4: Set environment variables
echo "[4/5] Setting environment variables..."
export BACKEND_PORT="$BACKEND_PORT"
export APP_PROVIDER_PARTY="$FULL_PARTY_ID"  # CRITICAL: Must be full party ID!
export SPRING_PROFILES_ACTIVE="$ENVIRONMENT"
export CANTON_LEDGER_HOST="$LEDGER_HOST"
export CANTON_LEDGER_PORT="$LEDGER_PORT"
export REGISTRY_BASE_URI="${REGISTRY_BASE_URI:-http://localhost:8090}"

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
EOF

echo "✅ Configuration saved to /tmp/backend-config.env"

# Step 5: Start backend
echo "[5/5] Starting backend..."
cd /root/cn-quickstart/quickstart/backend

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
echo "  ../gradlew bootRun"
echo ""
echo "Logs will be written to: /tmp/backend-production.log"
echo ""

# Start in background and verify
nohup ../gradlew bootRun > /tmp/backend-production.log 2>&1 &
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
