# Atomic Swap API Documentation

## Overview

The Atomic Swap API provides a **race-condition-free** method to execute token swaps in a single Canton transaction. Unlike the two-step flow (prepare → execute), atomic swaps combine all operations into one atomic transaction using multi-party authorization.

**Endpoint**: `POST /api/swap/atomic`

**DAR Version**: 1.0.1+
**Required Template**: `AtomicSwapProposal`

---

## Request

### Headers
```http
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json
```

### Body
```json
{
  "poolId": "ETH-USDC",
  "inputSymbol": "ETH",
  "outputSymbol": "USDC",
  "inputAmount": "1.0",
  "minOutput": "1900.0",
  "maxPriceImpactBps": 500
}
```

### Parameters

| Field | Type | Required | Description | Validation |
|-------|------|----------|-------------|------------|
| `poolId` | string | Yes | Pool identifier (e.g., "ETH-USDC") | Must exist with liquidity |
| `inputSymbol` | string | Yes | Symbol of token to sell | Must match pool token |
| `outputSymbol` | string | Yes | Symbol of token to receive | Must match pool token, ≠ inputSymbol |
| `inputAmount` | decimal | Yes | Amount of input token | > 0, scale=10 |
| `minOutput` | decimal | Yes | Minimum acceptable output | ≥ 0, scale=10 |
| `maxPriceImpactBps` | integer | Yes | Max price impact in basis points | 0-10000 (0-100%) |

**Notes**:
- All decimal amounts are automatically scaled to 10 decimal places
- `maxPriceImpactBps`: 100 = 1%, 500 = 5%, 10000 = 100%
- Deadline is calculated server-side (5 minutes from request time)

---

## Response

### Success (200 OK)
```json
{
  "outputTokenCid": "00b8ace3d8935cae8c89d1c9...",
  "trader": "app_provider_quickstart-root-1::12201300...",
  "inputSymbol": "ETH",
  "outputSymbol": "USDC",
  "inputAmount": "0.9992500000",
  "outputAmount": "1972.8499381025",
  "executionTime": "2025-10-19T20:11:52.833630Z"
}
```

### Response Fields

| Field | Type | Description |
|-------|------|-------------|
| `outputTokenCid` | string | Contract ID of received token |
| `trader` | string | Canton party ID of trader |
| `inputSymbol` | string | Symbol of input token |
| `outputSymbol` | string | Symbol of output token |
| `inputAmount` | decimal | Actual input amount (after fees) |
| `outputAmount` | decimal | Actual output amount received |
| `executionTime` | timestamp | ISO-8601 execution timestamp |

---

## Error Responses

### 400 Bad Request
```json
{
  "timestamp": "2025-10-19T20:11:17.156+00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "inputAmount must be positive",
  "path": "/api/swap/atomic"
}
```

**Common Errors**:
- `inputAmount must be positive` - Amount ≤ 0
- `inputSymbol and outputSymbol must be different` - Same token swap
- `maxPriceImpactBps must be between 0 and 10000` - Invalid BPS
- `minOutput must be non-negative` - Negative min output

### 401 Unauthorized
```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Authentication required - JWT subject missing"
}
```

**Cause**: Missing or invalid JWT token

### 404 Not Found
```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Pool not found or has no liquidity: ETH-USDC"
}
```

**Causes**:
- Pool doesn't exist
- Pool has zero reserves
- Insufficient trader token balance

### 500 Internal Server Error
```json
{
  "status": 500,
  "error": "Internal Server Error",
  "message": "Swap execution failed"
}
```

**Causes**:
- Canton ledger error
- Template not found (DAR version < 1.0.1)
- Contract archived during execution (race condition - should not happen!)

---

## Atomicity Guarantees

### What Makes It Atomic?

The atomic swap uses **multi-party authorization** to execute all three steps in a single Canton transaction:

```daml
choice ExecuteAtomicSwap : ContractId Receipt
  controller trader, poolParty  -- Both parties authorize together!
  do
    -- All in ONE transaction:
    swapRequestCid <- create SwapRequest with ...
    (swapReadyCid, _) <- exercise swapRequestCid PrepareSwap with ...
    receiptCid <- exercise swapReadyCid ExecuteSwap
    return receiptCid
```

### Transaction Flow

