# ClearportX UI Runbook

## USD Price Display (UI-only)

This feature is **display-only**. It does not impact swap math, slippage, or any on-ledger logic.

### Backend endpoint
`GET /api/prices?symbols=CBTC,CC`

If `symbols` is omitted, defaults to `CBTC,CC`.

### Environment variables
- `PRICE_CACHE_TTL_SECONDS` (default: `60`)
- `PRICE_BTC_SOURCE` (default: `COINGECKO`)
- `PRICE_CC_MODE` (default: `NONE`)
  - `NONE`: always returns CC as unavailable
  - `COINGECKO_ID`: uses `PRICE_CC_COINGECKO_ID`
  - `FROM_POOL`: uses pool reserves + BTC/USD to estimate CC
- `PRICE_CC_COINGECKO_ID` (optional)
- `PRICE_CC_POOL_CID` (optional if `FROM_POOL`)
- `PRICE_HTTP_TIMEOUT_MS` (default: `2000`)

### Example curl responses

#### `PRICE_CC_MODE=NONE`
```bash
curl "http://localhost:8080/api/prices?symbols=CBTC,CC"
```
```json
{
  "quotes": {
    "CBTC": {
      "symbol": "CBTC",
      "priceUsd": 62345.12,
      "source": "coingecko:bitcoin",
      "status": "OK",
      "reason": null
    },
    "CC": {
      "symbol": "CC",
      "priceUsd": null,
      "source": null,
      "status": "UNAVAILABLE",
      "reason": "NO_RELIABLE_SOURCE"
    }
  },
  "asOf": "2026-01-28T12:34:56.789Z"
}
```

#### `PRICE_CC_MODE=FROM_POOL`
```bash
curl "http://localhost:8080/api/prices?symbols=CBTC,CC"
```
```json
{
  "quotes": {
    "CBTC": {
      "symbol": "CBTC",
      "priceUsd": 62345.12,
      "source": "coingecko:bitcoin",
      "status": "OK",
      "reason": null
    },
    "CC": {
      "symbol": "CC",
      "priceUsd": 0.5412,
      "source": "amm-spot+btc-usd",
      "status": "OK",
      "reason": "ESTIMATED_FROM_POOL"
    }
  },
  "asOf": "2026-01-28T12:34:56.789Z"
}
```

