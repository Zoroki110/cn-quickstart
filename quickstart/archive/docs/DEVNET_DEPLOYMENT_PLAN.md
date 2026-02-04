# ClearportX DevNet Deployment Plan

**Goal**: Deploy ClearportX AMM to Canton Network DevNet with real ledger and working swaps

---

## Phase 1: Prerequisites & Validation

### 1.1 Validate DevNet Access
```bash
# Check egress IP
curl -sSL http://checkip.amazonaws.com

# Test Canton Network connectivity (requires IP whitelisting)
curl -fsS -m 5 https://scan.devnet.canton.network/api/scan/version | jq -r '.version'
```

**Required**:
- [ ] IP whitelisted by Canton Network SV (2-7 day process)
- [ ] Canton Network DevNet credentials (participant ID, PQS credentials)
- [ ] Kubernetes cluster access (kubectl configured)
- [ ] Docker registry access (ghcr.io/clearportx or similar)

### 1.2 Gather Required Credentials

You need to obtain from Canton Network:
- **Participant ID**: Your validator's participant identifier
- **Ledger API Host**: `participant.devnet.canton.network`
- **PQS JDBC URL**: `jdbc:postgresql://pqs.devnet.canton.network:5432/pqs`
- **PQS Username**: `pqs_user`
- **PQS Password**: Provided by SV sponsor

---

## Phase 2: Prepare Application

### 2.1 Build DAR Package
```bash
cd /root/cn-quickstart/quickstart/clearportx
daml build

# Verify DAR was created
ls -lh .daml/dist/clearportx-amm-1.0.1.dar

# Inspect DAR contents
daml damlc inspect-dar .daml/dist/clearportx-amm-1.0.1.dar
```

**Output**: `.daml/dist/clearportx-amm-1.0.1.dar` (ready for upload)

### 2.2 Build Backend Docker Image
```bash
cd /root/cn-quickstart/quickstart

# Build backend JAR
../gradlew :backend:build -x test

# Build Docker image
docker build -t ghcr.io/clearportx/clearportx-backend:v2.1.0-hardened \
  -f backend/Dockerfile \
  backend/

# Push to registry
docker push ghcr.io/clearportx/clearportx-backend:v2.1.0-hardened
```

**Note**: Replace `ghcr.io/clearportx` with your actual registry

### 2.3 Verify Configuration Files

Check these files exist and are correct:
- [x] `application-devnet.yml` - DevNet Spring Boot config
- [x] `deploy-devnet.sh` - Kubernetes deployment script
- [x] `verify-devnet.sh` - Health check script
- [x] `devnet-smoke-test.sh` - End-to-end test script

---

## Phase 3: Deploy to Kubernetes

### 3.1 Create Canton Network Credentials Secret
```bash
kubectl create namespace clearportx

kubectl -n clearportx create secret generic canton-network-creds \
  --from-literal=participant-id=YOUR_PARTICIPANT_ID \
  --from-literal=ledger-api-host=participant.devnet.canton.network \
  --from-literal=pqs-jdbc-url=jdbc:postgresql://pqs.devnet.canton.network:5432/pqs \
  --from-literal=pqs-username=pqs_user \
  --from-literal=pqs-password=YOUR_PQS_PASSWORD
```

### 3.2 Deploy Redis (Required for Distributed Rate Limiter)
```bash
# Using Helm (recommended)
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install redis bitnami/redis \
  --namespace clearportx \
  --set cluster.enabled=true \
  --set cluster.slaveCount=2 \
  --set master.persistence.enabled=true \
  --set auth.enabled=false

# Wait for Redis to be ready
kubectl -n clearportx wait --for=condition=ready pod -l app.kubernetes.io/name=redis --timeout=300s
```

### 3.3 Deploy ClearportX Backend
```bash
cd /root/cn-quickstart/quickstart/clearportx

# Set environment variables
export DOCKER_REGISTRY=ghcr.io/clearportx
export IMAGE_TAG=v2.1.0-hardened
export REDIS_HOST=redis-cluster.clearportx.svc.cluster.local

# Run deployment script
./deploy-devnet.sh
```

**This will deploy**:
- 3-pod backend deployment (HA + distributed rate limiting)
- ClusterIP service on port 8080
- Ingress with TLS (api.clearportx.devnet.canton.network)
- Prometheus ServiceMonitor

### 3.4 Verify Deployment
```bash
# Check pod status
kubectl -n clearportx get pods -l app=clearportx-backend

# Check logs
kubectl -n clearportx logs -l app=clearportx-backend -f --tail=50

# Test health endpoint
curl -fsS https://api.clearportx.devnet.canton.network/api/health/ledger | jq .
```

**Expected output**:
```json
{
  "status": "OK",
  "synced": true,
  "atomicSwapAvailable": true,
  "poolsActive": 0,
  "darVersion": "1.0.1",
  "clearportxPackageId": "..."
}
```

---

## Phase 4: Upload DAR to Canton Network

### 4.1 Upload DAR via Ledger API
```bash
# Option A: Using daml CLI (if you have Canton Network JWT)
daml ledger upload-dar \
  .daml/dist/clearportx-amm-1.0.1.dar \
  --host participant.devnet.canton.network \
  --port 443 \
  --tls \
  --access-token-file /path/to/jwt-token.txt

# Option B: Using Canton Console (via participant admin API)
# Contact Canton Network support for DAR upload assistance
```

### 4.2 Verify DAR Upload
```bash
# Check package ID appears in health endpoint
curl -fsS https://api.clearportx.devnet.canton.network/api/health/ledger | jq '.clearportxPackageId'
```

