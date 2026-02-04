## Add Liquidity V1 (TransferInstruction flow)

This runbook documents the devnet-only Add Liquidity V1 flow where Loop signs
two inbound TransferInstructions (TIs) and the backend consumes them atomically.

### Prereqs
- Backend running with `devnet` profile.
- HoldingPool DAR compiled and uploaded (new choice `AddLiquidityFromTransferInstructionsV1`).
- `holdingpool.package-id` updated in `backend/src/main/resources/application-devnet.yml`.
- Pool created with a **stable** `poolId` (e.g., `pool-cbtc-amulet-prod`).

### Stable poolId (required)
HoldingPool contractIds rotate on every add/remove. The `poolId` must be a stable identifier
that does **not** change across updates, and LP tokens now store this stable `poolId`.
Legacy LPs (minted before this patch) may be non-removable after pool rotation.

### Deploy/upgrade steps (after poolId patch)
1) Build the DAR:
```
cd /root/cn-quickstart/quickstart/clearportx
daml build -o dist/clearportx-amm-1.0.11.dar
```
2) Upload the DAR:
```
daml ledger upload-dar --host <participant-host> --port <admin-port> dist/clearportx-amm-1.0.11.dar
```
3) Update backend config with the new package id:
```
sed -i 's/^holdingpool\.package-id:.*/holdingpool.package-id: "<NEW_PACKAGE_ID>"/' \
  /root/cn-quickstart/quickstart/backend/src/main/resources/application-devnet.yml
```
4) Recreate pool (template changed; old pool must be archived):
```
curl -s -X POST "http://localhost:8080/api/holding-pools" \
  -H "Content-Type: application/json" \
  -d '{
    "poolId": "pool-cbtc-amulet-prod",
    "instrumentA": { "admin": "<DSO_PARTY>", "id": "Amulet" },
    "instrumentB": { "admin": "<DSO_PARTY>", "id": "CBTC" },
    "feeBps": 30
  }'
```
5) Bootstrap with real inbound TIs (example):
```
curl -s -X POST "http://localhost:8080/api/holding-pools/<poolCid>/bootstrap" \
  -H "Content-Type: application/json" \
  -d '{"tiCidA":"<tiA>","tiCidB":"<tiB>","amountA":"100.0","amountB":"1.0","lpProvider":"<party>"}'
```

### UI flow
1) Open **Add Liquidity** page.
2) Select CC/CBTC pool and enter both amounts.
3) Click **Add Liquidity**.
4) Loop prompts twice (one TI for CC, one TI for CBTC).
5) Backend consumes both TIs and mints LP.

### Inspect (manual)
```
curl -s "http://localhost:8080/api/devnet/liquidity/inspect?requestId=liq-<id>"
```

### Consume (manual)
```
curl -s -X POST "http://localhost:8080/api/devnet/liquidity/consume" \
  -H "Content-Type: application/json" \
  -d '{"requestId":"liq-<id>","poolCid":"<poolCid>"}'
```

### Verify
- `consume` returns `lpMinted`, `newReserveA`, `newReserveB`.
- Pool reserves increase in `/api/holding-pools`.
- LP token appears in `/api/wallet/lp-tokens/<party>`.

### Common errors
- `NOT_FOUND`: missing inbound TIs for requestId.
- `PRECONDITION_FAILED`: deadline expired or provider mismatch.
- `LEDGER_REJECTED`: ledger submission failed (see details in response).


