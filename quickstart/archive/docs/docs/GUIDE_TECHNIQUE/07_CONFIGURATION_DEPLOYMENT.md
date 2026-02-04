# MODULE 07 - CONFIGURATION & DEPLOYMENT

**Auteur**: Documentation technique ClearportX  
**Date**: 2025-10-21  
**Version**: 1.0.0  
**Prérequis**: Module 01 (Architecture), Module 06 (Sécurité)

---

## TABLE DES MATIÈRES

1. [Environnements de déploiement](#1-environnements-de-déploiement)
2. [Localnet - Development Local](#2-localnet---development-local)
3. [Devnet - Canton Network Testing](#3-devnet---canton-network-testing)
4. [Docker Compose Architecture](#4-docker-compose-architecture)
5. [Makefile Commands](#5-makefile-commands)

---

## 1. ENVIRONNEMENTS DE DÉPLOIEMENT

### 1.1 Vue d'ensemble

```
┌────────────────────────────────────────────────────────────────┐
│ CLEARPORTX DEPLOYMENT ENVIRONMENTS                              │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│ LOCALNET (Development)                                         │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ • Standalone Canton participant (local Docker)           │   │
│ │ • No rate limiting (unlimited TPS)                       │   │
│ │ • No authentication (local parties)                      │   │
│ │ • Fast iteration (instant restarts)                      │   │
│ │ • Full control (logs, debugging)                         │   │
│ │                                                           │   │
│ │ Use cases:                                               │   │
│ │ • Feature development                                    │   │
│ │ • Unit/integration tests                                 │   │
│ │ • DAML script testing                                    │   │
│ │ • UI prototyping                                         │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│ DEVNET (Canton Network Testing)                                │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ • Canton Network validators (api.sync.global)            │   │
│ │ • Rate limiting: 0.4 TPS (enforced)                      │   │
│ │ • OAuth2 JWT authentication (Keycloak)                   │   │
│ │ • IP whitelist (3-4 day SV propagation)                  │   │
│ │ • Real network latency (~500ms consensus)                │   │
│ │                                                           │   │
│ │ Use cases:                                               │   │
│ │ • Pre-production testing                                 │   │
│ │ • Rate limit compliance validation                       │   │
│ │ • Multi-party workflow testing                           │   │
│ │ • Performance benchmarking                               │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│ MAINNET (Production) [Future]                                  │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ • Canton Network production validators                   │   │
│ │ • No global rate limit (pricing-based)                   │   │
│ │ • High throughput (100+ TPS)                             │   │
│ │ • Enterprise SLA                                         │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### 1.2 Comparaison détaillée

| Aspect                  | Localnet                   | Devnet                        |
|-------------------------|----------------------------|-------------------------------|
| **Canton**              | Standalone (local Docker)  | Canton Network (api.sync.global) |
| **Authentication**      | None (shared secret)       | OAuth2 JWT (Keycloak)         |
| **Rate Limiting**       | None (unlimited)           | 0.4 TPS (enforced)            |
| **IP Whitelist**        | No                         | Yes (3-4 day SV propagation)  |
| **Consensus Latency**   | ~50ms (local)              | ~500ms (network)              |
| **Party Creation**      | Instant (allocateParty)    | Manual (Canton Console)       |
| **DAR Upload**          | Direct (canton docker)     | Via participant console       |
| **Logs Access**         | Full (docker logs)         | Limited (participant only)    |
| **Cost**                | Free (local resources)     | Free (devnet credits)         |
| **Uptime**              | Developer-controlled       | 99% SLA                       |

---

## 2. LOCALNET - DEVELOPMENT LOCAL

### 2.1 Architecture Docker Compose

**Fichier**: `compose.yaml` (racine du projet)

```yaml
version: '3.8'

services:
  # Canton Standalone Participant
  canton:
    image: digitalasset/canton-open-source:3.3.0
    container_name: clearportx-canton
    ports:
      - "3901:3901"  # Ledger API
      - "3902:3902"  # Admin API
    volumes:
      - ./docker/canton/conf:/canton/conf:ro
      - ./docker/canton/data:/canton/data
      - ./quickstart/clearportx/.daml/dist:/canton/dars:ro
    environment:
      - CANTON_PARTICIPANT_NAME=participant1
      - CANTON_DOMAIN_NAME=local
    command: daemon --config /canton/conf/standalone.conf
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:3902/health"]
      interval: 10s
      timeout: 5s
      retries: 5

  # PQS (Participant Query Service) - PostgreSQL indexer
  pqs:
    image: digitalasset/canton-participant-query-service:3.3.0
    container_name: clearportx-pqs
    ports:
      - "8080:8080"  # PQS HTTP API
    environment:
      - PQS_DB_HOST=pqs-db
      - PQS_DB_PORT=5432
      - PQS_DB_NAME=pqs_db
      - PQS_DB_USER=pqs_user
      - PQS_DB_PASSWORD=pqs_password
      - PQS_PARTICIPANT_HOST=canton
      - PQS_PARTICIPANT_PORT=3901
      # Package allowlist (critical!)
      - PQS_ALLOWLIST_PACKAGES={"clearportx-amm":{"name":"clearportx-amm","version":"1.0.1"}}
    depends_on:
      canton:
        condition: service_healthy
      pqs-db:
        condition: service_healthy

  # PostgreSQL for PQS
  pqs-db:
    image: postgres:15
    container_name: clearportx-pqs-db
    ports:
      - "5432:5432"
    environment:
      - POSTGRES_DB=pqs_db
      - POSTGRES_USER=pqs_user
      - POSTGRES_PASSWORD=pqs_password
    volumes:
      - pqs-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U pqs_user"]
      interval: 5s
      timeout: 3s
      retries: 5

  # Spring Boot Backend
  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: clearportx-backend
    ports:
      - "8080:8080"  # REST API
    environment:
      - SPRING_PROFILES_ACTIVE=localnet
      - CANTON_HOST=canton
      - CANTON_PORT=3901
      - PQS_HOST=pqs-db
      - PQS_PORT=5432
      - PQS_PASSWORD=pqs_password
      - APP_PROVIDER_PARTY=${APP_PROVIDER_PARTY}
    depends_on:
      canton:
        condition: service_healthy
      pqs:
        condition: service_started
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5

  # React Frontend
  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile
    container_name: clearportx-frontend
    ports:
      - "3000:3000"
    environment:
      - REACT_APP_BACKEND_URL=http://localhost:8080
      - REACT_APP_AUTH_ENABLED=false  # Localnet: no auth
    depends_on:
      - backend

  # Prometheus (Metrics)
  prometheus:
    image: prom/prometheus:latest
    container_name: clearportx-prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus-clearportx.yml:/etc/prometheus/prometheus.yml:ro
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'

  # Grafana (Dashboards)
  grafana:
    image: grafana/grafana:latest
    container_name: clearportx-grafana
    ports:
      - "3001:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    volumes:
      - ./grafana-clearportx-dashboard.json:/etc/grafana/provisioning/dashboards/clearportx.json:ro

volumes:
  pqs-data:
  canton-data:
```

### 2.2 Canton Configuration - standalone.conf

**Fichier**: `docker/canton/conf/standalone.conf`

```hocon
canton {
  parameters {
    # Localnet: no rate limiting
    rate-limit = {
      max-rate = 1000  # 1000 TPS (effectively unlimited)
    }
  }

  participants {
    participant1 {
      storage {
        type = h2
        config {
          url = "jdbc:h2:/canton/data/participant1"
          user = "canton"
          password = "supersafe"
        }
      }

      ledger-api {
        address = "0.0.0.0"
        port = 3901
        # Localnet: no TLS
        tls = null
      }

      admin-api {
        address = "0.0.0.0"
        port = 3902
      }
    }
  }

  domains {
    local {
      storage {
        type = h2
        config {
          url = "jdbc:h2:/canton/data/domain"
          user = "canton"
          password = "supersafe"
        }
      }

      public-api {
        address = "0.0.0.0"
        port = 3903
      }

      admin-api {
        address = "0.0.0.0"
        port = 3904
      }
    }
  }
}
```

### 2.3 Startup Sequence Localnet

```
make local-up

↓

ÉTAPE 1: Start PostgreSQL (PQS database)
  docker compose up -d pqs-db
  Wait for health check → pg_isready

ÉTAPE 2: Start Canton Standalone
  docker compose up -d canton
  • Initialize domain "local"
  • Create participant "participant1"
  • Connect participant to domain
  Wait for health check → Canton Admin API /health

ÉTAPE 3: Upload DAR (DAML Archive)
  docker exec canton daml ledger upload-dar /canton/dars/clearportx-amm-1.0.1.dar
  ← Deploy smart contracts to Canton ledger

ÉTAPE 4: Run Initialization Script (DAML)
  daml script \
    --dar .daml/dist/clearportx-amm-1.0.1.dar \
    --script-name InitializeClearportX:initClearportX \
    --ledger-host localhost \
    --ledger-port 3901
  
  ← Creates:
    • AppProvider party
    • Alice, Bob parties (test users)
    • Initial tokens (ETH, USDC, USDT)
    • ETH-USDC pool with liquidity

ÉTAPE 5: Start PQS (indexer)
  docker compose up -d pqs
  • Connect to Canton Ledger API
  • Index all contracts into PostgreSQL
  Wait for sync → PQS offset matches Canton

ÉTAPE 6: Start Backend (Spring Boot)
  docker compose up -d backend
  • Validate Canton connection (StartupValidation)
  • Validate PQS connection
  • Expose REST API :8080

ÉTAPE 7: Start Frontend (React)
  docker compose up -d frontend
  • Connect to backend :8080
  • Serve UI :3000

ÉTAPE 8: Start Observability (Prometheus + Grafana)
  docker compose up -d prometheus grafana
  • Prometheus scrapes backend :8080/actuator/prometheus
  • Grafana dashboards :3001

↓

✅ ClearportX Localnet Ready!
  • Frontend: http://localhost:3000
  • Backend API: http://localhost:8080
  • Grafana: http://localhost:3001 (admin/admin)
  • Prometheus: http://localhost:9090
  • Canton Admin: http://localhost:3902
```

---

## 3. DEVNET - CANTON NETWORK TESTING

### 3.1 Prerequisites

**1. IP Whitelist Approval** (3-4 days) :

```bash
# Submit IP whitelist PR to Canton Network
# https://github.com/digital-asset/canton-network-node-config

# Example PR:
# Add 203.0.113.45 for ClearportX devnet testing

# Wait for:
# • PR review and merge
# • Super Validator (SV) propagation (3-4 days)
# • Confirmation from Canton team
```

**2. Canton Network Credentials** :

```bash
# Obtain from Canton Network team:
# • Participant ID
# • OAuth2 client credentials (Keycloak)
# • Party allocation instructions
```

**3. Create Party via Canton Console** :

```scala
// Connect to Canton Console
ssh participant@participant.clearportx.canton.network

// Allocate party
val clearportxParty = participant1.parties.enable(
  name = "ClearportX",
  displayName = Some("ClearportX DEX"),
  namespace = "clearportx"
)

// Get party ID
clearportxParty.party
// Output: "ClearportX::1220abc123def456..."
```

### 3.2 Devnet Configuration

**Fichier**: `docker/backend-service/env/devnet.env`

```bash
# Spring profile
SPRING_PROFILES_ACTIVE=devnet

# Canton Network Ledger API
CANTON_HOST=api.sync.global
CANTON_PORT=443
CANTON_TLS=true

# OAuth2 (Canton Network Keycloak)
KEYCLOAK_URL=https://keycloak.canton.network
KEYCLOAK_REALM=AppProvider
KEYCLOAK_CLIENT_ID=clearportx-backend
KEYCLOAK_CLIENT_SECRET=${KEYCLOAK_CLIENT_SECRET}  # From Canton team

# ClearportX Party
APP_PROVIDER_PARTY=ClearportX::1220abc123def456...

# Rate limiting (devnet compliance)
RATE_LIMITER_ENABLED=true
RATE_LIMITER_TPS=0.4

# PQS (local PostgreSQL, indexes Canton Network)
PQS_HOST=pqs-db
PQS_PORT=5432
PQS_DB_NAME=pqs_db
PQS_DB_USER=pqs_user
PQS_DB_PASSWORD=${PQS_PASSWORD}
```

**Fichier**: `backend/src/main/resources/application-devnet.yml`

```yaml
# Override for devnet deployment
canton:
  ledger:
    host: api.sync.global
    port: 443
    tls: true
    # Canton Network uses JWT bearer tokens
    auth-type: oauth2

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://keycloak.canton.network/realms/AppProvider
          jwk-set-uri: https://keycloak.canton.network/realms/AppProvider/protocol/openid-connect/certs

# Rate limiter
rate-limiter:
  enabled: true
  tps-limit: 0.4

# Logging (less verbose for production-like)
logging:
  level:
    root: WARN
    com.digitalasset.quickstart: INFO
```

### 3.3 DAR Upload Devnet

**Via Canton Console** :

```scala
// Connect to Canton Console
ssh participant@participant.clearportx.canton.network

// Upload DAR
participant1.dars.upload("/path/to/clearportx-amm-1.0.1.dar")

// Verify DAR packages
participant1.dars.list()
// Output:
// clearportx-amm-1.0.1 (hash: abc123...)
//   - AMM.Pool
//   - AMM.SwapRequest
//   - Token.Token
//   - LPToken.LPToken
```

**Via Ledger API** (automated) :

```bash
# Using daml CLI (requires authentication token)
daml ledger upload-dar \
  .daml/dist/clearportx-amm-1.0.1.dar \
  --host api.sync.global \
  --port 443 \
  --access-token-file ./canton-network-token.jwt
```

### 3.4 Devnet Initialization

**Script**: `devnet-init.sh`

```bash
#!/bin/bash
set -e

echo "🚀 Initializing ClearportX on Canton Network Devnet..."

# 1. Verify IP whitelist
echo "1. Testing Canton Network connectivity..."
timeout 10 curl -f https://api.sync.global/health || {
  echo "❌ Cannot reach api.sync.global - IP not whitelisted?"
  exit 1
}
echo "✅ Canton Network reachable"

# 2. Upload DAR
echo "2. Uploading DAR to Canton Network..."
daml ledger upload-dar \
  .daml/dist/clearportx-amm-1.0.1.dar \
  --host api.sync.global \
  --port 443 \
  --access-token-file ./canton-network-token.jwt \
  --max-inbound-message-size 10000000

echo "✅ DAR uploaded"

# 3. Run initialization script (DAML)
echo "3. Running DAML initialization script..."
daml script \
  --dar .daml/dist/clearportx-amm-1.0.1.dar \
  --script-name InitializeClearportX:initClearportX \
  --ledger-host api.sync.global \
  --ledger-port 443 \
  --access-token-file ./canton-network-token.jwt

echo "✅ Initialization complete"

# 4. Wait for PQS sync
echo "4. Waiting for PQS to sync..."
timeout 60 bash -c '
  until curl -s http://localhost:8080/api/health/ledger 2>/dev/null | grep -q "\"status\":\"OK\""; do
    echo "Waiting for PQS sync..."
    sleep 3
  done
'
echo "✅ PQS synced"

echo "🎉 ClearportX devnet ready!"
```

### 3.5 Devnet Monitoring

**Health Check Endpoint** :

```bash
# Check backend health
curl http://localhost:8080/api/health/ledger

# Response (healthy):
{
  "status": "OK",
  "synced": true,
  "cantonConnected": true,
  "pqsOffset": 123456,
  "clearportxContractCount": 42,
  "poolsActive": 3,
  "atomicSwapAvailable": true
}

# Response (syncing):
{
  "status": "SYNCING",
  "synced": false,
  "diagnostic": "PQS catching up to Canton (offset 12340 / 12345)"
}

# Response (error):
{
  "status": "PACKAGE_NOT_INDEXED",
  "synced": false,
  "diagnostic": "ClearportX package not found in PQS - check allowlist"
}
```

---

## 4. DOCKER COMPOSE ARCHITECTURE

### 4.1 Network Topology

```
┌────────────────────────────────────────────────────────────────┐
│ DOCKER COMPOSE NETWORK                                          │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│ Network: clearportx-network (bridge)                           │
│                                                                 │
│ ┌──────────────┐                                               │
│ │  frontend    │ :3000                                         │
│ │  (React)     │                                               │
│ └──────┬───────┘                                               │
│        │ HTTP                                                   │
│        ↓                                                        │
│ ┌──────────────┐     gRPC      ┌──────────────┐               │
│ │  backend     │ :8080 ─────────→ canton       │ :3901 (Ledger API)│
│ │  (Spring)    │               │ (Participant) │ :3902 (Admin API) │
│ └──────┬───────┘               └──────┬────────┘               │
│        │                               │                        │
│        │ JDBC                          │ Stream                 │
│        ↓                               ↓                        │
│ ┌──────────────┐               ┌──────────────┐               │
│ │  pqs-db      │ :5432 ←────── │  pqs         │ :8080         │
│ │  (PostgreSQL)│               │  (Indexer)   │               │
│ └──────────────┘               └──────────────┘               │
│                                                                 │
│ ┌──────────────┐     HTTP      ┌──────────────┐               │
│ │ prometheus   │ :9090 ─────────→ backend      │ :8080/actuator│
│ └──────┬───────┘     scrape    └──────────────┘               │
│        │                                                        │
│        │ PromQL                                                 │
│        ↓                                                        │
│ ┌──────────────┐                                               │
│ │  grafana     │ :3001                                         │
│ └──────────────┘                                               │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### 4.2 Volume Persistence

```yaml
volumes:
  # PQS database (contract index)
  pqs-data:
    driver: local
    # Persists across container restarts
    # Location: /var/lib/docker/volumes/clearportx_pqs-data/
  
  # Canton participant storage (ledger state)
  canton-data:
    driver: local
    # Contains: transactions, contracts, party info
    # Location: /var/lib/docker/volumes/clearportx_canton-data/
```

**Backup strategy** :

```bash
# Backup PQS database
docker exec clearportx-pqs-db pg_dump -U pqs_user pqs_db > pqs-backup-$(date +%Y%m%d).sql

# Backup Canton data
docker run --rm -v clearportx_canton-data:/data -v $(pwd):/backup \
  alpine tar czf /backup/canton-backup-$(date +%Y%m%d).tar.gz /data

# Restore PQS
docker exec -i clearportx-pqs-db psql -U pqs_user pqs_db < pqs-backup-20250115.sql

# Restore Canton
docker run --rm -v clearportx_canton-data:/data -v $(pwd):/backup \
  alpine tar xzf /backup/canton-backup-20250115.tar.gz -C /
```

---

## 5. MAKEFILE COMMANDS

### 5.1 Makefile Structure

**Fichier**: `quickstart/clearportx/Makefile`

```makefile
.PHONY: help local-up local-down local-restart test clean

# Default target: show help
help:
	@echo "ClearportX Makefile Commands"
	@echo ""
	@echo "Localnet:"
	@echo "  make local-up       - Start full stack (Canton + PQS + Backend + Frontend)"
	@echo "  make local-down     - Stop all containers"
	@echo "  make local-restart  - Restart backend only (fast iteration)"
	@echo "  make local-logs     - Tail logs (backend + canton)"
	@echo ""
	@echo "Testing:"
	@echo "  make test           - Run DAML tests"
	@echo "  make test-backend   - Run backend integration tests"
	@echo ""
	@echo "Devnet:"
	@echo "  make devnet-init    - Initialize Canton Network devnet"
	@echo "  make devnet-health  - Check devnet connectivity"
	@echo ""
	@echo "Maintenance:"
	@echo "  make clean          - Clean build artifacts"
	@echo "  make reset          - DANGEROUS: Delete all data (volumes)"

# Start localnet
local-up:
	@echo "🚀 Starting ClearportX Localnet..."
	cd ../.. && docker compose up -d pqs-db canton
	@echo "⏳ Waiting for Canton to be ready..."
	@sleep 10
	@echo "📦 Uploading DAR..."
	docker exec clearportx-canton daml ledger upload-dar \
	  /canton/dars/clearportx-amm-1.0.1.dar \
	  --host localhost --port 3901
	@echo "🎬 Running initialization script..."
	daml script \
	  --dar .daml/dist/clearportx-amm-1.0.1.dar \
	  --script-name InitializeClearportX:initClearportX \
	  --ledger-host localhost --ledger-port 3901
	@echo "🔄 Starting PQS, Backend, Frontend..."
	cd ../.. && docker compose up -d pqs backend frontend prometheus grafana
	@echo "⏳ Waiting for backend to be ready..."
	@timeout 120 bash -c 'until curl -s http://localhost:8080/api/health/ledger 2>/dev/null | grep -q "\"status\":\"OK\""; do echo "Waiting..."; sleep 3; done'
	@echo "✅ ClearportX Localnet Ready!"
	@echo "   Frontend: http://localhost:3000"
	@echo "   Backend:  http://localhost:8080"
	@echo "   Grafana:  http://localhost:3001 (admin/admin)"

# Stop localnet
local-down:
	@echo "🛑 Stopping ClearportX Localnet..."
	cd ../.. && docker compose down

# Restart backend only (fast dev iteration)
local-restart:
	@echo "🔄 Restarting backend..."
	cd ../.. && docker compose restart backend
	@timeout 60 bash -c 'until curl -s http://localhost:8080/actuator/health; do sleep 2; done'
	@echo "✅ Backend restarted"

# Tail logs
local-logs:
	cd ../.. && docker compose logs -f backend canton

# Run DAML tests
test:
	@echo "🧪 Running DAML tests..."
	daml test --show-coverage

# Run backend integration tests
test-backend:
	@echo "🧪 Running backend integration tests..."
	cd ../../backend && ./gradlew test

# Devnet initialization
devnet-init:
	@echo "🚀 Initializing Canton Network Devnet..."
	@bash devnet-init.sh

# Devnet health check
devnet-health:
	@echo "🏥 Checking Canton Network connectivity..."
	@curl -f https://api.sync.global/health && echo "✅ Canton Network OK" || echo "❌ Canton Network unreachable"
	@curl -s http://localhost:8080/api/health/ledger | jq '.'

# Clean build artifacts
clean:
	@echo "🧹 Cleaning build artifacts..."
	rm -rf .daml/dist
	cd ../../backend && ./gradlew clean

# DANGEROUS: Reset all data
reset:
	@echo "⚠️  WARNING: This will DELETE all Canton data and PQS database!"
	@read -p "Are you sure? (yes/no): " confirm && [ "$$confirm" = "yes" ] || exit 1
	@echo "🗑️  Stopping containers..."
	cd ../.. && docker compose down -v
	@echo "✅ All data reset"
```

### 5.2 Usage Examples

**Development workflow** :

```bash
# 1. Start localnet (first time)
make local-up
# → Canton + PQS + Backend + Frontend all running

# 2. Make code changes in backend/src/...
vim backend/src/main/java/com/digitalasset/quickstart/controller/SwapController.java

# 3. Rebuild backend image
cd ../../backend && ./gradlew build && docker build -t clearportx-backend .

# 4. Restart backend only (fast!)
make local-restart
# → Only restarts backend container (Canton/PQS stay running)

# 5. View logs
make local-logs
# → Tail backend + canton logs

# 6. Run tests
make test
# → DAML unit tests

make test-backend
# → Backend integration tests

# 7. Stop everything
make local-down
```

**Devnet workflow** :

```bash
# 1. Initialize devnet (after IP whitelist approved)
make devnet-init
# → Upload DAR, run init script, wait for sync

# 2. Monitor health
make devnet-health
# → Check Canton Network connection + PQS sync

# 3. Manual smoke test
curl -X POST http://localhost:8080/api/swap/atomic \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "poolId": "ETH-USDC-pool-0.3%",
    "inputSymbol": "ETH",
    "inputAmount": "1.0",
    "minOutput": "1800.0",
    "maxPriceImpactBps": 1000
  }'
```

---

## RÉSUMÉ MODULE 07

Ce module couvre **configuration et déploiement** de ClearportX :

1. **Environnements** :
   - Localnet (dev local, no rate limit, no auth)
   - Devnet (Canton Network, 0.4 TPS, OAuth2 JWT)
   - Mainnet (production, 100+ TPS, enterprise SLA)

2. **Localnet Setup** :
   - Docker Compose (Canton + PQS + Backend + Frontend)
   - Canton standalone configuration
   - Initialization DAML scripts
   - Full stack startup sequence

3. **Devnet Setup** :
   - IP whitelist (3-4 day SV propagation)
   - OAuth2 JWT authentication (Keycloak)
   - Party allocation via Canton Console
   - DAR upload to Canton Network
   - Rate limiting compliance (0.4 TPS)

4. **Docker Compose** :
   - Network topology (7 services)
   - Volume persistence (pqs-data, canton-data)
   - Health checks
   - Backup/restore strategy

5. **Makefile** :
   - local-up/down/restart (dev workflow)
   - test/test-backend (testing)
   - devnet-init/health (Canton Network)
   - clean/reset (maintenance)

**Next Steps** : Module 08 (Flows End-to-End - scénarios complets).

