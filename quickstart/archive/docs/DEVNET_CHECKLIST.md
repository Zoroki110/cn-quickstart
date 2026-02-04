# Canton Network DevNet Deployment - Pre-Flight Checklist

**Date**: 2025-10-23
**Version**: v2.1.0-hardened
**DAR**: clearportx-amm-1.0.1
**Package ID**: `0b50244f981d3023d595164fa94ecba0cb139ed9ae320c9627d8f881d611b0de`

---

## ☑️ Phase 1: Canton Network Access (MUST HAVE)

### Access Requirements
- [ ] **IP Whitelisted**: Your egress IP approved by Canton Network SV (2-7 days)
  ```bash
  # Check your IP
  curl -sSL http://checkip.amazonaws.com
  # Test connectivity
  curl -fsS -m 5 https://scan.devnet.canton.network/api/scan/version
  ```

- [ ] **Participant ID**: Received from Canton Network onboarding
  - Format: `<validator-name>::<fingerprint>`
  - Example: `clearportx-validator-1::122013...`

- [ ] **PQS Credentials**: Database credentials for Canton Network PQS
  - JDBC URL: `jdbc:postgresql://pqs.devnet.canton.network:5432/pqs`
  - Username: `pqs_user`
  - Password: `_______________` (fill in)

- [ ] **OAuth2 Credentials**: For Ledger API authentication
  - Issuer: `https://login.canton.network/realms/canton-network`
  - Client ID: `_______________` (fill in)
  - Client Secret: `_______________` (fill in)

**🚫 BLOCKER**: Cannot proceed without IP whitelisting and Canton Network credentials

---

## ☑️ Phase 2: Infrastructure Ready

### Kubernetes Cluster
- [ ] kubectl installed and configured
  ```bash
  kubectl version --client
  kubectl cluster-info
  ```

- [ ] Namespace created
  ```bash
  kubectl create namespace clearportx
  ```

- [ ] Ingress controller installed (nginx)
  ```bash
  kubectl get ingressclass
  ```

- [ ] cert-manager installed (for TLS)
  ```bash
  kubectl get pods -n cert-manager
  ```

### Docker Registry
- [ ] Registry access configured (ghcr.io, DockerHub, or private)
  ```bash
  docker login ghcr.io
  ```

- [ ] Registry URL: `_______________` (fill in)
  - Example: `ghcr.io/clearportx`

### Redis Cluster
- [ ] Redis deployed to Kubernetes
  ```bash
  helm install redis bitnami/redis \
    --namespace clearportx \
    --set cluster.enabled=true \
    --set cluster.slaveCount=2
  ```

- [ ] Redis accessible from pods
  ```bash
  kubectl -n clearportx get svc | grep redis
  ```

---

## ☑️ Phase 3: Application Build

### DAR Package
- [x] **DAR Built**: clearportx-amm-1.0.1.dar (1.2 MB)
  ```bash
  ls -lh .daml/dist/clearportx-amm-1.0.1.dar
  ```

- [x] **Package ID**: `0b50244f981d3023d595164fa94ecba0cb139ed9ae320c9627d8f881d611b0de`

- [ ] **DAR Validated**: No compilation errors
  ```bash
  daml damlc inspect-dar .daml/dist/clearportx-amm-1.0.1.dar
  ```

### Backend JAR
- [ ] Gradle build successful
  ```bash
  cd /root/cn-quickstart/quickstart
  ../gradlew :backend:build -x test
  ```

- [ ] JAR file exists
  ```bash
  ls -lh backend/build/libs/backend-*.jar
  ```

### Docker Image
- [ ] Image built
  ```bash
  docker build -t <REGISTRY>/clearportx-backend:v2.1.0-hardened \
    -f backend/Dockerfile backend/
  ```

- [ ] Image pushed to registry
  ```bash
  docker push <REGISTRY>/clearportx-backend:v2.1.0-hardened
  ```

- [ ] Image tag: `_______________` (fill in)

---

## ☑️ Phase 4: Kubernetes Secrets