```
┌─────────────────────────────────────────────────────────────┐
│ Single Atomic Canton Transaction                            │
├─────────────────────────────────────────────────────────────┤
│ 1. Create AtomicSwapProposal (trader signatory)             │
│ 2. Execute with multi-party (trader + poolParty)            │
│    ├─ Create SwapRequest (ephemeral)                        │
│    ├─ Exercise PrepareSwap (ephemeral SwapReady)            │
│    └─ Exercise ExecuteSwap → Receipt                        │
│                                                              │
│ Result: Pool updated, tokens transferred, receipt created   │
└─────────────────────────────────────────────────────────────┘
```

### No Race Conditions

**Old Two-Step Flow (RACE CONDITION):**
```
Time  Trader Thread          Pool Thread
----  -----------------      ---------------------
T0    PrepareSwap
      → stores poolCid
T1                           Another swap updates pool
                             → pool archived, new pool created
T2    ExecuteSwap
      → uses stale poolCid
      → ❌ CONTRACT_NOT_FOUND
```

**New Atomic Flow (NO RACE):**
```
Time  Combined Thread
----  ------------------------------------------
T0    Create AtomicSwapProposal
T1    Execute (trader + poolParty authorize together)
      → All 3 steps in SAME transaction
      → Pool CID stays valid throughout
      → ✅ Success guaranteed
```

---

## Example Usage

### cURL
```bash
# Get OAuth token
TOKEN=$(curl -s -X POST "http://localhost:8082/realms/AppProvider/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=app-provider-backend" \
  -d "client_secret=<SECRET>" \
  -d "grant_type=client_credentials" | jq -r .access_token)

# Execute atomic swap
curl -X POST "http://localhost:8080/api/swap/atomic" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "poolId": "ETH-USDC",
    "inputSymbol": "ETH",
    "outputSymbol": "USDC",
    "inputAmount": "1.0",
    "minOutput": "1900.0",
    "maxPriceImpactBps": 500
  }'
```

### JavaScript
```javascript
const response = await fetch('http://localhost:8080/api/swap/atomic', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${jwtToken}`,
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({
    poolId: 'ETH-USDC',
    inputSymbol: 'ETH',
    outputSymbol: 'USDC',
    inputAmount: '1.0',
    minOutput: '1900.0',
    maxPriceImpactBps: 500
  })
});

const result = await response.json();
console.log(`Swapped ${result.inputAmount} ${result.inputSymbol} for ${result.outputAmount} ${result.outputSymbol}`);
```

---

## Fee Calculation

Swaps incur a **0.3% total fee** (30 basis points):
- **22.5 bps** (0.225%) → Liquidity providers
- **7.5 bps** (0.075%) → Protocol treasury

**Example**:
```
Input: 1.0 ETH
Fee: 0.003 ETH (0.3%)
Amount for swap: 0.997 ETH
Output: ~1,972.85 USDC (at 2000 USDC/ETH price)
```

---

## Health Check

Before using atomic swap, verify it's available:

```bash
curl http://localhost:8080/api/health/ledger
```

```json
{
  "darVersion": "1.0.1",
  "atomicSwapAvailable": true,
  "status": "OK"
}
```

If `atomicSwapAvailable: false`, upgrade to DAR v1.0.1+

---

## Comparison: Atomic vs Two-Step

| Aspect | Two-Step Flow | Atomic Swap |
|--------|---------------|-------------|
| **Transactions** | 2 separate | 1 atomic |
| **Race Conditions** | ⚠️ Possible | ✅ None |
| **Authorization** | Sequential | Multi-party |
| **Pool CID** | Can become stale | Always valid |
| **Retries Needed** | Often | Rare |
| **Complexity** | Higher | Lower |
| **Recommended** | ❌ No | ✅ Yes |

---

## Troubleshooting

### "Pool not found or has no liquidity"
**Solution**: Verify pool exists and has reserves
```bash
curl http://localhost:8080/api/health/ledger | jq .clearportxContractCount
```

### "Insufficient token balance"
**Solution**: Check trader token balance matches inputAmount

### "AtomicSwapProposal template NOT found"
**Solution**: Upgrade to DAR v1.0.1
```bash
cd clearportx && daml build
cd ../backend && ../gradlew build
make restart-backend
```

### "Authentication required"
**Solution**: Include valid JWT in Authorization header

---

## Rate Limits

**Current**: None
**Planned**: 10 swaps/min per party (Phase 4)

---

## See Also

- [ATOMIC_SWAP_STATUS.md](../ATOMIC_SWAP_STATUS.md) - Implementation details
- [Health Endpoint](../backend/src/main/java/com/digitalasset/quickstart/service/LedgerHealthService.java)
- [DAML Template](../daml/AMM/AtomicSwap.daml)
