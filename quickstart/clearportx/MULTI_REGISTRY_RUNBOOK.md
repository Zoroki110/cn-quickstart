# Multi-Asset Registry Routing Runbook

## Overview

This document explains how to configure and use multi-registry routing for TransferInstruction acceptance. This is required because different token issuers (Amulet, CBTC, etc.) may use different Registry API servers.

## Problem Statement

- **Amulet/CC**: Uses the DevNet Scan Registry at `https://scan.sv-1.dev.global.canton.network.digitalasset.com`
- **CBTC**: Uses a different registry (TBD - needs CBTC team confirmation)

The `accept-with-context` endpoint needs to query the correct registry to get `disclosedContracts` for TransferInstruction acceptance.

## Configuration

### application-devnet.yml

```yaml
ledger:
  host: ${LEDGER_API_HOST:localhost}
  port: ${LEDGER_API_PORT:5001}
  registry:
    # Default registry (Amulet/CC via DevNet Scan)
    default-base-uri: ${LEDGER_REGISTRY_DEFAULT_BASE_URI:https://scan.sv-1.dev.global.canton.network.digitalasset.com}
    # Fallback registries to try if default returns 404/empty (comma-separated)
    fallback-base-uris: ${LEDGER_REGISTRY_FALLBACK_BASE_URIS:}
    # Admin-to-registry mapping (JSON format)
    by-admin: ${LEDGER_REGISTRY_BY_ADMIN:{}}
```

### Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `LEDGER_REGISTRY_DEFAULT_BASE_URI` | Default registry URL | `https://scan.sv-1.dev...` |
| `LEDGER_REGISTRY_FALLBACK_BASE_URIS` | Comma-separated fallback URLs | `https://cbtc-scan.example.com,https://other-scan.example.com` |
| `LEDGER_REGISTRY_BY_ADMIN` | JSON mapping of admin party to registry | `{"DSO::1220...":"https://scan.sv-1..."}` |

## API Endpoints

### 1. Get Registry Configuration

```bash
curl http://localhost:8080/api/devnet/transfer-instruction/registry/config
```

Response:
```json
{
  "defaultBaseUri": "https://scan.sv-1.dev.global.canton.network.digitalasset.com",
  "fallbackBaseUris": [],
  "adminMappings": {}
}
```

### 2. Probe Registries for a TI

Use this to discover which registry can provide disclosures for a specific TransferInstruction:

```bash
curl -X POST http://localhost:8080/api/devnet/transfer-instruction/registry/probe \
  -H "Content-Type: application/json" \
  -d '{
    "tiCid": "<TransferInstruction-contract-id>",
    "bases": [
      "https://scan.sv-1.dev.global.canton.network.digitalasset.com",
      "https://cbtc-registry.example.com"
    ]
  }'
```

Response:
```json
{
  "tiCid": "<contract-id>",
  "results": [
    {
      "baseUri": "https://scan.sv-1.dev...",
      "httpStatus": 200,
      "hasDisclosures": true,
      "disclosureCount": 2,
      "contextKeys": ["amulet-rules", "open-round", "expire-lock"],
      "error": null
    },
    {
      "baseUri": "https://cbtc-registry.example.com",
      "httpStatus": 404,
      "hasDisclosures": false,
      "disclosureCount": 0,
      "contextKeys": [],
      "error": "AmuletTransferInstruction 'xxx' not found."
    }
  ],
  "successfulBase": "https://scan.sv-1.dev..."
}
```

If `bases` is empty/null, the probe uses all configured registries.

### 3. Accept with Context (Multi-Registry)

The `accept-with-context` endpoint now automatically tries multiple registries:

```bash
curl -X POST http://localhost:8080/api/devnet/transfer-instruction/accept-with-context \
  -H "Content-Type: application/json" \
  -d '{
    "contractId": "<TransferInstruction-contract-id>",
    "asParty": "ClearportX-DEX-1::122081f2..."
  }'
```

Response (success):
```json
{
  "accepted": true,
  "updateId": "1220...",
  "holdingCid": "00...",
  "error": null,
  "registryUsed": "https://scan.sv-1.dev...",
  "registriesAttempted": [
    "https://scan.sv-1.dev..."
  ]
}
```

Response (failure - no registry found):
```json
{
  "accepted": false,
  "updateId": null,
  "holdingCid": null,
  "error": "choice-context missing disclosures after trying 2 registries",
  "registryUsed": null,
  "registriesAttempted": [
    "https://scan.sv-1.dev...",
    "https://other-registry.example.com"
  ]
}
```

## Step-by-Step: Finding CBTC Registry

1. **Get a pending CBTC TransferInstruction CID** from the CBTC team or Loop wallet

2. **Probe candidate registries**:
   ```bash
   curl -X POST http://localhost:8080/api/devnet/transfer-instruction/registry/probe \
     -H "Content-Type: application/json" \
     -d '{
       "tiCid": "<CBTC-TI-CID>",
       "bases": [
         "https://scan.sv-1.dev.global.canton.network.digitalasset.com",
         "https://cbtc-scan.example.com",
         "https://some-other-registry.com"
       ]
     }'
   ```

3. **Check which registry has disclosures** - look for `hasDisclosures: true`

4. **Configure the mapping** (if a CBTC-specific registry is found):
   ```bash
   export LEDGER_REGISTRY_BY_ADMIN='{"<CBTC_ADMIN_PARTY>":"https://cbtc-registry.example.com"}'
   ```

5. **Restart backend** and verify with `/registry/config`

6. **Test accept-with-context** with the CBTC TI CID

## Known Token Admins

| Token | Admin Party | Registry |
|-------|-------------|----------|
| Amulet/CC | `DSO::1220be58c29e65de40bf273be1dc2b266d43a9a002ea5b18955aeef7aac881bb471a` | `https://scan.sv-1.dev.global.canton.network.digitalasset.com` |
| CBTC | TBD - ask CBTC team | TBD |

## Troubleshooting

### "choice-context missing disclosures"

- The registry returned empty `disclosedContracts`
- Try probing other registries
- Contact the token issuer/admin to request disclosure

### "AmuletTransferInstruction 'xxx' not found"

- The registry is Amulet-specific and doesn't handle this token
- This is expected for non-Amulet tokens
- Configure a fallback registry for the specific token

### Registry returns 404

- The TransferInstruction may not exist or be expired
- Verify the CID is correct and the TI is still active

## Next Steps

1. Contact CBTC team to get their registry endpoint
2. Add CBTC admin party and registry URL to configuration
3. Test end-to-end CBTC TransferInstruction acceptance