### Canton Network Credentials Secret
- [ ] Secret created
  ```bash
  kubectl -n clearportx create secret generic canton-network-creds \
    --from-literal=participant-id=YOUR_PARTICIPANT_ID \
    --from-literal=ledger-api-host=participant.devnet.canton.network \
    --from-literal=pqs-jdbc-url=jdbc:postgresql://pqs.devnet.canton.network:5432/pqs \
    --from-literal=pqs-username=pqs_user \
    --from-literal=pqs-password=YOUR_PQS_PASSWORD
  ```

- [ ] Secret validated
  ```bash
  kubectl -n clearportx get secret canton-network-creds -o yaml
  ```

### OAuth2 Secret (if needed)
- [ ] OAuth2 credentials stored
  ```bash
  kubectl -n clearportx create secret generic oauth2-creds \
    --from-literal=client-id=YOUR_CLIENT_ID \
    --from-literal=client-secret=YOUR_CLIENT_SECRET
  ```

---

## ☑️ Phase 5: Deploy Backend

### Deployment Script
- [ ] Environment variables set
  ```bash
  export DOCKER_REGISTRY=<your-registry>
  export IMAGE_TAG=v2.1.0-hardened
  export REDIS_HOST=redis-cluster.clearportx.svc.cluster.local
  ```

- [ ] Deployment executed
  ```bash
  cd /root/cn-quickstart/quickstart/clearportx
  ./deploy-devnet.sh
  ```

### Verify Deployment
- [ ] 3 pods running
  ```bash
  kubectl -n clearportx get pods -l app=clearportx-backend
  ```

- [ ] Service created
  ```bash
  kubectl -n clearportx get svc clearportx-backend
  ```

- [ ] Ingress configured
  ```bash
  kubectl -n clearportx get ingress clearportx-backend
  ```

- [ ] TLS certificate issued
  ```bash
  kubectl -n clearportx get certificate clearportx-api-tls
  ```

### Test Health Endpoint
- [ ] Health check passes
  ```bash
  curl -fsS https://api.clearportx.devnet.canton.network/api/health/ledger | jq .
  ```

**Expected**:
```json
{
  "status": "OK",
  "synced": true,
  "atomicSwapAvailable": true,
  "poolsActive": 0,
  "darVersion": "1.0.1"
}
```

---

## ☑️ Phase 6: Upload DAR to Canton Network

### Upload Process
- [ ] **Contact Canton Network Support** to upload DAR
  - Slack: `#validator-operations`
  - Provide: `clearportx-amm-1.0.1.dar` file
  - Package ID: `0b50244f981d3023d595164fa94ecba0cb139ed9ae320c9627d8f881d611b0de`

**Alternative** (if you have ledger API access):
```bash
daml ledger upload-dar \
  .daml/dist/clearportx-amm-1.0.1.dar \
  --host participant.devnet.canton.network \
  --port 443 \
  --tls \
  --access-token-file /path/to/jwt.txt
```

### Verify Upload
- [ ] Package ID appears in health endpoint
  ```bash
  curl -fsS https://api.clearportx.devnet.canton.network/api/health/ledger | \
    jq '.clearportxPackageId'
  ```

**Expected**: `"0b50244f981d3023d595164fa94ecba0cb139ed9ae320c9627d8f881d611b0de"`

---

## ☑️ Phase 7: Initialize Ledger (Pools + Tokens)

**⚠️ CRITICAL**: This requires Canton Network party provisioning and ledger access

### Option A: Via Canton Console (Recommended)
Contact Canton Network support to:
1. Allocate your party on the ledger
2. Mint initial tokens (ETH, USDC) for your party
3. Create empty pool
4. Add initial liquidity via `Pool.AddLiquidity`

### Option B: Via DAML Script (if you have auth)
```bash
# Requires Canton Network JWT token
daml script \
  --dar .daml/dist/clearportx-amm-1.0.1.dar \
  --script-name InitializeClearportX:initialize \
  --ledger-host participant.devnet.canton.network \
  --port 443 \
  --tls \
  --access-token-file /path/to/jwt.txt
```

### Verify Initialization
- [ ] Pools exist
  ```bash
  curl -fsS https://api.clearportx.devnet.canton.network/api/pools | jq 'length'
  ```

- [ ] Pools have valid canonicals (NOT NULL)
  ```bash
  curl -fsS https://api.clearportx.devnet.canton.network/api/pools | \
    jq '.[0] | {poolId, tokenACid, tokenBCid}'
  ```

