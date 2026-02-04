<!--
  ClearportX on Canton DevNet – operational runbook
  Audience: engineers running the ClearportX AMM stack (Daml + Spring Boot backend + React frontend) against an existing Canton DevNet validator, fronted by ngrok/Netlify.
-->

# ClearportX · Canton DevNet Runbook

This document is the single source of truth for reproducing the ClearportX DevNet environment end-to-end:

* Canton validator (DevNet participant or whitelisted VPN endpoint)
* Spring Boot backend (`quickstart/backend`) connecting to that validator
* ngrok tunnel that exposes the backend to the public internet
* React/Netlify frontend configured to talk to the ngrok URL

Copy/paste every command as-is unless noted.

---

## 0. Scope & Prerequisites

| Requirement | Details |
|-------------|---------|
| Canton DevNet access | VPN credentials + whitelisted participant or validator that exposes gRPC (typically `localhost:5001` once SSH/VPN tunnel is established). |
| Party IDs | `APP_PROVIDER_PARTY=ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37` (update if validator assigns a new hash). |
| Tooling | `git`, `jq`, `grpcurl`, `curl`, `tmux`/`screen`, Java 17, Node.js 18+, npm 9+, ngrok v3, Daml SDK 3.4.7 (for contract builds). |
| Credentials | JFrog (for Maven artifacts), Netlify (frontend deploys), VPN profile for DevNet. |

> **Tip**: Export the following once per shell session (adjust host/ports as needed):

```bash
export APP_PROVIDER_PARTY='ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37'
export CANTON_LEDGER_HOST=localhost          # or participant hostname
export CANTON_LEDGER_PORT=5001               # or 8888 if going through Canton gateway
export BACKEND_PORT=8080
export SPRING_PROFILES_ACTIVE=devnet,debug
```

---

## 1. Get the Code & Build Daml

```bash
git clone git@github.com:Zoroki110/cn-quickstart.git
cd cn-quickstart/quickstart

# Build Daml packages (only needed after contract changes)
cd clearportx
daml build

# Optional: regenerate Java bindings if DAR changed
cd ..
./gradlew :daml:build
```

Artifacts:

* Daml DARs live under `clearportx/.daml/dist/`
* Java bindings appear under `backend/build/generated-daml-bindings/`

---

## 2. Canton DevNet Connectivity Check

1. Ensure VPN/SSH tunnel is up so that `CANTON_LEDGER_HOST:CANTON_LEDGER_PORT` is reachable.
2. List parties to confirm connectivity:

```bash
grpcurl -plaintext -d '{}' \
  "$CANTON_LEDGER_HOST:$CANTON_LEDGER_PORT" \
  com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties | jq '.party_details[].party'
```

3. Optionally query ledger end to confirm streaming access:

```bash
grpcurl -plaintext -d '{}' \
  "$CANTON_LEDGER_HOST:$CANTON_LEDGER_PORT" \
  com.daml.ledger.api.v2.StateService/GetLedgerEnd
```

If your validator requires SNI/virtual host routing, set `LEDGER_GRPC_AUTHORITY` and add `-authority "$LEDGER_GRPC_AUTHORITY"` to each `grpcurl` command (the startup script handles this automatically).

---

## 3. Backend Runtime (Spring Boot)

### Key Environment Variables

| Variable | Description |
|----------|-------------|
| `APP_PROVIDER_PARTY` | Full party ID the backend will act as (ClearportX validator party). |
| `CANTON_LEDGER_HOST`, `CANTON_LEDGER_PORT` | gRPC endpoint for the DevNet participant/validator. |
| `LEDGER_GRPC_AUTHORITY` (optional) | Virtual host for Canton gateway (set when port is 8888). |
| `BACKEND_PORT` | Local HTTP port for Spring Boot (default 8080). |
| `SPRING_PROFILES_ACTIVE` | Use `devnet,debug` to load `application-devnet.yml` + verbose logs. |
| `REGISTRY_BASE_URI` | Token registry/TAP service (default `http://localhost:8090`). |
| `CORS_ALLOWED_ORIGINS` | Comma-separated list of frontend origins (include ngrok & Netlify URLs). |

### Start Command

```bash
cd /root/cn-quickstart/quickstart/clearportx
SPRING_PROFILES_ACTIVE=devnet,debug \
BACKEND_PORT=${BACKEND_PORT:-8080} \
LEDGER_API_PORT=${CANTON_LEDGER_PORT:-5001} \
APP_PROVIDER_PARTY="$APP_PROVIDER_PARTY" \
./start-backend-production.sh
```

What the script does:

1. Kills previous `gradlew bootRun` instances.
2. Resolves `APP_PROVIDER_PARTY` if not provided.
3. Writes `/tmp/backend-config.env` for audit.
4. Starts `../gradlew bootRun` with the correct profiles and logs to `/tmp/backend-production.log`.
5. Runs `/tmp/verify-backend.sh` which pings `/actuator/health` and `/api/pools`.

### Health Checks

