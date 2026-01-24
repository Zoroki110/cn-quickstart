# ClearportX AMM – Current Flow & Integration Roadmap

## 1. Current Working Flow (DevNet / Drain+Credit)

The end-to-end path is now verified from the UI through ngrok to the DevNet backend. Each action below has been exercised with the latest build (Canton 3.4.7 backend, drain+credit DAR 1.0.1, React UI).

.

### 1.3 Health & Observability
| Check | Command | Result |
|-------|---------|--------|
| Backend health | `curl http://localhost:8080/actuator/health` | `UP` (Redis optional) |
| Pools list | `curl http://localhost:8080/api/pools \| jq '.[] | select(.poolId=="cc-cbtc-showcase")'` | Shows reserves, TVL, APR |
| Wallet tokens | `curl http://localhost:8080/api/clearportx/debug/wallet/raw-tokens?...` | Lists newly minted CC/CBTC/ETH |
| Directory | `curl http://localhost:8080/api/clearportx/debug/directory` | Mirrors latest pool CID & party |

All key flows—in particular, two consecutive add-liquidity submissions and UI-driven swaps—have been confirmed with fresh ledger state.

---

## 2. Integration Roadmap (Based on `CANTON_APP_INTEGRATION_PLAN.md`)

The plan remains viable and aligns with our architecture. Below is a condensed mapping plus test gates for each phase.

### Phase 1 – Authentication Infrastructure (Week 1)
- **Result<T> / CantonError**: Introduce composable error handling helpers before touching auth flows.  
  _Test_: Unit tests for `Result.flatMap`, `recover`, and error propagation.
- **PartyValidationService**: Enforce `namespace::fingerprint` format and ledger presence without `try/catch`.  
  _Test_: Contract tests against mock ledger client using malformed IDs, unknown parties, and valid parties.

### Phase 2 – Canton Wallet Integration (Week 1–2)
- **Challenge/Response**: `POST /api/canton/auth/challenge` + `/verify` with deterministic JSON contracts; relies on Result chaining (no raw `try/catch`).  
  _Test_: Integration test that feeds a fake Canton wallet module (signs challenge) and asserts JWT issuance.
- **Signature Verification**: Implement Ed25519 verifier sourcing keys from party fingerprint or ledger metadata.  
  _Test_: Crypto test vectors for success/failure; negative tests for tampered signatures.

### Phase 3 – OAuth2 (Week 2)
- **Spring OAuth client config** targeting Canton realms (per plan).  
  _Test_: Run Keycloak test realm locally, confirm authorization code flow and token exchange; verify Canton claims inside JWT validator.

### Phase 4 – Multi-party Coordination (Week 2–3)
- **Transaction builder** with explicit Result chaining, no global `try/catch`.  
  _Test_: Simulated multi-sign swap requiring two parties, including timeout path. Each step emits structured domain events for observability.
- **Party discovery service** for pools/tokens.  
  _Test_: Query actual pool contracts and ensure all controller parties are discovered.

### Phase 5 – CIP-0056 Metadata (Week 3)
- Build metadata service pulling from Canton’s registry or fallback ledger queries.  
  _Test_: Validate CIP-0056 schema compliance (symbol/name/decimals/logo URI) and persistence.

### Phase 6 – Frontend Wallet (Week 3–4)
- **`useCantonWallet` hook** following plan’s challenge-signature workflow.  
  _Test_: Cypress/E2E scenario using mock wallet object; ensure JWT is stored and reused.
- **Transaction signing modal** bridging UI requests to backend multi-party service.  
  _Test_: Replay prevention (unique challenge IDs) and error toasts for signature failures.

### Phase 7 – Production Hardening (Week 4)
- **Security**: Add rate limiting, replay protection, challenge expiry, structured audit logs.  
  _Test_: Gatling/K6 load tests plus negative auth scenarios.
- **Performance**: Cache party validations (Redis or in-memory), connection pooling, and ledger batching.  
  _Test_: Benchmark before/after to ensure swap throughput improves.
- **Monitoring**: Emit metrics for auth success, signature latency, and CIP-0056 coverage.  
  _Test_: Dashboard thresholds with alert rules.

---

## 3. Implementation Principles & Next Steps

