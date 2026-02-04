# ClearportX Test Scripts

## Essential Scripts (Keep - Working Tests)

### Production-Ready Swap Tests

**[test-atomic-swap-e2e.sh](../test-atomic-swap-e2e.sh)** ⭐ **RECOMMENDED**
- **Purpose**: Complete end-to-end atomic swap validation
- **Tests**: Health check → OAuth → Prepare swap → Execute swap → Receipt validation
- **Features**:
  - Comprehensive assertions (15+ tests)
  - Color-coded output
  - Validates DAR version 1.0.1
  - Checks atomic swap availability
  - Verifies output amounts
- **Usage**: `./test-atomic-swap-e2e.sh`
- **Status**: ✅ Production-ready

**[smoke-test.sh](../smoke-test.sh)** ⭐ **LOCALNET**
- **Purpose**: Full localnet validation
- **Tests**: Health → Init → Pools → 3 swaps → Fee metrics
- **Features**:
  - Exit codes for CI/CD (0-6)
  - Validates fee split ratio (75% LP / 25% protocol)
  - Idempotent initialization
  - Prometheus metrics validation
- **Usage**: `./smoke-test.sh`
- **Status**: ✅ Production-ready for localnet

**[devnet-smoke-test.sh](../devnet-smoke-test.sh)** ⭐ **DEVNET**
- **Purpose**: Rate-limited devnet validation
- **Tests**: Health → Rate limiter → 3 swaps @ 0.33 TPS → Fee metrics
- **Features**:
  - 3-second delays between swaps (complies with 0.5 TPS devnet limit)
  - Validates rate limiter returns HTTP 429
  - Package ID validation
  - DAR version strict matching
- **Usage**:
  ```bash
  export BASE=https://devnet.clearportx.com/api
  export ALICE_JWT="eyJ..."
  ./devnet-smoke-test.sh
  ```
- **Status**: ✅ Production-ready for Canton Network devnet

### Quick Test Scripts

**[quick-swap-test.sh](../quick-swap-test.sh)**
- **Purpose**: Fast single swap test (no assertions)
- **Usage**: Quick validation during development
- **Output**: JSON response from atomic swap
- **Status**: ✅ Useful for quick checks

**[test-single-swap.sh](../test-single-swap.sh)**
- **Purpose**: Single swap with full output
- **Usage**: Debugging individual swaps
- **Status**: ✅ Simple and reliable

**[test-multiple-swaps.sh](../test-multiple-swaps.sh)**
- **Purpose**: Execute 5 swaps and check metrics
- **Features**: Shows swap results + Prometheus metrics sample
- **Status**: ✅ Good for load testing

**[test-10-swaps-fees.sh](../test-10-swaps-fees.sh)**
- **Purpose**: Execute 10 swaps and calculate expected fees
- **Features**:
  - Shows success/failure count
  - Calculates expected protocol fees (25%) and LP fees (75%)
  - 2-second delays between swaps
- **Status**: ✅ Excellent for fee validation

## Diagnostic Scripts (Keep)

**[diagnose-missing-metrics.sh](../diagnose-missing-metrics.sh)**
- **Purpose**: Debug metrics issues
- **Shows**: All registered metrics, available tags, backend logs
- **Status**: ✅ Useful for troubleshooting

## Obsolete Scripts (Archive)

### Metrics Testing (Redundant)
- `test-fee-metrics.sh` - Covered by smoke-test.sh
- `test-metrics-swaps.sh` - Covered by test-10-swaps-fees.sh
- `test-all-new-metrics.sh` - Covered by smoke-test.sh
- `verify-metrics-hardened.sh` - Covered by devnet-smoke-test.sh
- `test-pool-metric.sh` - Covered by smoke-test.sh
- `quick-debug-metrics.sh` - Covered by diagnose-missing-metrics.sh
- `final-metrics-test.sh` - Redundant
- `final-summary.sh` - Redundant
- `final-swap-test.sh` - Covered by test-10-swaps-fees.sh
- `final-test-swaps.sh` - Redundant
- `final-verification-test.sh` - Covered by smoke-test.sh

### Utility Scripts (Keep)

**[complete-test.sh](../complete-test.sh)** ✅
- **Purpose**: End-to-end backend startup + swap test
- **Features**:
  - Waits for backend health (3-minute timeout)
  - Verifies metrics registration
  - Initializes pools
  - Configures OAuth via Keycloak admin CLI
  - Executes 3 test swaps
  - Validates Prometheus metrics
- **Usage**: `./complete-test.sh`
- **Status**: ✅ Unique - full system validation from cold start

**[create-test-user.sh](../create-test-user.sh)** ✅
- **Purpose**: Create Keycloak test users
- **Usage**: `./create-test-user.sh [username] [password]`
- **Default**: Creates `alice` with password `alicepass`
- **Realm**: AppUser
- **Status**: ✅ Essential for manual testing

**[enable-oauth.sh](../enable-oauth.sh)** ✅
- **Purpose**: Enable OAuth direct access grants in Keycloak
- **Features**:
  - Configures `app-provider-unsafe` client
  - Enables password grant type
  - Tests OAuth token acquisition
- **Usage**: `./enable-oauth.sh`
- **Status**: ✅ Required for localnet OAuth setup

## Recommended Test Workflow

### Local Development
```bash
# 1. Quick check
./quick-swap-test.sh

# 2. Full validation
./test-atomic-swap-e2e.sh

# 3. Load test with fees
./test-10-swaps-fees.sh

# 4. Complete smoke test
./smoke-test.sh
```

### Devnet Deployment
```bash
# 1. Get JWT token
export ALICE_JWT="$(curl -X POST ... | jq -r '.access_token')"

# 2. Run devnet smoke test (rate-limited)
export BASE=https://devnet.clearportx.com/api
./devnet-smoke-test.sh

# Expected output: ✅ DEVNET SMOKE TEST PASSED
```

### Debugging
```bash
# Check metrics
./diagnose-missing-metrics.sh

# Test single swap
./test-single-swap.sh
```

## Summary

**Keep (11 scripts)**:
- ✅ test-atomic-swap-e2e.sh (E2E validation)
- ✅ smoke-test.sh (Localnet full test)
- ✅ devnet-smoke-test.sh (Devnet rate-limited test)
- ✅ quick-swap-test.sh (Quick check)
- ✅ test-single-swap.sh (Debug single swap)
- ✅ test-multiple-swaps.sh (5 swaps + metrics)
- ✅ test-10-swaps-fees.sh (Fee calculation)
- ✅ diagnose-missing-metrics.sh (Metrics debugging)
- ✅ create-test-user.sh (If user creation)
- ✅ enable-oauth.sh (If OAuth toggle)
- ✅ complete-test.sh (If unique functionality)

**Archive (11 scripts)**: All `final-*`, `verify-*`, `test-fee-*`, `test-all-*`, `test-pool-*`, `quick-debug-*` that are redundant
