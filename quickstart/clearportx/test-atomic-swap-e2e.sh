#!/bin/bash
# Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: 0BSD

# End-to-End Atomic Swap Validation Script
#
# This script validates the complete atomic swap functionality:
# 1. Backend health check
# 2. OAuth token acquisition
# 3. Atomic swap execution
# 4. Receipt validation
# 5. Pool state verification

set -euo pipefail

# Configuration
BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak.localhost:8082}"
TEST_USER="${TEST_USER:-alice}"
TEST_PASSWORD="${TEST_PASSWORD:-test}"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test counters
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

test_assert() {
    local description="$1"
    local condition="$2"

    TESTS_RUN=$((TESTS_RUN + 1))

    if eval "$condition"; then
        echo -e "${GREEN}✓${NC} $description"
        TESTS_PASSED=$((TESTS_PASSED + 1))
        return 0
    else
        echo -e "${RED}✗${NC} $description"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi
}

echo "========================================"
echo "Atomic Swap E2E Validation"
echo "========================================"
echo ""

# Test 1: Backend Health Check
log_info "Step 1: Checking backend health..."
HEALTH_RESPONSE=$(curl -s "${BACKEND_URL}/api/health/ledger" || echo '{"status":"ERROR"}')
echo "Health: $HEALTH_RESPONSE"

test_assert "Backend is healthy" \
    "[[ \$(echo '$HEALTH_RESPONSE' | jq -r '.status' 2>/dev/null || echo 'ERROR') == 'OK' ]]"

test_assert "Atomic swap is available" \
    "[[ \$(echo '$HEALTH_RESPONSE' | jq -r '.atomicSwapAvailable' 2>/dev/null || echo 'false') == 'true' ]]"

DAR_VERSION=$(echo "$HEALTH_RESPONSE" | jq -r '.darVersion' 2>/dev/null || echo "unknown")
test_assert "DAR version is 1.0.1" \
    "[[ '$DAR_VERSION' == '1.0.1' ]]"

echo ""

# Test 2: OAuth Token Acquisition
log_info "Step 2: Acquiring OAuth token..."
TOKEN_RESPONSE=$(curl -s -X POST \
    -H "Host: keycloak.localhost" \
    "${KEYCLOAK_URL}/realms/AppProvider/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password" \
    -d "client_id=app-provider-unsafe" \
    -d "username=${TEST_USER}" \
    -d "password=${TEST_PASSWORD}" \
    2>/dev/null || echo '{"error":"connection_failed"}')

ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.access_token' 2>/dev/null || echo "")

test_assert "OAuth token acquired" \
    "[[ -n '$ACCESS_TOKEN' && '$ACCESS_TOKEN' != 'null' ]]"

if [[ -z "$ACCESS_TOKEN" || "$ACCESS_TOKEN" == "null" ]]; then
    log_error "Failed to acquire OAuth token. Cannot continue with swap tests."
    log_warn "Ensure Keycloak is running and user ${TEST_USER} exists."
    echo ""
    echo "Summary: $TESTS_RUN tests run, $TESTS_PASSED passed, $TESTS_FAILED failed"
    exit 1
fi

echo ""

# Test 3: Prepare Atomic Swap
log_info "Step 3: Preparing atomic swap (1 ETH → USDC)..."
PREPARE_REQUEST='{
  "inputSymbol": "ETH",
  "outputSymbol": "USDC",
  "inputAmount": "1.0",
  "minOutput": "2900.0",
  "maxPriceImpactBps": 200
}'

