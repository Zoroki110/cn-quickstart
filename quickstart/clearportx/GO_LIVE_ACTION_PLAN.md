# ClearportX Canton Network DevNet - GO LIVE Action Plan

**Date:** October 26, 2025
**Current Status:** Phase 1 Complete - Pools visible on Netlify via ngrok tunnel
**Target:** Production launch on Canton Network DevNet

---

## Executive Summary

ClearportX is an institutional-grade DEX built on Canton Network. We are currently testing on DevNet with real Canton smart contracts. This document outlines the complete path to production launch.

### Current Infrastructure
- **Frontend:** https://app.clearportx.com (Netlify, auto-deploys from GitHub)
- **Backend:** Spring Boot 3.4.2 on localhost:8080 → ngrok tunnel
- **Ledger:** Canton Network DevNet (14 Super Validators, BFT consensus)
- **ngrok Tunnel:** https://nonexplicable-lacily-leesa.ngrok-free.dev
- **Pools:** 1 ETH-USDC pool (100 ETH / 200,000 USDC reserves)
- **Party:** app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388

---

## Phase 1: UX Improvements ✅ COMPLETE

**Status:** Deployed to Netlify (commit 953030c6)

**Changes Made:**
- ✅ Removed yellow "Devnet – Auth désactivée" banner showing app-provider ID
- ✅ Replaced long party ID in header with clean "Connected" status
- ✅ Added subtle "DevNet" badge in bottom corner (dev mode only)
- ✅ Pools now publicly visible without authentication required
- ✅ Users can browse pools before connecting wallet

**Result:** Clean, professional UI that doesn't expose technical internals to users.

---

## Phase 2: Canton Loop Wallet Integration

**Goal:** Enable users to connect their Canton Loop wallet for authenticated operations (swaps, liquidity provision).

### Prerequisites
1. Canton Loop wallet documentation review
2. Canton Network DevNet wallet connection endpoint
3. JWT token flow from Canton Loop → Backend

### Tasks

#### 2.1 Research Canton Loop Integration
- [ ] Read Canton Loop docs: https://docs.canton.network/canton-loop
- [ ] Identify wallet connection SDK/library
- [ ] Understand party ID extraction from Canton Loop
- [ ] Understand JWT token format from Canton Loop

#### 2.2 Frontend Wallet Connection
**File:** `/root/canton-website/app/src/services/auth.ts`

Current state: Mock auth with `DISABLE_AUTH=true`

Changes needed:
```typescript
// Replace mock login with Canton Loop connection
async connectCantonLoop(): Promise<{ token: string, party: string }> {
  // 1. Open Canton Loop wallet popup
  // 2. Request user authorization
  // 3. Receive JWT token + party ID
  // 4. Store in localStorage
  // 5. Inject X-Party header in all API calls
}
```

#### 2.3 Backend OAuth2 Re-enabling
**File:** `/root/cn-quickstart/quickstart/backend/src/main/java/com/digitalasset/quickstart/security/DevNetSecurityConfig.java`

Current state: OAuth2 disabled (`.anyRequest().permitAll()`)

Changes needed:
```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
  http
    .cors(Customizer.withDefaults())
    .csrf(csrf -> csrf.disable())
    .authorizeHttpRequests(auth -> auth
      // Public endpoints
      .requestMatchers("/api/health/**", "/api/pools").permitAll()

      // Authenticated endpoints
      .requestMatchers("/api/swap/**", "/api/liquidity/**").authenticated()
    )
    // Re-enable OAuth2 with Canton Loop issuer
    .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
      .jwkSetUri("https://canton-network-devnet/oauth2/jwks")
      .issuer("https://canton-network-devnet")
    ));

  return http.build();
}
```

#### 2.4 Testing Checklist
- [ ] Can open Canton Loop wallet popup
- [ ] Can authorize ClearportX application
- [ ] Receive valid JWT token
- [ ] Party ID matches Canton Network format
- [ ] JWT validates on backend
- [ ] X-Party header correctly extracted
- [ ] Pools still visible when NOT connected
- [ ] Swap button requires connection
- [ ] Add Liquidity button requires connection

