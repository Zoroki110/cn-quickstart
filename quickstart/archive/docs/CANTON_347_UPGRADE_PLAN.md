# Canton 3.4.7 / Splice 0.5.1 Upgrade Plan

## Current State
- **Validator**: Running Canton 3.4.7 / Splice 0.5.1 ✅
- **Backend**: Using Canton 3.3.0 stubs (incompatible) ❌
- **Blocker**: JFrog authentication for `com.daml:ledger-api-proto:3.4.7`
- **.env**: Already set to `DAML_RUNTIME_VERSION=3.4.7` ✅

## Problem
The validator (3.4.7) requires:
- `EventFormat` in `GetActiveContractsRequest`
- `TransactionFormat` in command submissions
- No more `SubmitAndWaitForTransactionTree` (removed in 3.4.x)

Our backend still uses 3.3.0 stubs that send old-format requests, causing:
```
/api/pools → 500 error (missing event_format field)
```

## Step-by-Step Upgrade Path

### Phase 1: JFrog Authentication Setup

**Option A: Use Canton Open Source (Public Maven Central)**
Canton 3.4.7 may be available on Maven Central. We can try switching to public repos.

**Option B: Get Valid JFrog Token**
Contact Digital Asset or Canton Network team for:
- Identity Token (JWT starting with `eyJ...`)
- API Key (not reference token)

**Option C: Use Local Validator Protos**
Extract protos from running validator container and generate stubs locally.

### Phase 2: Update Dependencies

**File**: `/root/cn-quickstart/quickstart/buildSrc/src/main/kotlin/Dependencies.kt`

Current transcode version targets daml3_3:
```kotlin
val plugin get() = "com.daml.codegen-java-daml3_3:com.daml.codegen-java-daml3_3.gradle.plugin:$version"
val protoJava get() = "com.daml:transcode-codec-proto-java-daml3.3_3:$version"
```

Need to find daml3_4 or daml3.4 equivalent transcode artifacts.

### Phase 3: Code Migration - LedgerApi.java

#### 3.1: Update `getActiveContracts` (for pools/tokens reads)

**Before (3.3.0)**:
```java
GetActiveContractsRequest.newBuilder()
    .setFilter(TransactionFilter.newBuilder()
        .putFiltersByParty(party, Filters.newBuilder()
            .addInclusive(InclusiveFilters.newBuilder()
                .addTemplateIds(templateId)
                .build())
            .build())
        .build())
    .build();
```

**After (3.4.7)**:
```java
import com.daml.ledger.api.v2.EventFormat;
import com.daml.ledger.api.v2.TemplateFilter;

GetActiveContractsRequest.newBuilder()
    .setEventFormat(EventFormat.newBuilder()
        .putFiltersByParty(party, Filters.newBuilder()
            .setCumulativeFilter(CumulativeFilter.newBuilder()
                .addTemplateFilters(TemplateFilter.newBuilder()
                    .setTemplateId(templateId)
                    .setIncludeCreatedEventBlob(false)
                    .build())
                .build())
            .build())
        .build())
    .setActiveAtOffset(ParticipantOffset.newBuilder()
        .setAbsolute(getLedgerEnd())  // Get current offset
        .build())
    .build();
```

#### 3.2: Update `createAndGetCid` (command submission)

**Before (3.3.0)**:
```java
SubmitAndWaitRequest req = SubmitAndWaitRequest.newBuilder()
    .setCommands(commands)
    .build();

TransactionTree tree = commandService.submitAndWaitForTransactionTree(req);
// Extract CID from tree.eventsById
```

**After (3.4.7)**:
```java
import com.daml.ledger.api.v2.TransactionFormat;

SubmitAndWaitRequest req = SubmitAndWaitRequest.newBuilder()
    .setCommands(commands)
    .setTransactionFormat(TransactionFormat.newBuilder()
        .setEventFormat(EventFormat.newBuilder()
            .putFiltersByParty(party, Filters.newBuilder()
                .setCumulativeFilter(CumulativeFilter.newBuilder()
                    .setTemplateWildcard(TemplateWildcard.newBuilder().build())
                    .build())
                .build())
            .build())
        .build())
    .build();

Transaction txn = commandService.submitAndWaitForTransaction(req);

// Parse flat transaction events to find CreatedEvent
String contractId = null;
for (Event evt : txn.getEventsList()) {
    if (evt.hasCreated()) {
        CreatedEvent created = evt.getCreated();
        if (created.getTemplateId().equals(expectedTemplateId)) {
            contractId = created.getContractId();
            break;
        }
    }
}
```

