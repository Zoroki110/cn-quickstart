## Remove Liquidity V1 (TransferInstruction payouts)

This runbook documents the devnet-only Remove Liquidity V1 flow where the backend
burns LP and creates two outbound TransferInstructions (one per pool instrument).

### Prereqs
- Backend running with `devnet` profile.
- HoldingPool DAR compiled and uploaded (new choice `RemoveLiquidityFromLpV1`).
- `holdingpool.package-id` updated in `backend/src/main/resources/application-devnet.yml`.
- **Package change requires re-creating the pool** (same as AddLiquidity V1).

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

### Common errors
- `VALIDATION`: missing fields or invalid decimals.
- `PRECONDITION_FAILED`: pool inactive, LP owner mismatch, burn too large, or output below min.
- `LEDGER_REJECTED`: ledger submission failed (check `details.step` + `synchronizerId`).