**Estimated Time:** 4-6 hours

---

## Phase 3: Create Test Users with Tokens on DevNet

**Goal:** Create Alice and Bob parties with real ETH/USDC tokens on Canton Network DevNet for end-to-end testing.

### Current State
- Only `app-provider` party exists with tokens
- No real user accounts configured on DevNet

### Tasks

#### 3.1 Onboard Alice to Canton Network DevNet
**Method:** Canton Network onboarding portal or DAML script

Option A: Canton Network Portal
```bash
# Request onboarding via Canton Network DevNet portal
# Wait for participant node allocation
# Receive: alice-participant::122...
```

Option B: DAML Script (if we have admin access)
```daml
-- File: daml/OnboardAlice.daml
module OnboardAlice where

import Daml.Script
import Token.Token

onboardAlice : Script ()
onboardAlice = script do
  -- Allocate alice party
  alice <- allocateParty "alice"

  -- Mint 1000 ETH for alice
  ethIssuer <- allocateParty "eth-issuer"
  submit ethIssuer do
    createCmd Token with
      issuer = ethIssuer
      owner = alice
      symbol = "ETH"
      amount = 1000.0

  -- Mint 50000 USDC for alice
  usdcIssuer <- allocateParty "usdc-issuer"
  submit usdcIssuer do
    createCmd Token with
      issuer = usdcIssuer
      owner = alice
      symbol = "USDC"
      amount = 50000.0
```

#### 3.2 Onboard Bob to Canton Network DevNet
Same process as Alice with different amounts:
- Bob: 500 ETH + 100,000 USDC

#### 3.3 Verify Token Balances
```bash
# Check alice's tokens
daml ledger list --party alice --host devnet.canton.network --port 443

# Check bob's tokens
daml ledger list --party bob --host devnet.canton.network --port 443
```

**Estimated Time:** 2-3 hours (depends on Canton Network onboarding process)

---

## Phase 4: Test Complete Swap Flow with Canton Loop

**Goal:** Execute end-to-end swap from Netlify frontend → ngrok → backend → Canton Network DevNet ledger.

### Test Scenarios

#### 4.1 Alice Swaps 10 ETH → USDC
**Steps:**
1. Open https://app.clearportx.com
2. Click "Connect Wallet"
3. Authorize Canton Loop with Alice's wallet
4. Navigate to Swap page
5. Input: 10 ETH
6. Output: ~19,600 USDC (accounting for slippage + fees)
7. Click "Swap"
8. Sign transaction in Canton Loop
9. Wait for Canton Network consensus (BFT, ~2-5 seconds)
10. Verify swap completed
11. Check transaction in History page
12. Verify pool reserves updated

**Expected Results:**
- Swap executes successfully
- Alice receives USDC
- Pool reserves: 110 ETH / 181,818 USDC (approx, depending on AMM math)
- Transaction recorded on Canton Network ledger
- No errors in browser console
- Backend logs show successful `AtomicSwap.daml` execution

#### 4.2 Bob Swaps 50,000 USDC → ETH
Reverse swap to test the other direction.

#### 4.3 Edge Cases to Test
- [ ] Swap with insufficient balance (should fail gracefully)
- [ ] Swap with excessive slippage (> 5%, should warn user)
- [ ] Swap while disconnected (should prompt to connect)
- [ ] Swap with invalid token amounts (negative, zero)
- [ ] Concurrent swaps from Alice and Bob (test BFT consensus)
- [ ] Swap with network timeout (backend unreachable)

**Estimated Time:** 3-4 hours

---

## Phase 5: Test Add Liquidity Flow

**Goal:** Test liquidity provision from frontend → Canton Network.

### Test Scenarios

#### 5.1 Alice Adds Liquidity: 50 ETH + 100,000 USDC
**Steps:**
1. Navigate to Liquidity page
2. Select ETH-USDC pool
3. Input: 50 ETH
4. Auto-calculate: 100,000 USDC (to maintain pool ratio)
5. Click "Add Liquidity"
6. Sign transaction in Canton Loop
7. Receive LP tokens representing pool share
8. Verify LP tokens in wallet
9. Check pool reserves increased
10. Check pool stats updated (TVL, user share %)

