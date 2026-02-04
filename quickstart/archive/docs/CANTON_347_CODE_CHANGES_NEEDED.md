# Canton 3.4.7 Code Migration - Required Changes

## ✅ Status: Protos Generated Successfully!

Canton 3.4.7 protos extracted from validator and compiled successfully.

## ❌ Compilation Errors to Fix (23 errors)

### Files Needing Updates:

1. **`LedgerApi.java`** - 13 errors
2. **`ClearportxDebugController.java`** - 10 errors

### Error Categories:

#### 1. `TransactionTree` and `TreeEvent` Removed (Canton 3.4.x breaking change)

**Errors:**
- `cannot find symbol: class TransactionTree`
- `cannot find symbol: class TreeEvent`
- `cannot find symbol: method submitAndWaitForTransactionTree()`

**Solution:** Use flat `Transaction` and `Event` instead

```java
// BEFORE (3.3.0):
SubmitAndWaitResponse response = commands.submitAndWaitForTransactionTree(request);
TransactionTree txTree = response.getTransaction();
Map<Integer, TreeEvent> eventsById = txTree.getEventsByIdMap();

// AFTER (3.4.7):
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

SubmitAndWaitResponse response = commands.submitAndWaitForTransaction(req);
Transaction txn = response.getTransaction();

// Iterate flat events list:
for (Event evt : txn.getEventsList()) {
    if (evt.hasCreated()) {
        CreatedEvent created = evt.getCreated();
        // Process created event
    }
    if (evt.hasExercised()) {
        ExercisedEvent exercised = evt.getExercised();
        // Process exercised event
    }
}
```

#### 2. `TransactionFilter` API Changed

**Error:**
```
cannot find symbol: variable TransactionFilter
location: class TransactionFilterOuterClass
```

**Solution:** Use `EventFormat` instead of `TransactionFilter`

```java
// BEFORE (3.3.0):
GetActiveContractsRequest.newBuilder()
    .setFilter(TransactionFilter.newBuilder()
        .putFiltersByParty(party, Filters.newBuilder()
            .addInclusive(InclusiveFilters.newBuilder()
                .addTemplateIds(templateId)
                .build())
            .build())
        .build())
    .build();

// AFTER (3.4.7):
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
        .setAbsolute(getLedgerEnd())
        .build())
    .build();
```

## Specific Methods to Update in LedgerApi.java

### Method 1: `createAndGetCid()` (Line ~280)

**Current Code:**
```java
return toCompletableFuture(commands.submitAndWaitForTransactionTree(request))
    .thenApply(response -> {
        TransactionTree txTree = response.getTransaction();
        // ... extract CID from tree
    });
```

**New Code:**
```java
// Add TransactionFormat to request
SubmitAndWaitRequest reqWithFormat = SubmitAndWaitRequest.newBuilder()
    .setCommands(request.getCommands())
    .setTransactionFormat(TransactionFormat.newBuilder()
        .setEventFormat(buildEventFormatForParty(request.getCommands().getActAs(0)))
        .build())
    .build();

return toCompletableFuture(commands.submitAndWaitForTransaction(reqWithFormat))
    .thenApply(response -> {
        Transaction txn = response.getTransaction();
        // Iterate flat events to find CreatedEvent
        for (Event evt : txn.getEventsList()) {
            if (evt.hasCreated()) {
                CreatedEvent created = evt.getCreated();
                if (created.getTemplateId().equals(expectedTemplateId)) {
                    return created.getContractId();
                }
            }
        }
        throw new RuntimeException("Created event not found");
    });
```

### Method 2: `exercise()` methods (Line ~603, ~740)

Similar changes - replace `submitAndWaitForTransactionTree` with `submitAndWaitForTransaction` and parse flat events.

### Method 3: `getActiveContracts()` (Line ~376)

**Current Code:**
```java
.setFilter(TransactionFilter.newBuilder()
    .putFiltersByParty(party, Filters.newBuilder()
        .addInclusive(InclusiveFilters.newBuilder()
            .addTemplateIds(templateId)
            .build())
        .build())
    .build())
```

**New Code:**
```java
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
    .setAbsolute(getLedgerEnd())
    .build())
```

## Helper Method to Add

```java
private EventFormat buildEventFormatForParty(String party) {
    return EventFormat.newBuilder()
        .putFiltersByParty(party, Filters.newBuilder()
            .setCumulativeFilter(CumulativeFilter.newBuilder()
                .setTemplateWildcard(TemplateWildcard.newBuilder().build())
                .build())
            .build())
        .build();
}
```

## ClearportxDebugController.java Changes

### Issue: Uses `TransactionTree` in debug endpoints

Lines affected: 433, 560, 907, 908

**Solution:** These debug methods should be updated to use flat Transaction or removed if not essential.

## Next Steps

1. ✅ Protos extracted and generated
2. ⏳ Update `LedgerApi.java` - migrate all methods to Canton 3.4.7 API
3. ⏳ Update `ClearportxDebugController.java` - fix debug endpoints
4. ⏳ Test compilation
5. ⏳ Test runtime with validator
6. ⏳ Verify `/api/pools` works
7. ⏳ Test pool creation, liquidity, swaps

## Estimated Effort

- LedgerApi.java updates: 2-3 hours
- Debug controller updates: 30 minutes
- Testing: 1 hour

Total: ~4 hours of focused work

## References

- Canton 3.4.x docs: https://docs.canton.io/
- Ledger API v2 changes: Event-based flat transactions only
- No more TransactionTree RPCs in 3.4.x
