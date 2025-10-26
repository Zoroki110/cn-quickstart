# ClearportX Devnet Onboarding Request

**Date:** October 22, 2025  
**Organization:** ClearportX  
**Contact:** [Your Name/Email]

## Application Overview

**ClearportX DEX** - Decentralized Exchange on Canton Network with:
- ✅ Atomic swap functionality (ETH, USDC, BTC, USDT)
- ✅ AMM liquidity pools with protocol fees (25% ClearportX / 75% LP)
- ✅ Production-grade rate limiting (0.4 TPS - under 0.5 TPS devnet limit)
- ✅ OAuth2/JWT authentication
- ✅ Full observability (Prometheus metrics, structured logging)
- ✅ Idempotency protection for all state-changing operations

## Technical Stack

- **Backend:** Spring Boot 3.4.2 + DAML SDK 3.3.0
- **Frontend:** React 18 + TypeScript
- **Auth:** OAuth2 with Keycloak integration
- **Monitoring:** Prometheus + Grafana + OpenTelemetry

## SV Connectivity Test Results

**Test Date:** October 22, 2025

Successfully connected to **10 out of 14 SVs** (71% success rate):

✅ Accessible SVs:
- sv.dev.global.canton.network.sv-nodeops.com (v0.4.22)
- sv-2.dev.global.canton.network.digitalasset.com (v0.4.22)
- sv-1.dev.global.canton.network.fivenorth.io (v0.4.22)
- sv.dev.global.canton.network.digitalasset.com (v0.4.22)
- sv-1.dev.global.canton.network.tradeweb.com (v0.4.22)
- sv-1.dev.global.canton.network.sync.global (v0.4.22)
- sv-1.dev.global.canton.network.cumberland.io (v0.4.22)
- sv-1.dev.global.canton.network.proofgroup.xyz (v0.4.22)
- sv-2.dev.global.canton.network.cumberland.io (v0.4.22)
- sv-1.dev.global.canton.network.digitalasset.com (v0.4.22)

⚠️ Temporary errors (likely rate limiting or maintenance):
- sv-1.dev.global.canton.network.orb1lp.mpch.io (403)
- sv-1.dev.global.canton.network.mpch.io (503)
- sv-1.dev.global.canton.network.c7.digital (timeout)
- sv-1.dev.global.canton.network.lcv.mpch.io (403)

## Required Information from Canton Network

To complete devnet deployment, we need:

### 1. **Participant Configuration**
```yaml
REGISTRY_BASE_URI: https://TBD_DEVNET_PARTICIPANT_ENDPOINT
```

### 2. **OAuth2/Keycloak Configuration**
```yaml
KEYCLOAK_ISSUER_URI: https://TBD_DEVNET_KEYCLOAK/realms/AppProvider
KEYCLOAK_JWK_SET_URI: https://TBD_DEVNET_KEYCLOAK/realms/AppProvider/protocol/openid-connect/certs
```

### 3. **Party ID Assignment**
```yaml
APP_PROVIDER_PARTY: TBD_ASSIGNED_PARTY_ID
```

### 4. **IP Whitelist Approval**
Our backend server IP: `5.9.70.48`

## Compliance Confirmation

✅ **Rate Limiting:** Configured at 0.4 TPS (20% safety margin below 0.5 TPS limit)  
✅ **Distributed Rate Limiter:** Redis-based for cluster safety  
✅ **Production Security:**
- No internal errors exposed to clients
- JWT validation with multi-issuer support
- CORS restricted to whitelisted origins
- Idempotency keys for all mutations

✅ **Monitoring:**
- Prometheus metrics exported at `/api/actuator/prometheus`
- Structured JSON logging with trace IDs
- Grafana dashboards for pool metrics, swap metrics, health metrics

✅ **DAML Contracts:**
- Package ID: `0b50244f981d3023d595164fa94ecba0cb139ed9ae320c9627d8f881d611b0de`
- Version: `clearportx-amm:1.0.1`
- Templates: Pool, Token, AtomicSwap, Receipt, LPToken

## Testing Performed

1. ✅ **Local Testing:** 3 atomic swaps executed successfully (ETH ↔ USDC)
2. ✅ **Frontend Integration:** OAuth login + real-time quotes + swap execution
3. ✅ **Rate Limiter:** Token bucket algorithm with 0.4 TPS global limit
4. ✅ **Idempotency:** Duplicate requests detected and cached (15-min TTL)
5. ✅ **PQS Sync:** Active contract state synchronized with retry logic

## Deployment Plan

Once credentials provided:

1. Update `application-devnet.yml` with Canton Network credentials
2. Deploy backend with `SPRING_PROFILES_ACTIVE=devnet`
3. Update frontend `.env.production` with devnet backend URL
4. Deploy frontend to `https://canton.clearportx.com`
5. Smoke test: Execute test swap with devnet credentials
6. Monitor metrics for 24h to confirm stability

## Contact Information

**Technical Contact:** [Your Email]  
**Server IP:** 5.9.70.48  
**GitHub:** [Your Repo URL]

---

**Ready for devnet onboarding!** 🚀