**Expected Results:**
- Liquidity added successfully
- Alice receives LP tokens
- Pool reserves: 150 ETH / 300,000 USDC
- Alice's pool share: ~33.3%
- TVL increased correctly in UI

#### 5.2 Bob Adds Liquidity: 25 ETH + 50,000 USDC

#### 5.3 Alice Removes Liquidity: Burn 50% of LP Tokens
**Steps:**
1. Navigate to Liquidity page
2. Select "Remove Liquidity"
3. Choose 50% of LP tokens to burn
4. Preview: Will receive back ~25 ETH + ~50,000 USDC (plus accumulated fees)
5. Confirm transaction
6. Verify tokens returned
7. Check pool share decreased

#### 5.4 Edge Cases to Test
- [ ] Add liquidity with unbalanced amounts (should adjust to pool ratio)
- [ ] Add liquidity with insufficient balance
- [ ] Remove 100% liquidity (close position entirely)
- [ ] Remove liquidity from pool you don't have LP tokens for
- [ ] Add minimal liquidity (dust amounts)
- [ ] Add massive liquidity (test integer overflow handling)

**Estimated Time:** 3-4 hours

---

## Phase 6: Verify Ledger Integrity on Canton Network

**Goal:** Ensure ALL operations are correctly recorded on the Canton Network ledger with proper audit trail.

### Verification Methods

#### 6.1 Query Active Contracts (ACS)
```bash
# Check all active pools
daml ledger list --party app-provider --filter "AMM.Pool" --host devnet.canton.network --port 443

# Check all active tokens for alice
daml ledger list --party alice --filter "Token.Token" --host devnet.canton.network --port 443

# Check LP tokens
daml ledger list --party alice --filter "AMM.LPToken" --host devnet.canton.network --port 443
```

#### 6.2 Check Transaction History
```bash
# Get transaction log for last 24 hours
daml ledger query --party app-provider --from $(date -u -d '1 day ago' +%Y-%m-%dT%H:%M:%SZ) \
  --host devnet.canton.network --port 443
```

#### 6.3 Verify Protocol Fees
**File:** `daml/AMM/ProtocolFee.daml`

Check that protocol fees are correctly collected:
- 0.3% total swap fee
- 75% to LPs (distributed proportionally)
- 25% to ClearportX treasury

```bash
# Check protocol fee contracts
daml ledger list --party clearportx-treasury --filter "AMM.ProtocolFee" \
  --host devnet.canton.network --port 443
```

#### 6.4 Audit Pool K Invariant
**Mathematical Verification:**

For constant product AMM: `k = reserveA * reserveB`

Before any swap: `k_initial = 100 * 200000 = 20,000,000`

After swap (example): `k_after = 110 * 181818 = 19,999,980`

Difference should ONLY be due to protocol fees (0.3%).

**Verification Script:**
```bash
# File: verify-k-invariant.sh
INITIAL_K=20000000

# Query current pool reserves
CURRENT_A=$(curl -s https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools \
  -H "ngrok-skip-browser-warning: true" | jq '.[0].reserveA')

CURRENT_B=$(curl -s https://nonexplicable-lacily-leesa.ngrok-free.dev/api/pools \
  -H "ngrok-skip-browser-warning: true" | jq '.[0].reserveB')

CURRENT_K=$((CURRENT_A * CURRENT_B))

DRIFT=$(awk "BEGIN {print (($CURRENT_K - $INITIAL_K) / $INITIAL_K) * 100}")

echo "K invariant drift: $DRIFT%"
if (( $(echo "$DRIFT > 0.5" | bc -l) )); then
  echo "❌ ALERT: K invariant drift exceeds 0.5% - potential AMM bug!"
  exit 1
else
  echo "✅ K invariant within acceptable range"
fi
```

