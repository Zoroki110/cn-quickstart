#!/bin/bash

# ============================================================================
# CLEAN START FOR PRODUCTION v1.0.0
# ============================================================================
# This script provides a clean startup with only production contracts visible
# ============================================================================

set -e

echo "=============================================="
echo "   ClearportX Production Clean Start"
echo "=============================================="
echo ""

# Step 1: Kill all backends
echo "[1/5] Stopping all backend processes..."
pkill -f bootRun 2>/dev/null || true
pkill -f gradlew 2>/dev/null || true
sleep 2
echo "✅ All backends stopped"
echo ""

# Step 2: Verify frozen DAR exists
echo "[2/5] Verifying production DAR..."
FROZEN_DAR="/root/cn-quickstart/quickstart/clearportx/artifacts/devnet/clearportx-amm-production-v1.0.0-frozen.dar"
if [ ! -f "$FROZEN_DAR" ]; then
    echo "❌ Frozen DAR not found at: $FROZEN_DAR"
    exit 1
fi
echo "✅ Production DAR found"
HASH=$(jar xf "$FROZEN_DAR" META-INF/MANIFEST.MF && cat META-INF/MANIFEST.MF | grep "Main-Dalf" | awk '{print $2}' | cut -d'-' -f3- | cut -d'.' -f1 && rm -rf META-INF)
echo "   Package hash: $HASH"
echo ""

# Step 3: Get app-provider party
echo "[3/5] Resolving app-provider party..."
APP_PROVIDER_PARTY=$(daml ledger list-parties --host localhost --port 5001 2>/dev/null | grep "app-provider" | head -1 | awk '{print $1}')
if [ -z "$APP_PROVIDER_PARTY" ]; then
    echo "❌ app-provider party not found"
    exit 1
fi
echo "✅ Found party: $APP_PROVIDER_PARTY"
echo ""

# Step 4: Save configuration
echo "[4/5] Saving configuration..."
cat > /tmp/production-config.env <<EOF
# ClearportX Production Configuration
LEDGER_HOST=localhost
LEDGER_PORT=5001
APP_PROVIDER_PARTY=$APP_PROVIDER_PARTY
PACKAGE_HASH=$HASH
PACKAGE_NAME=clearportx-amm-production
PACKAGE_VERSION=1.0.0
FROZEN_DAR=$FROZEN_DAR
EOF

echo "✅ Configuration saved to /tmp/production-config.env"
cat /tmp/production-config.env
echo ""

# Step 5: Start backend
echo "[5/5] Starting backend..."
echo "📝 Backend will use production DAR only"
echo "📝 Old v1.0.4 pools will be ignored (different package name)"
echo ""

cd /root/cn-quickstart/quickstart/clearportx
nohup ../gradlew bootRun > /tmp/backend-production-clean.log 2>&1 &
BACKEND_PID=$!
echo "Backend PID: $BACKEND_PID"
echo "Logs: /tmp/backend-production-clean.log"
echo ""

# Wait for backend
echo "Waiting for backend to start..."
for i in {1..40}; do
    if curl -s http://localhost:8080/actuator/health 2>/dev/null | grep -q "UP"; then
        echo "✅ Backend is healthy!"
        break
    fi
    echo -n "."
    sleep 3
done
echo ""

# Test pools endpoint
echo ""
echo "Testing /api/pools endpoint..."
POOLS_RESPONSE=$(curl -s http://localhost:8080/api/pools 2>/dev/null || echo "ERROR")

if echo "$POOLS_RESPONSE" | grep -q "error"; then
    echo "⚠️  Pools endpoint returned error:"
    echo "$POOLS_RESPONSE" | jq '.' 2>/dev/null || echo "$POOLS_RESPONSE"
    echo ""
    echo "📋 Checking logs..."
    tail -30 /tmp/backend-production-clean.log | grep -A5 "ERROR"
else
    POOL_COUNT=$(echo "$POOLS_RESPONSE" | jq 'length' 2>/dev/null || echo "0")
    echo "✅ Pools endpoint working: $POOL_COUNT pools found"
    if [ "$POOL_COUNT" -gt 0 ]; then
        echo ""
        echo "Pool IDs:"
        echo "$POOLS_RESPONSE" | jq -r '.[].poolId' 2>/dev/null || true
    fi
fi

echo ""
echo "=============================================="
echo "   Production Environment Ready"
echo "=============================================="
echo ""
echo "API Endpoints:"
echo "  Health:  http://localhost:8080/actuator/health"
echo "  Pools:   http://localhost:8080/api/pools"
echo "  Tokens:  http://localhost:8080/api/tokens/{party}"
echo ""
echo "Configuration:"
echo "  Party:   $APP_PROVIDER_PARTY"
echo "  Package: clearportx-amm-production v1.0.0"
echo "  Hash:    $HASH"
echo ""
echo "To view logs:"
echo "  tail -f /tmp/backend-production-clean.log"
echo ""
echo "To stop:"
echo "  kill $BACKEND_PID"
echo ""