---

## Phase 5: Initialize Ledger (Pools + Tokens)

### 5.1 Create Initialization DAML Script

Since we can't run DAML scripts directly with Canton Network auth, we have two options:

**Option A: Use Backend API** (Recommended)
The backend has endpoints to create pools via AddLiquidity:
```bash
# First, get your party ID from Canton Network
YOUR_PARTY="..." # Provided by Canton Network onboarding

# Mint tokens via DAML script through Canton Console
# (Contact Canton Network support for party provisioning)
```

**Option B: Manual Initialization via Canton Console**
Contact Canton Network support to help initialize:
1. Mint ETH and USDC tokens for your party
2. Create empty pool
3. Add initial liquidity via `Pool.AddLiquidity`

### 5.2 Verify Pools Exist
```bash
curl -fsS https://api.clearportx.devnet.canton.network/api/pools | jq '.'
```

**Expected**: At least 1 pool with non-NULL `tokenACid` and `tokenBCid`

---

## Phase 6: End-to-End Testing

### 6.1 Run DevNet Smoke Test
```bash
cd /root/cn-quickstart/quickstart/clearportx

export BASE_URL=https://api.clearportx.devnet.canton.network
export ALICE_JWT="..." # Get from Canton Network OAuth2

./devnet-smoke-test.sh
```

**Tests**:
- ✅ Health endpoint returns correct DAR version
- ✅ Pools exist with valid canonicals
- ✅ Atomic swaps execute successfully
- ✅ Rate limiter enforces 0.4 TPS limit
- ✅ Fees are recorded correctly (75/25 split)
- ✅ Prometheus metrics are exported

### 6.2 Manual Swap Test
```bash
# Get OAuth2 token from Canton Network
TOKEN=$(curl -fsS "https://login.canton.network/realms/canton-network/protocol/openid-connect/token" \
  -d "client_id=YOUR_CLIENT_ID" \
  -d "client_secret=YOUR_SECRET" \
  -d "grant_type=client_credentials" | jq -r '.access_token')

# Execute atomic swap
curl -fsS -X POST "https://api.clearportx.devnet.canton.network/api/swap/atomic" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "poolId": "ETH-USDC",
    "inputSymbol": "USDC",
    "inputAmount": "1000.0",
    "outputSymbol": "ETH",
    "minOutputAmount": "0.3",
    "maxPriceImpactBps": 100,
    "deadlineEpochMs": '$(date +%s%3N)000'
  }' | jq .
```

**Expected response**:
```json
{
  "receiptCid": "00...",
  "poolId": "ETH-USDC",
  "inputSymbol": "USDC",
  "inputAmount": "1000.0",
  "outputSymbol": "ETH",
  "outputAmount": "0.333...",
  "executedAt": "2025-10-23T19:00:00Z"
}
```

### 6.3 Run Verification Script
```bash
export BASE_URL=https://api.clearportx.devnet.canton.network
./verify-devnet.sh
```

---

## Phase 7: Observability Setup

### 7.1 Deploy Prometheus Alerts
```bash
kubectl -n monitoring apply -f prometheus-clearportx-alerts.yml
```

### 7.2 Import Grafana Dashboard
```bash
# Upload grafana-clearportx-dashboard.json to Grafana
# Dashboards → Import → Upload JSON
cat grafana-clearportx-dashboard.json
```

---

## Success Criteria

- [ ] Backend deployed to Kubernetes (3 pods running)
- [ ] Health endpoint returns `status: "OK"`
- [ ] DAR uploaded to Canton Network ledger
- [ ] At least 1 pool with valid `tokenACid` and `tokenBCid`
- [ ] Atomic swap succeeds with HTTP 200
- [ ] Rate limiter returns HTTP 429 on rapid requests
- [ ] Prometheus metrics show `clearportx_swap_executed_total > 0`
- [ ] Fee split is 75% LP / 25% protocol
- [ ] Smoke test passes all checks

---

## Troubleshooting

### "UNAUTHENTICATED" error
- Verify Canton Network JWT is valid (check expiry)
- Confirm IP is whitelisted
- Check participant ID is correct

### "NO_VALID_POOL_CANONICALS" error
- Pools have NULL tokenACid/tokenBCid
- Need to add liquidity via `Pool.AddLiquidity` choice
- Contact Canton Network support for ledger initialization help

### "Health endpoint unreachable"
- Check ingress DNS propagation
- Verify TLS certificate issued by cert-manager
- Check pod logs: `kubectl -n clearportx logs -l app=clearportx-backend`

### "Rate limiter not working"
- Verify Redis is deployed and accessible
- Check `rate-limiter.distributed=true` in application-devnet.yml
- View logs for rate limiter messages

---

## Next Steps After Deployment

1. **Frontend Deployment**: Deploy frontend to Netlify/Vercel
   - Update CORS config in application-devnet.yml
   - Set `REACT_APP_BACKEND_URL=https://api.clearportx.devnet.canton.network`

2. **Monitoring**: Set up alerts for:
   - High error rate (>5%)
   - P95 latency >2s
   - Rate limit exceeded
   - K-invariant drift

3. **Load Testing**: Run load tests respecting 0.4 TPS limit

4. **Documentation**: Update API docs with DevNet endpoints

---

## Contact & Support

- **Canton Network Support**: Slack `#validator-operations`
- **DevNet Docs**: https://docs.dev.sync.global/validator_operator/
- **Onboarding Guide**: https://docs.dev.sync.global/validator_operator/validator_onboarding.html