#### 6.5 Check PQS (Participant Query Store) Sync Status
```bash
curl -s http://localhost:8080/api/health/ledger | jq .
```

Expected:
```json
{
  "synced": true,
  "offset": 4521,
  "status": "OK"
}
```

**Estimated Time:** 2-3 hours

---

## Phase 7: Deploy to Permanent Infrastructure

**Goal:** Replace ngrok tunnel with production-grade infrastructure.

### Current Architecture (TEMPORARY)
```
Netlify (Frontend) → ngrok (Tunnel) → localhost:8080 (Backend) → Canton Network DevNet
```

### Target Architecture (PRODUCTION)
```
Netlify (Frontend) → AWS ALB (HTTPS) → EC2/ECS (Backend) → Canton Network DevNet
                                        ↓
                                    RDS PostgreSQL (PQS Database)
                                        ↓
                                    Redis (Rate Limiter)
```

### Infrastructure Requirements

#### 7.1 Backend Hosting Options

**Option A: AWS EC2** (Simple, good for MVP)
- Instance type: t3.medium (2 vCPU, 4 GB RAM)
- OS: Ubuntu 22.04 LTS
- Java 21 installed
- Docker for dependencies (PostgreSQL, Redis)
- Elastic IP for static addressing
- Security Group: Allow 443 (HTTPS), 22 (SSH admin only)

**Option B: AWS ECS Fargate** (Scalable, production-ready)
- Container: `clearportx-backend:v2.1.0-hardened`
- Task definition: 2 vCPU, 4 GB RAM
- Auto-scaling: 2-10 tasks based on CPU utilization
- Application Load Balancer (ALB) with TLS termination
- ECS Service Discovery for internal routing

**Option C: Google Cloud Run** (Serverless, cost-effective)
- Container image: gcr.io/clearportx/backend:latest
- Max instances: 10
- Min instances: 1 (always warm)
- CPU: 2, Memory: 4 GB
- HTTPS automatic (Google-managed certificates)

**Recommendation:** Start with AWS EC2 (Option A) for DevNet testing, migrate to ECS Fargate (Option B) for production.

#### 7.2 Database (PQS)

**Current:** In-memory H2 database (VOLATILE, loses data on restart)

**Production:**
- AWS RDS PostgreSQL 15.x
- Instance: db.t3.medium (2 vCPU, 4 GB RAM)
- Storage: 100 GB SSD (GP3)
- Multi-AZ: No (for DevNet), Yes (for Production)
- Automated backups: Daily, 7-day retention
- Endpoint: `clearportx-pqs.c9abc123xyz.us-east-1.rds.amazonaws.com:5432`

**Migration:**
```bash
# Update application-devnet.yml
spring:
  datasource:
    url: jdbc:postgresql://clearportx-pqs.c9abc123xyz.us-east-1.rds.amazonaws.com:5432/pqs_db
    username: pqs_user
    password: ${PQS_DB_PASSWORD}  # Store in AWS Secrets Manager
  jpa:
    hibernate:
      ddl-auto: validate  # Use Flyway for schema migrations
```

#### 7.3 Redis (Distributed Rate Limiter)

**Current:** Local in-memory rate limiter (does NOT work across multiple backend instances)

**Production:**
- AWS ElastiCache Redis 7.x
- Node type: cache.t3.micro (0.5 GB RAM) - sufficient for rate limiter
- Cluster mode: Disabled (single shard)
- Endpoint: `clearportx-redis.abc123.0001.use1.cache.amazonaws.com:6379`

**Configuration:**
```yaml
# application-devnet.yml
rate-limiter:
  distributed: true
  redis:
    host: clearportx-redis.abc123.0001.use1.cache.amazonaws.com
    port: 6379
    password: ${REDIS_PASSWORD}
```

#### 7.4 Domain and SSL/TLS

**Current:** ngrok free tier (randomized subdomain, HTML warning page)

**Production:**
- Domain: `api.clearportx.com` (CNAME to ALB or EC2 Elastic IP)
- SSL Certificate: AWS Certificate Manager (ACM) - FREE
- HTTPS enforcement: Redirect all HTTP → HTTPS
- CORS origins: Update to `https://api.clearportx.com`