#### 3.3: Update `exercise` methods

Similar changes:
- Use `submitAndWaitForTransaction` instead of `submitAndWaitForTransactionTree`
- Parse `Transaction.events` list
- Look for `ExercisedEvent` in flat events

### Phase 4: Code Migration - LedgerReader.java

**File**: `backend/src/main/java/com/digitalasset/quickstart/service/LedgerReader.java`

Update `pools()` method to call updated `LedgerApi.getActiveContracts` which now uses EventFormat.

### Phase 5: Code Migration - Controllers

Check all controllers that might use TransactionTree:
- `PoolCreationController`
- `SwapController`
- `LiquidityController`

Replace TransactionTree parsing with flat Transaction.events parsing.

### Phase 6: Configuration Updates

**File**: `/root/cn-quickstart/quickstart/clearportx/start-backend-production.sh`

Add environment variables if using gateway:
```bash
export CANTON_LEDGER_PORT=5001  # Or 8888 if using gateway
export LEDGER_GRPC_AUTHORITY=participant  # Or gateway vhost
```

### Phase 7: Testing

1. **Verify gRPC connection**:
```bash
grpcurl -plaintext localhost:5001 com.daml.ledger.api.v2.StateService/GetLedgerEnd -d '{}'
```

2. **Test /api/pools**:
```bash
curl -H "X-Party: ClearportX-DEX-1::122081f2b8e2..." http://localhost:8080/api/pools
```

3. **Test pool creation**:
```bash
./create-pool-direct.sh
```

## Quick Start Commands

### Step 1: Try Maven Central First
```bash
cd /root/cn-quickstart/quickstart/backend

# Add Maven Central to repositories (if not already present)
# Edit build.gradle.kts to add:
# repositories {
#     mavenCentral()
#     maven {
#         url = uri("https://digitalasset.jfrog.io/artifactory/canton")
#         credentials { ... }  // Optional, try without first
#     }
# }

# Attempt build
../gradlew compileJava
```

### Step 2: If Maven Central fails, extract protos from validator
```bash
# Extract protos from running validator
docker cp splice-validator-participant-1:/canton/protos /tmp/canton-347-protos

# Generate Java stubs locally
cd /root/cn-quickstart/quickstart/backend
# Add protobuf configuration to use local protos
```

### Step 3: Implement code changes incrementally
1. Start with `LedgerApi.java` - update one method at a time
2. Update `LedgerReader.java`
3. Update controllers
4. Test after each change

## Migration Checklist

- [ ] Phase 1: Resolve JFrog authentication or use alternative
- [ ] Phase 2: Update Dependencies.kt for daml3.4 transcode
- [ ] Phase 3.1: Migrate `getActiveContracts` to EventFormat
- [ ] Phase 3.2: Migrate `createAndGetCid` to TransactionFormat
- [ ] Phase 3.3: Migrate `exercise` methods
- [ ] Phase 4: Update LedgerReader.pools()
- [ ] Phase 5: Update controllers for flat Transaction
- [ ] Phase 6: Configure gateway settings if needed
- [ ] Phase 7: Test all endpoints

## Expected Outcomes

After upgrade:
- ✅ `/api/pools` returns pool list (no more 500 error)
- ✅ Pool creation works via CommandService
- ✅ Add liquidity works
- ✅ Swaps execute successfully
- ✅ All queries use EventFormat
- ✅ All commands use TransactionFormat

## Rollback Plan

If upgrade fails:
1. Revert `.env` to `DAML_RUNTIME_VERSION=3.3.0`
2. Revert code changes to LedgerApi.java
3. Restart backend with `./start-backend-production.sh`