PREPARE_RESPONSE=$(curl -s -X POST \
    "${BACKEND_URL}/api/swap/prepare" \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$PREPARE_REQUEST" \
    2>/dev/null || echo '{"error":"request_failed"}')

echo "Prepare response: $(echo "$PREPARE_RESPONSE" | jq -c '.' 2>/dev/null || echo "$PREPARE_RESPONSE")"

test_assert "Prepare swap succeeded" \
    "[[ \$(echo '$PREPARE_RESPONSE' | jq -r '.swapReadyCid' 2>/dev/null || echo '') != '' ]]"

SWAP_READY_CID=$(echo "$PREPARE_RESPONSE" | jq -r '.swapReadyCid' 2>/dev/null || echo "")
INPUT_SYMBOL=$(echo "$PREPARE_RESPONSE" | jq -r '.inputSymbol' 2>/dev/null || echo "")
OUTPUT_SYMBOL=$(echo "$PREPARE_RESPONSE" | jq -r '.outputSymbol' 2>/dev/null || echo "")
MIN_OUTPUT=$(echo "$PREPARE_RESPONSE" | jq -r '.minOutput' 2>/dev/null || echo "0")

test_assert "Swap ready CID generated" \
    "[[ -n '$SWAP_READY_CID' && '$SWAP_READY_CID' != 'null' ]]"

test_assert "Input symbol is ETH" \
    "[[ '$INPUT_SYMBOL' == 'ETH' ]]"

test_assert "Output symbol is USDC" \
    "[[ '$OUTPUT_SYMBOL' == 'USDC' ]]"

test_assert "Min output is positive" \
    "[[ \$(echo '$MIN_OUTPUT > 0' | bc 2>/dev/null || echo '0') == '1' ]]"

echo ""

# Test 4: Execute Atomic Swap
log_info "Step 4: Executing atomic swap..."
EXECUTE_REQUEST=$(cat <<EOF
{
  "swapReadyCid": "${SWAP_READY_CID}",
  "inputSymbol": "${INPUT_SYMBOL}",
  "outputSymbol": "${OUTPUT_SYMBOL}",
  "inputAmount": "1.0",
  "minOutput": "${MIN_OUTPUT}"
}
EOF
)

EXECUTE_RESPONSE=$(curl -s -X POST \
    "${BACKEND_URL}/api/swap/execute" \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$EXECUTE_REQUEST" \
    2>/dev/null || echo '{"error":"execution_failed"}')

echo "Execute response: $(echo "$EXECUTE_RESPONSE" | jq -c '.' 2>/dev/null || echo "$EXECUTE_RESPONSE")"

test_assert "Execute swap succeeded" \
    "[[ \$(echo '$EXECUTE_RESPONSE' | jq -r '.receiptCid' 2>/dev/null || echo '') != '' ]]"

RECEIPT_CID=$(echo "$EXECUTE_RESPONSE" | jq -r '.receiptCid' 2>/dev/null || echo "")
OUTPUT_AMOUNT=$(echo "$EXECUTE_RESPONSE" | jq -r '.outputAmount' 2>/dev/null || echo "0")

test_assert "Receipt CID generated" \
    "[[ -n '$RECEIPT_CID' && '$RECEIPT_CID' != 'null' ]]"

test_assert "Output amount meets minimum" \
    "[[ \$(echo '$OUTPUT_AMOUNT >= $MIN_OUTPUT' | bc 2>/dev/null || echo '0') == '1' ]]"

test_assert "Output amount is positive and reasonable (> 1900 USDC)" \
    "[[ \$(echo '$OUTPUT_AMOUNT > 1900' | bc 2>/dev/null || echo '0') == '1' ]]"

echo ""

# Test 5: Verify Pool State After Swap
log_info "Step 5: Verifying pool state..."
HEALTH_AFTER=$(curl -s "${BACKEND_URL}/api/health/ledger" 2>/dev/null || echo '{"status":"ERROR"}')
CONTRACT_COUNT_AFTER=$(echo "$HEALTH_AFTER" | jq -r '.contractCount' 2>/dev/null || echo "0")

log_info "Contracts after swap: $CONTRACT_COUNT_AFTER"

test_assert "System remains healthy after swap" \
    "[[ \$(echo '$HEALTH_AFTER' | jq -r '.status' 2>/dev/null || echo 'ERROR') == 'OK' ]]"

echo ""

# Summary
echo "========================================"
echo "Test Summary"
echo "========================================"
echo "Total tests run: $TESTS_RUN"
echo -e "${GREEN}Tests passed:${NC} $TESTS_PASSED"
if [[ $TESTS_FAILED -gt 0 ]]; then
    echo -e "${RED}Tests failed:${NC} $TESTS_FAILED"
else
    echo -e "${GREEN}Tests failed:${NC} $TESTS_FAILED"
fi
echo ""

if [[ $TESTS_FAILED -eq 0 ]]; then
    echo -e "${GREEN}✓ All E2E tests passed!${NC}"
    echo ""
    echo "Atomic swap flow verified:"
    echo "  • Backend health: OK"
    echo "  • OAuth authentication: OK"
    echo "  • Prepare swap: OK (CID: ${SWAP_READY_CID:0:20}...)"
    echo "  • Execute swap: OK (Receipt: ${RECEIPT_CID:0:20}...)"
    echo "  • Output: ${OUTPUT_AMOUNT} USDC (>= ${MIN_OUTPUT} min)"
    echo ""
    exit 0
else
    echo -e "${RED}✗ Some tests failed. Review output above.${NC}"
    exit 1
fi