**Expected**:
```json
{
  "poolId": "ETH-USDC",
  "tokenACid": "00a1b2c3...",  // NOT null
  "tokenBCid": "00d4e5f6..."   // NOT null
}
```

---

## ☑️ Phase 8: End-to-End Testing

### Smoke Test
- [ ] Run smoke test script
  ```bash
  export BASE_URL=https://api.clearportx.devnet.canton.network
  export ALICE_JWT="<your-jwt-token>"
  ./devnet-smoke-test.sh
  ```

### Manual Swap Test
- [ ] Get OAuth2 token
  ```bash
  TOKEN=$(curl -fsS "https://login.canton.network/realms/canton-network/protocol/openid-connect/token" \
    -d "client_id=YOUR_CLIENT_ID" \
    -d "client_secret=YOUR_SECRET" \
    -d "grant_type=client_credentials" | jq -r '.access_token')
  ```

- [ ] Execute atomic swap
  ```bash
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
      "deadlineEpochMs": '$(date +%s)000'
    }' | jq .
  ```

**Expected**: HTTP 200 with receipt CID

### Verification Script
- [ ] Run verification
  ```bash
  ./verify-devnet.sh
  ```

**All checks must pass**:
- ✅ Health: OK
- ✅ Synced: true
- ✅ Atomic Swap: Available
- ✅ Success Rate: ≥98%
- ✅ Active Pools: >0
- ✅ Fee Split: 75/25
- ✅ Metrics: Exported

---

## ☑️ Phase 9: Observability

### Prometheus
- [ ] ServiceMonitor deployed
  ```bash
  kubectl -n clearportx get servicemonitor clearportx-backend
  ```

- [ ] Metrics scraped
  ```bash
  curl -fsS https://api.clearportx.devnet.canton.network/api/actuator/prometheus | \
    grep clearportx_swap
  ```

### Grafana
- [ ] Dashboard imported
  - File: `grafana-clearportx-dashboard.json`
  - URL: `https://grafana.yourcluster.com`

### Alerts
- [ ] Prometheus alerts deployed
  ```bash
  kubectl -n monitoring apply -f prometheus-clearportx-alerts.yml
  ```

---

## ☑️ Phase 10: Production Ready

### Final Checks
- [ ] 3 backend pods healthy
- [ ] Health endpoint <200ms response time
- [ ] Swap success rate ≥98%
- [ ] Rate limiter enforcing 0.4 TPS (HTTP 429)
- [ ] Prometheus metrics exported
- [ ] Grafana dashboard showing live data
- [ ] Alerts configured and firing

### Documentation
- [ ] API endpoints documented
- [ ] Frontend updated with DevNet backend URL
- [ ] Runbook created for on-call

---

## 🚀 GO/NO-GO Decision

**READY FOR DEVNET** if all these are TRUE:
- ✅ Canton Network access working (IP whitelisted)
- ✅ 3 backend pods running and healthy
- ✅ DAR uploaded to Canton Network ledger
- ✅ At least 1 pool with valid canonicals
- ✅ Atomic swap succeeds with HTTP 200
- ✅ Rate limiter returns HTTP 429
- ✅ Metrics show swaps executed
- ✅ Fee split is correct (75/25)

**BLOCKERS** (cannot launch):
- ❌ IP not whitelisted (2-7 day wait)
- ❌ No Canton Network credentials
- ❌ NULL pool canonicals (need initialization)
- ❌ Swaps failing with errors
- ❌ Rate limiter not working

---

## Contact & Escalation

**Canton Network Support**:
- Slack: `#validator-operations`
- Email: support@canton.network
- Docs: https://docs.dev.sync.global/

**ClearportX Team**:
- On-call: <your-contact>
- Slack: <your-slack>

---

## Next Actions

1. **Fill in all `_______________` fields** with your credentials
2. **Work through checklist top to bottom**
3. **Stop at first ❌ BLOCKER** and resolve before continuing
4. **Run smoke test** when all phases complete
5. **Make GO/NO-GO decision** based on final checks

**Current Status**: 📋 Ready for Canton Network access provisioning
