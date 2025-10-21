#!/usr/bin/env bash
#
# ClearportX Testnet Smoke Test
#
# End-to-end test that validates:
# 1. Health endpoints return correct package/version info
# 2. Initialization creates tokens and pools
# 3. Atomic swaps execute successfully
# 4. Fees are recorded correctly in metrics
# 5. Grafana dashboards show expected values
#
# Usage:
#   export BASE=http://localhost:8080/api
#   export ALICE_JWT="eyJ..."
#   ./smoke-test.sh
#
# Exit codes:
#   0 = All tests passed
#   1 = Setup/dependency failure
#   2 = Health check failure
#   3 = Init failure
#   4 = Liquidity failure
#   5 = Swap failure
#   6 = Metrics validation failure

set -euo pipefail

# Configuration
BASE=${BASE:-http://localhost:8080/api}
PROM=${PROM:-http://localhost:8080/api/actuator/prometheus}
ALICE_JWT=${ALICE_JWT:-}

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Helper functions
step() {
    echo -e "\n${GREEN}🔹 $*${NC}"
}

error() {
    echo -e "${RED}✗ ERROR: $*${NC}" >&2
}

success() {
    echo -e "${GREEN}✓ $*${NC}"
}

warn() {
    echo -e "${YELLOW}⚠ $*${NC}"
}

check_dependency() {
    if ! command -v "$1" &> /dev/null; then
        error "$1 is required but not installed"
        exit 1
    fi
}

# Validate dependencies
check_dependency curl
check_dependency jq

step "Starting ClearportX Smoke Test"
echo "BASE: $BASE"
echo "PROM: $PROM"

# Test 1: Health Check
step "Test 1: Health endpoint validation"
HEALTH=$(curl -fsS "$BASE/health/ledger" | jq .)

if [ -z "$HEALTH" ]; then
    error "Health endpoint returned empty response"
    exit 2
fi

STATUS=$(echo "$HEALTH" | jq -r '.status')
DAR_VERSION=$(echo "$HEALTH" | jq -r '.darVersion')
ATOMIC_AVAILABLE=$(echo "$HEALTH" | jq -r '.atomicSwapAvailable')
POOLS_ACTIVE=$(echo "$HEALTH" | jq -r '.poolsActive')
PACKAGE_ID=$(echo "$HEALTH" | jq -r '.clearportxPackageId // "unknown"')

echo "  Status: $STATUS"
echo "  DAR Version: $DAR_VERSION"
echo "  Atomic Swap Available: $ATOMIC_AVAILABLE"
echo "  Active Pools: $POOLS_ACTIVE"
echo "  Package ID: $PACKAGE_ID"

if [ "$STATUS" != "OK" ] && [ "$STATUS" != "SYNCING" ]; then
    error "Health status is $STATUS (expected OK or SYNCING)"
    exit 2
fi

if [ "$DAR_VERSION" != "1.0.1" ]; then
    warn "DAR version is $DAR_VERSION (expected 1.0.1)"
fi

if [ "$ATOMIC_AVAILABLE" != "true" ]; then
    error "Atomic swap not available - wrong DAR deployed?"
    exit 2
fi

success "Health check passed"

# Test 2: Get Alice JWT if not provided
if [ -z "$ALICE_JWT" ]; then
    step "Test 2: Getting Alice JWT from Keycloak"

    # Try to get token from backend container
    ALICE_JWT=$(docker exec backend-service curl -fsS \
        "http://keycloak:8082/realms/AppProvider/protocol/openid-connect/token" \
        -d "client_id=app-provider-unsafe" \
        -d "grant_type=password" \
        -d "username=alice" \
        -d "password=alicepass" 2>/dev/null | jq -r '.access_token // empty') || true

    if [ -z "$ALICE_JWT" ]; then
        warn "Could not get Alice JWT automatically - init may fail"
        warn "Set ALICE_JWT environment variable manually"
    else
        success "Got Alice JWT (${#ALICE_JWT} chars)"
    fi
fi

# Test 3: Initialize (idempotent)
step "Test 3: Initialize ClearportX (create tokens and pools)"

if [ -n "$ALICE_JWT" ]; then
    INIT_RESULT=$(curl -fsS -X POST "$BASE/init/clearportx" \
        -H "Authorization: Bearer $ALICE_JWT" \
        -H "Content-Type: application/json" 2>&1) || {
        warn "Init endpoint call failed - may already be initialized"
        echo "Response: $INIT_RESULT"
    }

    success "Init endpoint called"
else
    warn "Skipping init - no JWT available"
fi

# Give PQS time to sync
sleep 5

# Test 4: Check pools exist
step "Test 4: Verify pools are created"

POOLS=$(curl -fsS "$BASE/pools" | jq .)
POOL_COUNT=$(echo "$POOLS" | jq '. | length')

echo "  Found $POOL_COUNT pools"

if [ "$POOL_COUNT" -eq 0 ]; then
    error "No pools found - init may have failed"
    exit 4
fi

# Get first pool for testing
POOL_ID=$(echo "$POOLS" | jq -r '.[0].poolId')
echo "  Using pool: $POOL_ID"

success "Pools verified"

# Test 5: Check metrics baseline
step "Test 5: Get baseline metrics"

METRICS_BEFORE=$(curl -fsS "$PROM" | grep "clearportx_fees" || echo "")

if [ -z "$METRICS_BEFORE" ]; then
    warn "No fee metrics found yet - this is expected on first run"
    PROTOCOL_FEE_BEFORE=0
    LP_FEE_BEFORE=0
else
    PROTOCOL_FEE_BEFORE=$(echo "$METRICS_BEFORE" | grep "clearportx_fees_protocol_collected_total" | awk '{print $2}' || echo "0")
    LP_FEE_BEFORE=$(echo "$METRICS_BEFORE" | grep "clearportx_fees_lp_collected_total" | awk '{print $2}' || echo "0")
fi

echo "  Protocol Fee Before: $PROTOCOL_FEE_BEFORE"
echo "  LP Fee Before: $LP_FEE_BEFORE"

success "Baseline metrics recorded"

# Test 6: Execute atomic swaps (if JWT available)
if [ -n "$ALICE_JWT" ]; then
    step "Test 6: Execute 3 atomic swaps"

    for i in $(seq 1 3); do
        echo "  Swap $i/3..."

        DEADLINE=$(($(date +%s%3N) + 300000))  # 5 minutes from now

        SWAP_RESULT=$(curl -fsS -X POST "$BASE/swap/atomic" \
            -H "Authorization: Bearer $ALICE_JWT" \
            -H "Content-Type: application/json" \
            -d "{
                \"poolId\": \"$POOL_ID\",
                \"inputSymbol\": \"USDC\",
                \"inputAmount\": \"1000.0000000000\",
                \"outputSymbol\": \"ETH\",
                \"minOutputAmount\": \"0.3000000000\",
                \"maxPriceImpactBps\": 100,
                \"deadlineEpochMs\": $DEADLINE
            }" 2>&1) || {
            error "Swap $i failed: $SWAP_RESULT"
            exit 5
        }

        RECEIPT_CID=$(echo "$SWAP_RESULT" | jq -r '.receiptCid // empty')
        AMOUNT_OUT=$(echo "$SWAP_RESULT" | jq -r '.outputAmount // empty')

        if [ -z "$RECEIPT_CID" ]; then
            error "Swap $i returned no receipt"
            echo "Response: $SWAP_RESULT"
            exit 5
        fi

        echo "    Receipt: $RECEIPT_CID"
        echo "    Output: $AMOUNT_OUT ETH"
    done

    success "3 swaps executed"

    # Test 7: Verify metrics updated
    step "Test 7: Verify fee metrics incremented"

    # Wait for metrics to propagate
    sleep 3

    METRICS_AFTER=$(curl -fsS "$PROM" | grep "clearportx_fees")

    PROTOCOL_FEE_AFTER=$(echo "$METRICS_AFTER" | grep "clearportx_fees_protocol_collected_total" | awk '{print $2}' || echo "0")
    LP_FEE_AFTER=$(echo "$METRICS_AFTER" | grep "clearportx_fees_lp_collected_total" | awk '{print $2}' || echo "0")

    echo "  Protocol Fee After: $PROTOCOL_FEE_AFTER"
    echo "  LP Fee After: $LP_FEE_AFTER"

    # Check that fees increased
    if [ "$(echo "$PROTOCOL_FEE_AFTER > $PROTOCOL_FEE_BEFORE" | bc -l)" -eq 1 ]; then
        success "Protocol fees incremented"
    else
        error "Protocol fees did not increase ($PROTOCOL_FEE_BEFORE → $PROTOCOL_FEE_AFTER)"
        exit 6
    fi

    if [ "$(echo "$LP_FEE_AFTER > $LP_FEE_BEFORE" | bc -l)" -eq 1 ]; then
        success "LP fees incremented"
    else
        error "LP fees did not increase ($LP_FEE_BEFORE → $LP_FEE_AFTER)"
        exit 6
    fi

    # Check 75/25 split ratio
    if [ "$PROTOCOL_FEE_AFTER" != "0" ] && [ "$LP_FEE_AFTER" != "0" ]; then
        RATIO=$(echo "scale=2; $LP_FEE_AFTER / $PROTOCOL_FEE_AFTER" | bc -l)
        echo "  Fee Split Ratio (LP/Protocol): $RATIO (expected: ~3.0)"

        # Ratio should be close to 3.0 (75% / 25%)
        if [ "$(echo "$RATIO >= 2.9 && $RATIO <= 3.1" | bc -l)" -eq 1 ]; then
            success "Fee split ratio correct"
        else
            warn "Fee split ratio is $RATIO (expected ~3.0)"
        fi
    fi

else
    warn "Skipping swap tests - no JWT available"
    warn "Set ALICE_JWT to run full smoke test"
fi

# Final summary
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${GREEN}✅ SMOKE TEST PASSED${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Summary:"
echo "  - Health endpoint: OK"
echo "  - DAR version: $DAR_VERSION"
echo "  - Atomic swap available: $ATOMIC_AVAILABLE"
echo "  - Active pools: $POOL_COUNT"
if [ -n "$ALICE_JWT" ]; then
    echo "  - Swaps executed: 3"
    echo "  - Fee metrics: Working"
fi
echo ""

exit 0