```bash
curl -s http://localhost:${BACKEND_PORT}/actuator/health | jq
curl -s http://localhost:${BACKEND_PORT}/api/pools | jq
```

Logs: `tail -f /tmp/backend-production.log`

Stop: `pkill -f "gradlew.*bootRun"` or `kill <PID>` reported by the script.

---

## 4. ngrok Tunnel (Backend → Internet)

### Install & Auth

```bash
curl -s https://ngrok-agent.s3.amazonaws.com/ngrok.asc | sudo tee /etc/apt/trusted.gpg.d/ngrok.asc >/dev/null
echo "deb https://ngrok-agent.s3.amazonaws.com buster main" | sudo tee /etc/apt/sources.list.d/ngrok.list
sudo apt update && sudo apt install ngrok

ngrok config add-authtoken <YOUR_NGROK_AUTHTOKEN>
```

### Start Tunnel

```bash
# Use a reserved domain if possible to avoid URL churn
ngrok http 8080 --domain <your-subdomain>.ngrok-free.app --log=stdout | tee /tmp/ngrok.log
```

Record the HTTPS URL (e.g., `https://nonexplicable-lacily-leesa.ngrok-free.dev`). Keep this terminal running (or run inside `tmux`).

### Update Backend CORS (only when URL changes)

```bash
export CORS_ALLOWED_ORIGINS="https://<your-ngrok>.ngrok-free.app,https://app.clearportx.com,http://localhost:3000"
export SPRING_PROFILES_ACTIVE=devnet,debug
./start-backend-production.sh   # restarts with new CORS list
```

### Required Header (avoids ngrok warning page)

All frontend and curl clients must send:

```
ngrok-skip-browser-warning: true
```

The React `backendApi` already injects this header globally.

### Monitor ngrok

* Web UI: `http://127.0.0.1:4040`
* Logs: inspect `/tmp/ngrok.log`

---

## 5. Frontend (React + Netlify)

### Local Development

```bash
cd /root/cn-quickstart/quickstart/frontend
npm install

# point at local backend
REACT_APP_BACKEND_API_URL=http://localhost:8080 \
REACT_APP_PARTY_ID="$APP_PROVIDER_PARTY" \
npm start
```

### Pointing to ngrok / DevNet backend

```bash
export REACT_APP_BACKEND_API_URL=https://<your-ngrok>.ngrok-free.app
export REACT_APP_PARTY_ID="$APP_PROVIDER_PARTY"
npm run build
```

Deploy the `build/` folder (Netlify or any static host). On Netlify set the following environment variables under **Site Settings → Build & Deploy → Environment**:

```
REACT_APP_BACKEND_API_URL=https://<your-ngrok>.ngrok-free.app
REACT_APP_PARTY_ID=ClearportX-DEX-1::122081...
REACT_APP_CANTON_API_URL=https://<your-ngrok>.ngrok-free.app   # optional, keeps provider in sync
```

Trigger a redeploy after each backend or ngrok URL change.

### Smoke Test (from browser DevTools console)

```javascript
fetch(`${process.env.REACT_APP_BACKEND_API_URL}/api/pools`, {
  headers: { 'ngrok-skip-browser-warning': 'true' }
}).then(r => r.json()).then(console.table);
```

---

## 6. Operational Commands (Pools, Liquidity, Swaps)

Always target the ngrok URL so every call hits DevNet.

### List Pools

```bash
curl -s https://<ngrok>/api/pools \
  -H "ngrok-skip-browser-warning: true" | jq
```

### Inspect Visible Pools for a Party

```bash
curl -s "https://<ngrok>/api/clearportx/debug/party-acs?template=AMM.Pool.Pool" \
  -H "X-Party: $APP_PROVIDER_PARTY" \
  -H "ngrok-skip-browser-warning: true" | jq
```

### Create Pool (direct bootstrap)

```bash
curl -s https://<ngrok>/api/debug/create-pool-direct \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER_PARTY" \
  -H "ngrok-skip-browser-warning: true" \
  -d '{
        "operatorParty": "'"$APP_PROVIDER_PARTY"'",
        "poolParty": "'"$APP_PROVIDER_PARTY"'",
        "ethIssuer": "'"$APP_PROVIDER_PARTY"'",
        "usdcIssuer": "'"$APP_PROVIDER_PARTY"'",
        "lpIssuer": "'"$APP_PROVIDER_PARTY"'",
        "feeReceiver": "'"$APP_PROVIDER_PARTY"'",
        "tokenASymbol": "CBTC",
        "tokenBSymbol": "CC",
        "tokenAInitial": "100",
        "tokenBInitial": "10000",
        "swapFeeBps": 30,
        "protocolFeeBps": 30,
        "bootstrapTokens": true,
        "poolId": "cc-cbtc-showcase"
      }' | jq
```

### Add Liquidity