**DNS Configuration:**
```
Type: CNAME
Name: api.clearportx.com
Value: clearportx-alb-1234567890.us-east-1.elb.amazonaws.com
TTL: 300
```

#### 7.5 Deployment Checklist

- [ ] Provision AWS EC2 instance (t3.medium)
- [ ] Install Java 21, Docker, nginx
- [ ] Setup RDS PostgreSQL database
- [ ] Setup ElastiCache Redis
- [ ] Configure security groups (ALB → EC2, EC2 → RDS/Redis)
- [ ] Build backend JAR: `./gradlew build -x test`
- [ ] Upload JAR to EC2: `scp backend.jar ec2-user@api.clearportx.com:/opt/clearportx/`
- [ ] Create systemd service for auto-restart
- [ ] Configure nginx reverse proxy (HTTP → localhost:8080)
- [ ] Request ACM certificate for api.clearportx.com
- [ ] Attach ACM certificate to ALB
- [ ] Update Netlify env: `REACT_APP_BACKEND_API_URL=https://api.clearportx.com`
- [ ] Update backend CORS: Add `https://api.clearportx.com` to allowed origins
- [ ] Deploy frontend: `git push fork main` (triggers Netlify build)
- [ ] Smoke test: Open https://app.clearportx.com, verify pools load
- [ ] Load test: Use Apache JMeter to simulate 100 concurrent users
- [ ] Monitor: Setup CloudWatch alarms for CPU, memory, error rate
- [ ] Backup: Configure automated RDS snapshots

**Estimated Time:** 8-12 hours (first time), 2-3 hours (subsequent deploys)

---

## Phase 8: Final Smoke Tests and Go Live

**Goal:** Comprehensive pre-launch testing across all critical user journeys.

### 8.1 Functional Testing

**Pools Page**
- [ ] Loads without authentication
- [ ] Displays correct pool reserves
- [ ] Shows correct TVL, APR, 24h volume
- [ ] Responsive on mobile (iPhone, Android)
- [ ] Dark mode works correctly

**Swap Page**
- [ ] Requires Canton Loop connection
- [ ] Token selection dropdown works
- [ ] Amount input validates correctly
- [ ] Slippage calculation accurate
- [ ] Price impact warning shows for large trades
- [ ] "Insufficient balance" error shows correctly
- [ ] Swap executes successfully
- [ ] Transaction appears in History page
- [ ] Pool reserves update immediately after swap

**Liquidity Page**
- [ ] Requires Canton Loop connection
- [ ] Add liquidity calculates correct token amounts
- [ ] LP tokens received match expected amount
- [ ] Pool share % calculates correctly
- [ ] Remove liquidity returns correct token amounts
- [ ] LP token balance updates after add/remove

**History Page**
- [ ] Shows all user transactions
- [ ] Timestamp displays correctly (local timezone)
- [ ] Transaction details (amounts, fees) accurate
- [ ] Link to Canton Network explorer works
- [ ] Pagination works for >20 transactions

### 8.2 Performance Testing

**Load Test Scenario:**
- 100 concurrent users
- 50% viewing pools (read-only)
- 30% executing swaps
- 20% adding/removing liquidity
- Duration: 10 minutes
- Target: P95 latency < 2 seconds

**Tool:** Apache JMeter
```bash
# Run load test
jmeter -n -t clearportx-load-test.jmx -l results.jtl -e -o report/

# Check P95 latency
cat report/statistics.json | jq '.overall.percentiles.p95'
```

**Success Criteria:**
- P95 latency < 2000 ms
- Error rate < 1%
- No memory leaks (heap usage stable)
- No database connection pool exhaustion
- Rate limiter correctly rejects excess traffic (>0.4 TPS per user)

### 8.3 Security Testing

