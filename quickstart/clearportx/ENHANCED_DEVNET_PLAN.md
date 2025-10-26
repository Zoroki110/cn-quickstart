# 🚀 ENHANCED PLAN TO GO LIVE ON DEVNET

## Pre-flight Checks ✅
- [x] Pool already created: `007bef2c61e5570d42075b6d70c4d595fbc050fc`
- [x] Frozen artifact script available: `/tmp/EXECUTE_THIS_NEXT_SESSION.sh`
- [ ] Backend currently returns `[]` - needs fix

## Enhanced 12-Step Execution Plan

### Phase 1: Environment Setup (Steps 1-4)
```bash
# 1. Kill zombies
pkill -9 -f "gradlew.*bootRun"
pkill -9 -f "java.*8080"

# 2-4. Execute frozen artifact workflow
/tmp/EXECUTE_THIS_NEXT_SESSION.sh
```

### Phase 2: Pool Creation (Step 5)
```bash
# Check if pool already exists first
cd /root/cn-quickstart/quickstart/clearportx
daml script \
  --ledger-host localhost \
  --ledger-port 5001 \
  --dar artifacts/devnet/clearportx-amm-v1.0.4-frozen.dar \
  --script-name VerifyPool:verifyExistingPools

# If no pools, create new one
daml script \
  --ledger-host localhost \
  --ledger-port 5001 \
  --dar artifacts/devnet/clearportx-amm-v1.0.4-frozen.dar \
  --script-name QuickPoolInit:quickPoolInit
```

### Phase 3: Backend Startup (Step 6)
```bash
# Start with proper environment
cd ../backend
export CANTON_PARTY_NAME=app-provider
export OAUTH_DISABLE=true  # Try to bypass OAuth if possible
../gradlew bootRun --args='--spring.profiles.active=devnet'
```

### Phase 4: Verification (Step 7)
```bash
# Test without auth
curl http://localhost:8080/api/pools

# If empty, test with mock JWT
TOKEN="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/pools
```

### Phase 5: Testing (Steps 8-10)
```bash
# 8. Test atomic swap
curl -X POST http://localhost:8080/api/pools/ETH-USDC-01/swap \
  -H "Content-Type: application/json" \
  -d '{"amountIn": 1.0, "tokenIn": "ETH", "minAmountOut": 1900.0}'

# 9. Test add liquidity
curl -X POST http://localhost:8080/api/pools/ETH-USDC-01/add-liquidity \
  -H "Content-Type: application/json" \
  -d '{"amountA": 10.0, "amountB": 20000.0}'

# 10. Run smoke tests
./run-smoke-tests.sh
```

### Phase 6: Documentation & Deploy (Steps 11-12)
```bash
# Generate API docs
../gradlew generateOpenApiDocs

# Deploy to DevNet
./deploy-to-devnet.sh
```

## 🔑 Critical Success Factors

1. **OAuth2 Bypass**: Must solve authentication issue
2. **Package Hash Sync**: Frozen artifact is key
3. **Party Alignment**: app-provider must own pools
4. **API Visibility**: /api/pools must return data

## 🎯 Expected Outcome
- Pool visible in API: ✅
- Swaps working: ✅
- Liquidity operations: ✅
- Ready for frontend: ✅
- LIVE ON DEVNET: 🚀
