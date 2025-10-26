# Pool Creation Status - DevNet Deployment

**Date**: October 25, 2025
**Session**: Context continuation after multi-day debugging
**Critical Blocker**: Pool visibility issue (2+ days)

## Current Situation

### What Works ✅
1. Canton Network DevNet validator running (localhost:5001)
2. v1.0.4 DAR built with package hash `e08c974f11c0a04446635e733211580e810d0590a8a1223737a996552ddebbad`
3. Java bindings generated from v1.0.4 DAR
4. Backend compiles successfully
5. Pool creation DAML scripts execute without errors
6. **NEW**: Created [PoolCreationController.java](../backend/src/main/java/com/digitalasset/quickstart/controller/PoolCreationController.java) for direct Ledger API pool creation

### The Core Problem ❌
**Symptom**: Backend API returns `[]` (zero pools) even after successful pool creation

**Root Cause Discovery**: DAML package hashes are **non-deterministic** across builds
- Every `daml build` generates a different package hash
- Pool created with hash A
- Backend queries with hash B
- Result: Invisible pools

### Critical Files

**Backend Pool Creation Endpoint**:
- Location: `/root/cn-quickstart/quickstart/backend/src/main/java/com/digitalasset/quickstart/controller/PoolCreationController.java`
- Endpoint: `POST /api/debug/create-pool-direct`
- Purpose: Create pools via Ledger API with full logging to debug visibility issues

**Request Format**:
```json
{
  "operatorParty": "OP_UNIQUE",
  "poolParty": "POOL_UNIQUE",
  "ethIssuer": "ETH_UNIQUE",
  "usdcIssuer": "USDC_UNIQUE",
  "lpIssuer": "LP_UNIQUE",
  "feeReceiver": "FEE_UNIQUE"
}
```

**Current DAR**:
- File: `/root/cn-quickstart/quickstart/clearportx/.daml/dist/clearportx-amm-1.0.4.dar`
- Package Hash: `e08c974f11c0a04446635e733211580e810d0590a8a1223737a996552ddebbad`
- Version: 1.0.4

**Backend Configuration**:
- Gradle: Points to frozen artifact at `artifacts/devnet/clearportx-amm-v1.0.4-e08c974f.dar`
- Bindings: Generated from v1.0.4 DAR with correct hash
- Profile: devnet
- Ledger: localhost:5001

## Next Steps

### Immediate Action Required
1. **Start Backend**:
   ```bash
   cd /root/cn-quickstart/quickstart/backend
   SPRING_PROFILES_ACTIVE=devnet \
   APP_PROVIDER_PARTY=OP_OCT25_1651 \
   LEDGER_API_HOST=localhost \
   LEDGER_API_PORT=5001 \
   BACKEND_PORT=8080 \
   REGISTRY_BASE_URI=http://localhost:5012 \
   ../gradlew bootRun > /tmp/backend-pool-debug.log 2>&1 &
   ```

2. **Wait for Startup** (30-60 seconds):
   ```bash
   tail -f /tmp/backend-pool-debug.log | grep -m 1 "Started"
   ```

3. **Create Pool via Ledger API**:
   ```bash
   curl -X POST http://localhost:8080/api/debug/create-pool-direct \
     -H "Content-Type: application/json" \
     -d '{
       "operatorParty": "DEBUG_OP_001",
       "poolParty": "DEBUG_POOL_001",
       "ethIssuer": "DEBUG_ETH_001",
       "usdcIssuer": "DEBUG_USDC_001",
       "lpIssuer": "DEBUG_LP_001",
       "feeReceiver": "DEBUG_FEE_001"
     }' | jq .
   ```

4. **Check Logs** for detailed trace:
   ```bash
   grep "DIRECT POOL CREATION" /tmp/backend-pool-debug.log -A 50
   ```

### Expected Outcomes

**Success Case**:
- Response shows `"success": true`
- Pool CID returned
- `poolCount: 1` in response
- Backend log shows: "✓ Pool created successfully!"

**Failure Case** (most likely):
- Error at specific step (captured in response)
- Full stack trace in logs
- **This will tell us EXACTLY where/why pool creation fails**

## Why This Approach Solves The Problem

Previous attempts used DAML scripts which:
1. Don't show detailed Ledger API errors
2. Build inside script context (hash mismatch)
3. Limited logging visibility

New approach (PoolCreationController):
1. Uses backend's LedgerApi directly
2. Same code path as production
3. Full Java stack traces
4. Step-by-step execution logging
5. Immediate visibility of failures

## Historical Context

### Failed Attempts (Previous Session)
1. Multiple DAML script variations (all succeeded locally, failed to appear in backend)
2. Frozen artifact pipeline (correct concept, execution blocked by party conflicts)
3. FastDevNetInit.daml (parties already allocated)
4. LocalInit.daml (parties already allocated)
5. FreshPoolOct25.daml (created pool, but backend sees 0)

### Parties Already Allocated on DevNet
- OP_OCT25_1651
- POOL_OCT25_1651
- FINAL_OP, DIRECT_OP, WIN_OP
- PoolOperator, PoolParty, ClearportX
- And ~60 more from previous attempts

## Business Context

**Urgency Level**: CRITICAL
- Blocked for 2+ days
- Competitors building similar solution
- Need to test swaps → connect frontend → go live ASAP

## Technical Insights Learned

1. **DAML Build Non-Determinism**: Every build changes package hash
2. **Party Resolution**: Backend uses `PartyRegistryService.resolve()` not direct FQIDs
3. **Multi-Party Authorization**: Pool creation requires `submitMulti` with both operator and poolParty
4. **Template Visibility**: Operator must be signatory or observer to see pool
5. **Canton Network Finalization**: BFT consensus can take seconds (not instant)

## Files Modified This Session

1. `/root/cn-quickstart/quickstart/backend/src/main/java/com/digitalasset/quickstart/controller/PoolCreationController.java` - **NEW**
2. `/root/cn-quickstart/quickstart/daml/build.gradle.kts` - Updated to use frozen v1.0.4 DAR
3. `/root/cn-quickstart/quickstart/clearportx/daml.yaml` - Version bumped to 1.0.4
4. `/root/cn-quickstart/quickstart/clearportx/daml/FreshPoolOct25.daml` - Created (didn't solve issue)
5. `/root/cn-quickstart/quickstart/clearportx/daml/FastInitV104.daml` - Created (couldn't run due to frozen DAR)

## Key Commands Reference

**Check DevNet Status**:
```bash
daml ledger list-parties --host localhost --port 5001 | wc -l
```

**Inspect DAR Package Hash**:
```bash
jar xf .daml/dist/clearportx-amm-1.0.4.dar META-INF/MANIFEST.MF
cat META-INF/MANIFEST.MF | grep "Main-Dalf:"
```

**Check Backend Template ID**:
```bash
grep "PACKAGE_ID = " ../backend/build/generated-daml-bindings/clearportx_amm/Identifiers.java
```

**Query Active Pools** (if backend running):
```bash
curl http://localhost:8080/api/pools | jq .
```

## Success Criteria

Pool creation is successful when:
1. ✅ POST to `/api/debug/create-pool-direct` returns HTTP 200
2. ✅ Response contains valid pool CID
3. ✅ Response shows `poolCount: 1`
4. ✅ GET `/api/pools` returns the created pool
5. ✅ Pool has non-null `tokenACid` and `tokenBCid`
6. ✅ Reserves show: ETH=100, USDC=200000

Once achieved, we can proceed to:
- Test atomic swaps
- Connect frontend
- Deploy to production

---

**Generated**: 2025-10-25 17:40 UTC
**Session**: Claude Code continuation after context limit