```bash
curl -s https://<ngrok>/api/clearportx/debug/add-liquidity-by-cid \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER_PARTY" \
  -H "ngrok-skip-browser-warning: true" \
  -d '{
        "poolCid": "<POOL_CID>",
        "poolId": "cc-cbtc-showcase",
        "amountA": "0.1",
        "amountB": "1000",
        "minLPTokens": "0.0000000001"
      }' | jq
```

### Reset CC/CBTC Showcase Pool

```bash
curl -s https://<ngrok>/api/clearportx/debug/showcase/reset-pool \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER_PARTY" \
  -H "ngrok-skip-browser-warning: true" \
  -d '{
        "poolId": "cc-cbtc-showcase",
        "cbtcAmount": "5",
        "pricePerCbtc": "900000",
        "maxInBps": 10000,
        "maxOutBps": 10000
      }' | jq
```

This helper archives any existing showcase pool, recreates it with high `maxInBps/maxOutBps`, mints fresh CC/CBTC to the acting party, and seeds liquidity so that ~1 cBTC equals 900 000 CC. Override `cbtcAmount`, `ccAmount`, or `pricePerCbtc` in the payload to tune reserves for larger demos.

### Atomic Swap

```bash
curl -s https://<ngrok>/api/clearportx/debug/swap-by-cid \
  -H "Content-Type: application/json" \
  -H "X-Party: $APP_PROVIDER_PARTY" \
  -H "ngrok-skip-browser-warning: true" \
  -d '{
        "poolCid": "<POOL_CID>",
        "poolId": "cc-cbtc-showcase",
        "inputSymbol": "CC",
        "amountIn": "50",
        "outputSymbol": "CBTC",
        "minOutput": "0.002",
        "maxPriceImpactBps": 500
      }' | jq
```

### Transaction History (backend-internal)

```bash
curl -s https://<ngrok>/api/transactions/recent \
  -H "ngrok-skip-browser-warning: true" | jq
```

### Quick Sanity Script

```bash
cd clearportx
./verify-real-data.sh   # Hits /api/pools, /api/tokens, /api/transactions via ngrok
```

---

## 7. Daily “Mise en Place” Checklist

1. **VPN / SSH tunnel** – connect to the DevNet validator host.
2. **Start backend** – `SPRING_PROFILES_ACTIVE=devnet,debug ./start-backend-production.sh`.
3. **Launch ngrok** – `ngrok http 8080 --domain <reserved>.ngrok-free.app` inside `tmux`.
4. **Update Netlify env** (if ngrok URL changed) and trigger redeploy.
5. **Verify chain**:
   ```bash
   curl -s https://<ngrok>/api/pools -H "ngrok-skip-browser-warning: true" | jq length
   ```
   should be `>= 1`.
6. **Frontend smoke** – load Netlify site in Chrome Incognito, confirm balances update after hard refresh.

Shutdown order: stop Netlify build (optional) → kill ngrok session → stop backend script → disconnect VPN.

---

## 8. Troubleshooting

| Symptom | Likely Cause | Resolution |
|---------|--------------|------------|
| `{"status":"DOWN","components":{"ledger":{"status":"DOWN"}}}` | Backend cannot reach DevNet gRPC | Re-check VPN/SSH tunnel, verify `CANTON_LEDGER_HOST/PORT`, rerun `grpcurl` test. |
| Browser shows ngrok warning page | Missing `ngrok-skip-browser-warning` header | Ensure frontend `.env` and curl commands include `ngrok-skip-browser-warning: true`. |
| `CORS error: origin not allowed` | New Netlify/ngrok URL not in CORS list | Update `CORS_ALLOWED_ORIGINS` (or `application-devnet.yml`), restart backend. |
| `CONTRACT_NOT_ACTIVE` when adding liquidity | Pool CID changed mid-flight | Fetch latest pool CID via `/api/clearportx/debug/pool-by-cid` and retry. |
| `Input exceeds maxInBps limit` | Swap size above pool's configured cap | Either reduce `amountIn` or raise `maxInBps` in the pool template + recreate pool. |
| Netlify UI still hitting old backend | Cached build | Trigger Netlify deploy, then hard refresh (`Ctrl/Cmd + Shift + R`). |
| ngrok session dies | Free tier idle timeout | Run ngrok inside `tmux` and monitor `http://127.0.0.1:4040`; restart tunnel and redeploy frontend with new URL. |

Logs & Tools:

* Backend logs: `/tmp/backend-production.log`
* Application config snapshot: `/tmp/backend-config.env`
* ngrok logs: `/tmp/ngrok.log`
* HTTP trace: `curl -v https://<ngrok>/api/pools -H "ngrok-skip-browser-warning: true"`

---

**Reference URLs**

* Backend health: `https://<ngrok>/actuator/health`
* Pools: `https://<ngrok>/api/pools`
* Tokens: `https://<ngrok>/api/tokens/<party>`
* Transaction history: `https://<ngrok>/api/transactions/recent`
* DevNet admin (grpcurl): `com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties`

Keep this runbook updated as URLs, parties, or infrastructure evolve. It is authoritative for DevNet operations. 