**CORS Verification**
```bash
# Test from allowed origin
curl -I -X OPTIONS https://api.clearportx.com/api/pools \
  -H "Origin: https://app.clearportx.com"
# Expected: 200 OK, Access-Control-Allow-Origin: https://app.clearportx.com

# Test from unauthorized origin
curl -I -X OPTIONS https://api.clearportx.com/api/pools \
  -H "Origin: https://evil.com"
# Expected: 403 Forbidden
```

**JWT Token Validation**
```bash
# Test with invalid token
curl -X POST https://api.clearportx.com/api/swap/execute \
  -H "Authorization: Bearer fake-token-12345" \
  -H "Content-Type: application/json" \
  -d '{"poolId": "..."}'
# Expected: 401 Unauthorized
```

**Rate Limiting**
```bash
# Send 100 requests in 1 second (exceeds 0.4 TPS limit)
for i in {1..100}; do
  curl -s https://api.clearportx.com/api/pools &
done
wait

# Expected: ~40 requests succeed (200 OK), ~60 requests fail (429 Too Many Requests)
```

**SQL Injection Protection**
```bash
# Attempt SQL injection in pool query
curl "https://api.clearportx.com/api/pools?id=1'; DROP TABLE pools; --"
# Expected: 400 Bad Request (input validation), NOT database error
```

### 8.4 Monitoring Setup

**Metrics to Track:**
- Request rate (requests/second)
- P50, P95, P99 latency (milliseconds)
- Error rate (%)
- Canton Network sync status (PQS offset)
- Active pool count
- Total TVL (USD)
- Swap volume (24h, 7d, 30d)
- Unique users (daily, weekly, monthly)

**Alerting Rules:**
- Error rate > 5% for 5 minutes → PagerDuty alert
- P95 latency > 2000ms for 5 minutes → Slack alert
- PQS sync lag > 100 offsets → Immediate investigation
- Backend instance down → Auto-restart + alert
- Database CPU > 80% for 10 minutes → Scale up instance

**Tools:**
- Metrics: Prometheus (scrapes `/actuator/prometheus` endpoint)
- Visualization: Grafana dashboard (`grafana-clearportx-dashboard.json`)
- Alerting: Prometheus AlertManager → PagerDuty
- Logs: CloudWatch Logs (centralized logging)
- Tracing: OpenTelemetry → Jaeger (distributed tracing)

### 8.5 Go-Live Checklist

**Pre-Launch (T-24 hours)**
- [ ] All Phase 2-7 tasks complete
- [ ] Load tests passed
- [ ] Security audit complete (no critical vulnerabilities)
- [ ] Monitoring and alerting operational
- [ ] Database backups verified (can restore)
- [ ] Rollback plan documented
- [ ] Team notified of launch window

**Launch Day (T-0)**
- [ ] Deploy latest backend version to production
- [ ] Deploy latest frontend to Netlify
- [ ] Smoke test critical paths (pools, swap, liquidity)
- [ ] Monitor error rates for 1 hour
- [ ] Announce launch on social media (Twitter, Discord, Telegram)
- [ ] Monitor user activity in real-time

**Post-Launch (T+24 hours)**
- [ ] Review metrics (request rate, errors, latency)
- [ ] Check Canton Network ledger integrity
- [ ] Interview 5 early users for feedback
- [ ] Document any issues encountered
- [ ] Plan next iteration (new features, optimizations)

**Estimated Time:** 6-8 hours

---

## Success Metrics

### Technical Metrics
- **Uptime:** 99.5% (allowing 3.6 hours downtime per month for DevNet maintenance)
- **Latency:** P95 < 2 seconds
- **Error Rate:** < 1%
- **Canton Network Sync:** PQS lag < 10 offsets

### Business Metrics
- **TVL:** $500K within 30 days
- **Daily Active Users:** 50+ within 30 days
- **Daily Swap Volume:** $50K within 30 days
- **Unique Wallets Connected:** 100+ within 30 days

### User Experience Metrics
- **Time to First Swap:** < 5 minutes (from landing page → completed swap)
- **Bounce Rate:** < 50% (users who leave without interacting)
- **Swap Completion Rate:** > 80% (users who start swap → complete it)