1. **Coding standards**: As requested, avoid blanket `try/catch`; prefer explicit Result-based control flow and targeted exception handling only at boundary layers (e.g., controller entry points).
2. **Debug/Test discipline**: Every phase gets a dedicated test harness (unit/integration/E2E) before we ship UI hooks.
3. **Documentation cadence**: Update this summary plus `E2E_FLOW_DOCUMENTATION.md` after each milestone so QA always has the current command set.
4. **Upcoming work**: Kick off Phase 1 (Result<T> + PartyValidationService) immediately; these primitives unblock the rest of the plan.

Let me know if you’d like this document moved into `docs/` or expanded with sequence diagrams—we can iterate as we implement each phase.


### DevNet Happy Path Checklist (Legacy Token.Token AMM)

1. **Drain (optional reset)**  
   - Call `POST /api/clearportx/debug/wallet/drain` for the operator party.  
   - Expect `200 OK` when contracts are archived; `409` if nothing was visible or already drained.

2. **Create or reset pool**  
   - Use `POST /api/clearportx/debug/showcase/reset-pool` with `poolId=cc-cbtc-showcase`.  
   - Verify `200 OK` and that the response contains the new `poolCid` and archived CIDs; the pool should reappear in the UI directory.

3. **Mint tokens**  
   - Call `POST /api/debug/mint-tokens` with the issuer/provider pairs from the summary (e.g., `CC` + `CBTC`).  
   - Confirm `200 OK` and that the response lists the minted contract IDs; balances should refresh in the wallet view.

4. **Add liquidity**  
   - From the UI (`POST /api/liquidity/add`) or the debug helper (`POST /api/clearportx/debug/add-liquidity-by-cid`), submit the request for `cc-cbtc-showcase`.  
   - Both routes now run through the same AddLiquidityService pipeline: pool CIDs are resolved automatically, one mint+retry occurs for stale Token.Token inputs, and only genuine issues produce 409/422 responses.

5. **Swap**  
   - Execute a swap via `POST /api/clearportx/debug/swap-by-cid` or the UI swap card.  
   - The swap service mirrors add-liquidity semantics—one mint+retry occurs automatically for stale trader Token.Token, and 409 responses now only reflect true pool/visibility problems while slippage violations remain 422. All errors are structured `DomainError` payloads.

### CIP-0056 / CIP-0089 Read-Only Validation

1. Pull the holdings snapshot for the validator party:

   ```bash
   curl -s http://localhost:8080/api/holdings/ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37 | jq
   ```

   The response is an array of `HoldingDto` objects. Each entry contains:
   - `instrumentId` – the CIP instrument identifier from the Holding view (DevNet currently returns the Amulet/Canton Coin instrument).
   - `amount` – normalized decimal quantity resolved from the view amount and decimals.
   - `decimals`, `symbol`, `name`, `tokenType`, `currencyCode`, `logoUri`, `metadataHash`, `metadataVersion` – populated from CIP-0089 metadata when the registry is reachable; `null` when the metadata service is offline.
   - `registry` – the administrator party for the instrument (e.g., `DSO::…`).
   - `attributes` – additional key/value pairs included in the Holding metadata map (round/rate annotations for Amulet).

   Example output (truncated):

   ```json
   [
     {
       "instrumentId": "Amulet",
       "amount": 193554.3817431232,
       "registry": "DSO::1220…",
       "attributes": {
         "amulet.splice.lfdecentralizedtrust.org/created-in-round": "20768",
         "amulet.splice.lfdecentralizedtrust.org/rate-per-round": "0.000224177"
       }
     }
   ]
   ```

2. Empty array → the ledger has not disclosed the CIP holdings to the ClearportX participant. Issue the appropriate observer/readAs grant for the party and retry after the ACS refreshes.

3. `LEDGER_VISIBILITY_ERROR` → the disclosure is propagating. Confirm the grant or wait for the ACS refresh before retrying.

4. `UNEXPECTED_ERROR` mentioning `splice-api-token-holding-v1` → upload the DAR to the participant (`daml ledger upload-dar --host localhost --port 5001 daml/dars/splice-api-token-holding-v1-*.dar`) and rerun the call.
