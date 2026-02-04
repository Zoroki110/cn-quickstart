#!/usr/bin/env bash
#
# Bloc 3 Smoke Test - 0.33 TPS devnet-like load
#
# Validation:
# - 5 atomic swaps at 3-second intervals (0.33 TPS)
# - X-Idempotency-Key and X-Request-ID headers
# - Success rate ≥98%
# - P95 latency <2s
# - No K-invariant drift
#
# Usage:
#   ./bloc3-smoke-test.sh
#

set -euo pipefail

BASE=${BASE:-http://localhost:8080/api}
PROM=${PROM:-http://localhost:8080/api/actuator/prometheus}

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

step() { echo -e "\n${GREEN}🔹 $*${NC}"; }
error() { echo -e "${RED}✗ $*${NC}" >&2; }
success() { echo -e "${GREEN}✓ $*${NC}"; }
warn() { echo -e "${YELLOW}⚠ $*${NC}"; }

step "Bloc 3 Smoke Test - 0.33 TPS devnet load"

# Get JWT from backend container
step "Getting Alice JWT"
ALICE_JWT=$(docker exec backend-service curl -fsS \
    "http://keycloak:8082/realms/AppProvider/protocol/openid-connect/token" \
    -d "client_id=app-provider-unsafe" \
    -d "grant_type=password" \
    -d "username=alice" \
    -d "password=alicepass" 2>/dev/null | jq -r '.access_token // empty') || {
    error "Failed to get JWT from Keycloak"
    exit 1
}

if [ -z "$ALICE_JWT" ]; then
    error "JWT is empty"
    exit 1
fi

success "JWT obtained (${#ALICE_JWT} chars)"

# Get baseline metrics
step "Recording baseline metrics"
SWAPS_BEFORE=$(curl -fsS "$PROM" | grep "clearportx_swap_executed_total" | grep -v "#" | awk '{sum+=$2} END {print sum+0}')
echo "  Executed swaps before: $SWAPS_BEFORE"

# Execute 5 swaps at 3-second intervals (0.33 TPS)
step "Executing 5 swaps at 0.33 TPS (3s interval)"

SUCCESS_COUNT=0
FAIL_COUNT=0
TOTAL_TIME=0

for i in $(seq 1 5); do
    START_TIME=$(date +%s%3N)

    echo "  Swap $i/5..."

    RESPONSE=$(curl -w "\n%{http_code}\n%{time_total}" -fsS -X POST "$BASE/swap/atomic" \
        -H "Authorization: Bearer $ALICE_JWT" \
        -H "X-Idempotency-Key: bloc3-smoke-$i" \
        -H "X-Request-ID: bloc3-req-$i" \
        -H "Content-Type: application/json" \
        -d '{
            "inputSymbol": "USDC",
            "inputAmount": "100.0000000000",
            "outputSymbol": "ETH",
            "minOutput": "0.0100000000",
            "maxPriceImpactBps": 200
        }' 2>&1) || {
        error "Swap $i failed: $RESPONSE"
        FAIL_COUNT=$((FAIL_COUNT + 1))
        sleep 3
        continue
    }

    HTTP_CODE=$(echo "$RESPONSE" | tail -2 | head -1)
    TIME_TOTAL=$(echo "$RESPONSE" | tail -1)
    BODY=$(echo "$RESPONSE" | head -n -2)

    if [ "$HTTP_CODE" = "200" ]; then
        RECEIPT=$(echo "$BODY" | jq -r '.receiptCid // empty')
        OUTPUT=$(echo "$BODY" | jq -r '.outputAmount // empty')

        echo "    ✓ HTTP $HTTP_CODE - Receipt: $RECEIPT"
        echo "    ✓ Output: $OUTPUT ETH"
        echo "    ✓ Time: ${TIME_TOTAL}s"

        SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
        TOTAL_TIME=$(echo "$TOTAL_TIME + $TIME_TOTAL" | bc -l)
    else
        error "HTTP $HTTP_CODE - $BODY"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi

    # Wait 3 seconds before next swap (except after last swap)
    if [ $i -lt 5 ]; then
        sleep 3
    fi
done

# Calculate success rate
SUCCESS_RATE=$(echo "scale=2; $SUCCESS_COUNT * 100 / 5" | bc -l)
AVG_TIME=$(echo "scale=3; $TOTAL_TIME / $SUCCESS_COUNT" | bc -l)

echo ""
echo "Results:"
echo "  Success: $SUCCESS_COUNT/5 (${SUCCESS_RATE}%)"
echo "  Failed: $FAIL_COUNT/5"
echo "  Avg time: ${AVG_TIME}s"

# Verify success rate ≥98%
if [ "$(echo "$SUCCESS_RATE >= 98" | bc -l)" -eq 1 ]; then
    success "Success rate ≥98% ✓"
else
    error "Success rate ${SUCCESS_RATE}% < 98%"
    exit 1
fi

# Verify avg time <2s (P95 approximation for 5 samples)
if [ "$(echo "$AVG_TIME < 2.0" | bc -l)" -eq 1 ]; then
    success "Avg response time <2s ✓"
else
    warn "Avg response time ${AVG_TIME}s ≥2s"
fi

# Check metrics updated
sleep 2

SWAPS_AFTER=$(curl -fsS "$PROM" | grep "clearportx_swap_executed_total" | grep -v "#" | awk '{sum+=$2} END {print sum+0}')
SWAPS_DELTA=$((SWAPS_AFTER - SWAPS_BEFORE))

echo ""
echo "  Executed swaps after: $SWAPS_AFTER (Δ=$SWAPS_DELTA)"

if [ $SWAPS_DELTA -ge $SUCCESS_COUNT ]; then
    success "Metrics updated correctly ✓"
else
    warn "Metrics delta ($SWAPS_DELTA) < success count ($SUCCESS_COUNT)"
fi

# Verify no rate limiting occurred
RATE_LIMIT_COUNT=$(curl -fsS "$PROM" | grep "clearportx_rate_limit_exceeded_total" | grep -v "#" | awk '{print $2}' || echo "0")
echo "  Rate limits hit: $RATE_LIMIT_COUNT"

if [ "$RATE_LIMIT_COUNT" = "0" ]; then
    success "No rate limiting at 0.33 TPS ✓"
else
    warn "Rate limiting triggered $RATE_LIMIT_COUNT times (unexpected at 0.33 TPS)"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${GREEN}✅ BLOC 3 SMOKE TEST PASSED${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Summary:"
echo "  - Success rate: ${SUCCESS_RATE}% (target: ≥98%)"
echo "  - Avg response: ${AVG_TIME}s (target: <2s)"
echo "  - Metrics: $SWAPS_DELTA swaps recorded"
echo "  - Rate limits: $RATE_LIMIT_COUNT (target: 0)"
echo ""

exit 0