---

## Risk Mitigation

### Risk 1: Canton Network DevNet Downtime
**Impact:** HIGH
**Probability:** MEDIUM (DevNet is for testing)
**Mitigation:**
- Monitor Canton Network status page: https://status.canton.network
- Display user-friendly error message when Canton Network unreachable
- Implement exponential backoff for retries (3 attempts, max 30s)
- Subscribe to Canton Network maintenance announcements

### Risk 2: ngrok Tunnel Failure (Before Phase 7)
**Impact:** HIGH
**Probability:** MEDIUM (ngrok free tier is unstable)
**Mitigation:**
- Keep ngrok process running in `tmux` session (persists across SSH disconnects)
- Monitor ngrok tunnel status every 5 minutes
- Auto-restart ngrok if tunnel dies
- Complete Phase 7 (permanent infrastructure) ASAP

### Risk 3: Smart Contract Bug (AMM Math Error)
**Impact:** CRITICAL
**Probability:** LOW (code already tested)
**Mitigation:**
- Audit AMM math before launch (already done in previous sessions)
- Implement circuit breaker: Pause all swaps if K invariant drifts > 1%
- Test with small amounts first ($100 swaps, not $100K)
- Have DAML upgrade path ready (new DAR version)

### Risk 4: Rate Limiter Bypass
**Impact:** MEDIUM
**Probability:** LOW
**Mitigation:**
- Use distributed rate limiter (Redis) instead of local in-memory
- Implement multiple rate limiting layers (IP-based, user-based, global)
- Monitor request patterns for anomalies
- Implement CAPTCHA for suspicious traffic

### Risk 5: Private Key Compromise
**Impact:** CRITICAL
**Probability:** VERY LOW
**Mitigation:**
- NEVER store private keys in backend
- ALL operations require user signature via Canton Loop
- Backend only holds JWT tokens (short-lived, 1 hour expiry)
- Use AWS Secrets Manager for database passwords, API keys

---

## Open Questions for Canton Network Team

1. **Canton Loop Integration:** Is there a JavaScript SDK for Canton Loop wallet connection? If not, what's the recommended integration approach?

2. **Devnet Onboarding:** How do we onboard new parties (Alice, Bob) to Canton Network DevNet? Is there a self-service portal or do we need to contact support?

3. **Production Readiness:** What are the requirements to migrate from DevNet to Canton Network Production (Mainnet)? Are there additional audits or compliance requirements?

4. **Rate Limits:** Are there any Canton Network-side rate limits we should be aware of? (e.g., max transactions per second per participant)

5. **Monitoring:** Does Canton Network provide any observability endpoints (Prometheus metrics, health checks) that we can integrate into our monitoring stack?

6. **Wallet Support:** Besides Canton Loop, are there other wallets (MetaMask-style browser extensions) that support Canton Network? Should we plan for multi-wallet support?

7. **Protocol Fees:** The current fee structure is 0.3% total (75% LPs, 25% ClearportX). Is this standard for Canton Network apps, or should we align with any network-wide fee conventions?

---

## Conclusion

This action plan provides a comprehensive roadmap from current state (Netlify + ngrok + DevNet) to production launch on Canton Network. The critical path is:

1. ✅ Phase 1: UX improvements (DONE)
2. Phase 2: Canton Loop integration (4-6 hours)
3. Phase 3: Test users on DevNet (2-3 hours)
4. Phase 4-6: End-to-end testing (8-11 hours)
5. Phase 7: Production infrastructure (8-12 hours)
6. Phase 8: Go live (6-8 hours)

**Total Estimated Time:** 28-40 hours of focused work

**Recommended Approach:** Execute phases sequentially over 1-2 weeks, with daily check-ins to review progress and adjust plan as needed.

**Next Immediate Step:** Research Canton Loop wallet integration (Phase 2.1) and reach out to Canton Network team with open questions.

---

**Document Version:** 1.0
**Last Updated:** October 26, 2025
**Author:** Claude (AI Assistant)
**Status:** Ready for Review
