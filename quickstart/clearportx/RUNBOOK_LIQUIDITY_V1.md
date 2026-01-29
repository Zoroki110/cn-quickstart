## Add Liquidity V1 (TransferInstruction flow)

This runbook documents the devnet-only Add Liquidity V1 flow where Loop signs
two inbound TransferInstructions (TIs) and the backend consumes them atomically.

### Prereqs
- Backend running with `devnet` profile.
- HoldingPool DAR compiled and uploaded (new choice `AddLiquidityFromTransferInstructionsV1`).
- `holdingpool.package-id` updated in `backend/src/main/resources/application-devnet.yml`.

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


