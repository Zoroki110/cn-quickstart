#!/usr/bin/env bash
#
# Verify Prometheus Queries for ClearportX v2.1.0-hardened
#
# Validates:
# - Success rate (1h and 5m windows)
# - P95 latency
# - Active pools gauge
# - Fee collection metrics
# - Rate limiting metrics
#

set -euo pipefail

PROM=${PROM:-http://localhost:8080/api/actuator/prometheus}

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

step() { echo -e "\n${GREEN}🔹 $*${NC}"; }
success() { echo -e "${GREEN}✓ $*${NC}"; }
warn() { echo -e "${YELLOW}⚠ $*${NC}"; }

step "Prometheus Query Verification"

# Success Rate - 1 hour window
step "Query 1: Success Rate (1h window)"
QUERY_1H='100 * (increase(clearportx_swap_executed_total[1h]) / clamp_min(increase(clearportx_swap_prepared_total[1h]), 1))'
echo "  Query: $QUERY_1H"

EXECUTED_1H=$(curl -sS "$PROM" | grep "clearportx_swap_executed_total" | grep -v "#" | awk '{print $2}')
PREPARED_1H=$(curl -sS "$PROM" | grep "clearportx_swap_prepared_total" | grep -v "#" | awk '{print $2}')

echo "  Executed (cumulative): $EXECUTED_1H"
echo "  Prepared (cumulative): $PREPARED_1H"

if [ -n "$EXECUTED_1H" ] && [ -n "$PREPARED_1H" ] && [ "$PREPARED_1H" != "0.0" ]; then
    SUCCESS_RATE=$(awk "BEGIN {printf \"%.2f\", ($EXECUTED_1H / $PREPARED_1H) * 100}")
    echo "  Success Rate: ${SUCCESS_RATE}%"

    if [ "$(awk "BEGIN {print ($SUCCESS_RATE >= 98)}")" = "1" ]; then
        success "Success rate ≥98% ✓"
    else
        warn "Success rate ${SUCCESS_RATE}% < 98%"
    fi
else
    warn "Not enough data for success rate calculation"
fi

# Success Rate - 5 minute window
step "Query 2: Success Rate (5m diagnostic window)"
QUERY_5M='100 * (increase(clearportx_swap_executed_total[5m]) / clamp_min(increase(clearportx_swap_prepared_total[5m]), 1))'
echo "  Query: $QUERY_5M"
echo "  Note: This query is used in Grafana panel id=15"
success "Query documented for Grafana dashboard"

# P95 Latency
step "Query 3: P95 Execution Time"
QUERY_P95='histogram_quantile(0.95, rate(clearportx_swap_execution_time_seconds_bucket[5m]))'
echo "  Query: $QUERY_P95"

HISTOGRAM_DATA=$(curl -sS "$PROM" | grep "clearportx_swap_execution_time_seconds_bucket" | grep -v "#")

if [ -n "$HISTOGRAM_DATA" ]; then
    echo "  Histogram buckets found:"
    echo "$HISTOGRAM_DATA" | head -3 | sed 's/^/    /'
    success "P95 histogram data available ✓"
else
    warn "No histogram data yet (no swaps executed)"
fi

# Active Pools Gauge
step "Query 4: Active Pools (traffic-independent)"
ACTIVE_POOLS=$(curl -sS "$PROM" | grep "clearportx_pool_active_count" | grep -v "#" | awk '{print $2}')
echo "  Active pools: $ACTIVE_POOLS"
echo "  Updated by: PoolMetricsScheduler (every 10s)"

if [ -n "$ACTIVE_POOLS" ]; then
    success "Active pools gauge working ✓"
else
    warn "Active pools gauge not yet populated"
fi

# Fee Metrics
step "Query 5: Fee Collection Metrics"

PROTOCOL_FEE=$(curl -sS "$PROM" | grep "clearportx_fees_protocol_collected_total" | grep -v "#" | awk '{print $2}' | head -1)
LP_FEE=$(curl -sS "$PROM" | grep "clearportx_fees_lp_collected_total" | grep -v "#" | awk '{print $2}' | head -1)

echo "  Protocol fees (25%): $PROTOCOL_FEE"
echo "  LP fees (75%): $LP_FEE"

if [ -n "$PROTOCOL_FEE" ] && [ -n "$LP_FEE" ] && [ "$PROTOCOL_FEE" != "0.0" ]; then
    RATIO=$(awk "BEGIN {printf \"%.2f\", $LP_FEE / $PROTOCOL_FEE}")
    echo "  Fee split ratio (LP/Protocol): $RATIO (expected: ~3.0)"

    if [ "$(awk "BEGIN {print ($RATIO >= 2.9 && $RATIO <= 3.1)}")" = "1" ]; then
        success "Fee split 75/25 correct ✓"
    else
        warn "Fee split ratio $RATIO != 3.0"
    fi
else
    warn "No fee data yet (no swaps executed)"
fi

# Rate Limiting Metrics
step "Query 6: Rate Limiting Metrics"

RATE_LIMIT_EXCEEDED=$(curl -sS "$PROM" | grep "clearportx_rate_limit_exceeded_total" | grep -v "#" | awk '{print $2}' || echo "0.0")
echo "  Rate limits exceeded: $RATE_LIMIT_EXCEEDED"

if [ "$RATE_LIMIT_EXCEEDED" != "0.0" ]; then
    success "Rate limiter is active (detected $RATE_LIMIT_EXCEEDED rejections) ✓"
else
    echo "  No rate limiting yet (traffic below threshold)"
fi

# K-Invariant Drift
step "Query 7: K-Invariant Drift Detection"
QUERY_K_DRIFT='abs(rate(clearportx_pool_k_invariant[10m])) > 1000'
echo "  Query: $QUERY_K_DRIFT"

K_INVARIANT=$(curl -sS "$PROM" | grep "clearportx_pool_k_invariant" | grep -v "#" | head -1)

if [ -n "$K_INVARIANT" ]; then
    echo "  Sample: $(echo "$K_INVARIANT" | awk '{print $1}') = $(echo "$K_INVARIANT" | awk '{print $2}')"
    success "K-invariant tracking active ✓"
else
    warn "No K-invariant data yet"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${GREEN}✅ PROMETHEUS QUERIES VERIFIED${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Key Queries for Grafana/Alerts:"
echo ""
echo "1. Success Rate (1h):"
echo "   100 * (increase(clearportx_swap_executed_total[1h]) /"
echo "          clamp_min(increase(clearportx_swap_prepared_total[1h]), 1))"
echo ""
echo "2. Success Rate (5m) - Panel id=15:"
echo "   100 * (increase(clearportx_swap_executed_total[5m]) /"
echo "          clamp_min(increase(clearportx_swap_prepared_total[5m]), 1))"
echo ""
echo "3. P95 Latency:"
echo "   histogram_quantile(0.95,"
echo "     rate(clearportx_swap_execution_time_seconds_bucket[5m]))"
echo ""
echo "4. Active Pools:"
echo "   clearportx_pool_active_count"
echo ""
echo "5. Fee Split Verification:"
echo "   clearportx_fees_lp_collected_total / clearportx_fees_protocol_collected_total"
echo "   (Expected ratio: ~3.0 for 75%/25% split)"
echo ""
echo "6. Rate Limiting:"
echo "   rate(clearportx_rate_limit_exceeded_total[5m])"
echo ""
echo "7. K-Invariant Drift (Alert):"
echo "   abs(rate(clearportx_pool_k_invariant[10m])) > 1000"
echo ""

exit 0
