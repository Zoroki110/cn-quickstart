  const BACKEND_URL =
    process.env.REACT_APP_BACKEND_API_URL ||
    BUILD_INFO?.features?.backendUrl ||
    'http://localhost:8080';

  async getPools(): Promise<PoolInfo[]> {
    const res = await this.client.get('/api/pools');
    …
  }
  async calculateSwapQuote(...) {
    return this.client.post('/api/swap/quote', body);
  }
  async addLiquidityByCid(...) {
    return this.request(() => this.client.post(
      '/api/clearportx/debug/add-liquidity-by-cid', body));
  }
  ```
- Before every request it injects:
  ```ts
  const party = getPartyId() || DEVNET_PARTY;
  config.headers['X-Party'] = party;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  config.headers['ngrok-skip-browser-warning'] = 'true';
  ```
  So the backend always knows which DevNet party to use (`ClearportX-DEX-1::122081…`).

### 2. ngrok Tunnel

- `https://nonexplicable-lacily-leesa.ngrok-free.dev` points to the Spring Boot process on your DevNet host.
- All `GET /api/pools`, `POST /api/clearportx/debug/...`, `POST /api/swap/atomic`, etc. flow through this tunnel. There shouldn’t be any local-only calls now; if you curl for mint/swap/quote you must hit the ngrok URL so the DevNet-connected backend does the work.

### 3. Backend (Spring Boot on your DevNet VM)

- Code lives in `quickstart/backend`. It’s a Spring Boot app (`com.digitalasset.quickstart.App`) with modules under `com.digitalasset.quickstart.controller`, `service`, `ledger`, etc.
- Relevant controllers/endpoints:
  - `GET /api/pools` → `PoolsController`/`LedgerReader`, pulls current AMM pools via `LedgerApi.getActiveContracts`.
  - `GET /api/clearportx/debug/party-acs`, `/pool-by-cid`, `/resolve-and-grant`, `/add-liquidity-by-cid`, `/swap-by-cid`, `/create-pool-*`: all in `ClearportxDebugController` and `PoolCreationController`.
  - `POST /api/swap/atomic` → `SwapController`, which builds a Daml `Pool.AtomicSwap` command and sends it to DevNet.
  - `POST /api/transactions/recent` etc. → `TransactionHistoryController`.
- Configuration:
  - Run with `SPRING_PROFILES_ACTIVE=devnet,debug` so `backend/src/main/resources/application-devnet.yml` is loaded (contains your DevNet participant host/port).
  - Set `APP_PROVIDER_PARTY=ClearportX-DEX-1::122081…` so the server knows which ledger party to act as.
  - All ledger RPC wiring is in `com.digitalasset.quickstart.ledger.LedgerApi`. This class wraps the gRPC services introduced in Canton 3.4.7 (`CommandService`, `TransactionService`, `StateService`). Examples:
    ```java
    // Create a pool contract on DevNet
    var poolCid = ledgerApi.createAndGetCid(
        poolTemplate,
        List.of(operatorFqid, poolPartyFqid),  // actAs parties
        Collections.emptyList(),              // readAs
        UUID.randomUUID().toString(),
        clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool
    ).join();
    ```
    ```java
    // Fetch visible pools for a party via StateService
    ledgerApi.getActiveContractsForParty(Pool.class, party).join();
    ```
- Ledger credentials/host come from `application-devnet.yml` (host/port, TLS certs, JWT audience, etc.). That’s what actually connects to the DevNet validator node.

### 4. Daml packages in use

- The backend bundles multiple codegen packages under `backend/build/generated-daml-bindings`:
  - `clearportx_amm_drain_credit` (v1.0.0) – All new pool/token logic (drain+credit choices, new `extraObservers`, etc.). This is the package you deployed to DevNet (DAR `clearportx-amm-drain-credit-1.0.0.dar`). All `create`/`exercise` calls for pools, tokens, LP tokens reference `clearportx_amm_drain_credit.*`.
  - `clearportx_amm_production_gv` (1.0.26/1.0.27) – The “gv-compat” read-only package so the backend can still see legacy pools and migrate them. The `LedgerReader` uses these bindings when `/api/pools` lists older pool instances.
  - `daml`/`daml_prim`/`daml_stdlib` – base types (e.g., `da.time.types.RelTime`, `da.types.Tuple2`).

  You can see the bound identifiers in `backend/build/generated-daml-bindings/daml/Daml.java`; both `clearportx_amm_production_gv` and `clearportx_amm_drain_credit` are registered there so the `LedgerApi` can decode events from either package.

### 5. Request flow example (Calculate Swap Quote)

1. User enters an amount on the `/swap` screen. UI calls:
   ```ts
   POST https://nonexplicable-lacily-leesa.ngrok-free.dev/api/swap/quote
   {
     poolId: "CBTC-CC",
     inputSymbol: "CC",
     outputSymbol: "CBTC",
     inputAmount: "1.0"
   }
   ```
2. Spring `SwapController` receives it, resolves a DevNet-visible pool CID via `/api/clearportx/debug/pool-by-cid` or `/resolve-and-grant`, then calls `ledgerApi.exerciseAndGetTransaction` with a `Pool.GetSpotPrice` or `Pool.AtomicSwap` depending on the path.
3. `LedgerApi` talks to the DevNet participant over gRPC (`/etc/quickstart/config/devnet/application.conf` defines host/port and TLS certs). The Daml runtime runs the `Pool` contract on the DevNet node connected to the ClearportX-DEX-1 party.
4. Response comes back from DevNet → `LedgerApi` converts the Daml `Transaction` into Java objects → controller returns JSON to the frontend.

### 6. Deployment/communication recap

- **Frontend**: built on Netlify with `REACT_APP_BACKEND_API_URL=https://nonexplicable-lacily-leesa-ngrok-free.dev`, `REACT_APP_PARTY_ID=ClearportX-DEX-1::122081…`.
- **ngrok**: terminates TLS and tunnels to your DevNet VM’s `localhost:8080`.
- **Backend**: Spring Boot app on the VM, configured with `SPRING_PROFILES_ACTIVE=devnet,debug` and `APP_PROVIDER_PARTY=ClearportX-DEX-1::122081…`. Uses `application-devnet.yml` to point to the DevNet participant (`host:port`, TLS certs, OAuth audience, etc.).
- **Ledger**: The backend’s `LedgerApi` uses the generated Daml bindings (`clearportx_amm_drain_credit` & `clearportx_amm_production_gv`) to sign and submit commands to the DevNet node (`CommandService`, `TransactionService`, `StateService`). All contracts live on the DevNet ledger.

Once the backend runs the latest code on the DevNet VM and all your test actions (mint/pool/swap) go through the ngrok URL, the entire chain—UI → backend → DevNet—stays in sync. Let me know when you’ve redeployed and we can test the full path end-to-end.