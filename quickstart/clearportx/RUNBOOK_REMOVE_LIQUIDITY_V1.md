## Remove Liquidity V1 (TransferInstruction payouts)

This runbook documents the devnet-only Remove Liquidity V1 flow where the backend
burns LP and creates two outbound TransferInstructions (one per pool instrument).

### Prereqs
- Backend running with `devnet` profile.
- HoldingPool DAR compiled and uploaded (new choice `RemoveLiquidityFromLpV1`).
- `holdingpool.package-id` updated in `backend/src/main/resources/application-devnet.yml`.
- **Package change requires re-creating the pool** (same as AddLiquidity V1).
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
1) Open **Liquidity → Remove Liquidity**.
2) Select an LP position and enter the LP burn amount.
3) Click **Remove Liquidity**.
4) Backend submits one ledger transaction:
   - validates LP ownership and burn amount
   - updates pool reserves + LP supply
   - creates two outbound TransferInstructions (A + B)
5) If payout status is `CREATED`, accept payouts in Loop.

### Inspect (manual)
```
curl -s "http://localhost:8080/api/devnet/liquidity/remove/inspect?requestId=rm-<id>&poolCid=<poolCid>&lpCid=<lpCid>&receiverParty=<party>&lpBurnAmount=1.0"
```

### Consume (manual)
```
curl -s -X POST "http://localhost:8080/api/devnet/liquidity/remove/consume" \
  -H "Content-Type: application/json" \
  -d '{
    "requestId":"rm-<id>",
    "poolCid":"<poolCid>",
    "lpCid":"<lpCid>",
    "receiverParty":"<party>",
    "lpBurnAmount":"1.0",
    "minOutA":"0",
    "minOutB":"0"
  }'
```

### Verify
- `consume` returns `payoutStatusA/B` (`COMPLETED` or `CREATED`) and optional `payoutCidA/B`.
- Pool reserves decrease in `/api/holding-pools`.
- LP balance decreases in `/api/wallet/lp-tokens/<party>`.

### Retest sequence (poolId resolution)
1) Add liquidity once (creates LP #1).
2) Add liquidity again (rotates poolCid).
3) Remove liquidity using the **first** LP (must succeed via stable `poolId` resolution):
```
curl -s -X POST "http://localhost:8080/api/devnet/liquidity/remove/consume" \
  -H "Content-Type: application/json" \
  -d '{
    "requestId":"rm-<id>",
    "lpCid":"<firstLpCid>",
    "receiverParty":"<party>",
    "lpBurnAmount":"1.0",
    "minOutA":"0",
    "minOutB":"0"
  }'
```

### Common errors
- `VALIDATION`: missing fields or invalid decimals.
- `PRECONDITION_FAILED`: pool inactive, LP owner mismatch, burn too large, or output below min.
- `LEDGER_REJECTED`: ledger submission failed (check `details.step` + `synchronizerId`).

