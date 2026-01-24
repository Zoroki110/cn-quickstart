# Loop Registry Investigation - CBTC Acceptance

## Ground Truth
- **Receiver (ClearportX)**: `ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37`
- **Sender (Loop wallet)**: `3f5cab62227096155dd237686093dc95::12205e4067e63c53ef877725e63da505cc27169a000db739fd82fe0065d2bc76eac8`
- **Transfer CID**: `006ae1c...` (pending transfer instruction)
- **Instrument**: `cbtc-network::1220.../CBTC`

## Problem Analysis
- Loop wallet is connected as **sender**, NOT receiver
- Backend must accept as receiver using `actAs: [ClearportX-DEX-1::...]`
- Utilities registries return 404 for DevNet CIDs
- Need Loop token-standard API to get disclosures/context

## Loop Token-Standard API Endpoints

### Known Endpoint (requires Bearer auth):
```
GET https://devnet.cantonloop.com/api/v1/token-standard/transfer-instructions
Authorization: Bearer <token>
```

### Suspected Choice-Context Endpoint:
```
POST https://devnet.cantonloop.com/api/v1/token-standard/transfer-instructions/{cid}/choice-contexts/accept
Authorization: Bearer <token>
```

OR

```
POST https://devnet.cantonloop.com/api/v1/token-standard/transfer-instructions/{cid}/prepare-transaction
Authorization: Bearer <token>
```

## Investigation Steps

### Step 1: Find Loop's Acceptance Flow
Open DevNet Loop UI in browser with DevTools Network tab:
1. Navigate to pending CBTC transfer
2. Click "Accept" button
3. Look for XHR/Fetch requests to:
   - `/choice-contexts/accept`
   - `/prepare-transaction`
   - Any endpoint returning `disclosedContracts`

### Step 2: Extract Request Details
Record:
- Full URL
- HTTP method
- Request headers (especially Authorization)
- Request body
- Response structure with disclosedContracts

### Step 3: Implement Backend Proxy
Create backend endpoint that:
1. Takes transfer CID
2. Calls Loop token-standard API with Bearer token (from env)
3. Returns disclosedContracts + context
4. Exercises TransferInstruction_Accept on ledger actAs receiver

## Next Actions
1. Open Loop UI and capture the exact acceptance API call
2. Extract Bearer token from browser localStorage/sessionStorage
3. Wire token into backend env vars (dev-only)
4. Implement backend acceptance with proper disclosures
