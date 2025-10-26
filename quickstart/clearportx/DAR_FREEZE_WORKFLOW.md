# DAR Freeze Workflow - Production Deployment Guide

## The Package Hash Problem

Every time `daml build` runs, it generates a **different package hash** even with identical source code. This is DAML's design for package identification.

**Impact:**
- Contracts created with hash A are NOT visible when backend queries with hash B
- Backend must use the SAME frozen DAR that was used to create contracts
- Canton Network enforces upgrade compatibility rules

## Solution: Freeze-First Workflow

This workflow ensures all deployments (pools, tokens, contracts) use the SAME package hash as the backend.

---

## Workflow Overview

```
1. Write DAML code (templates + scripts)
2. Build DAR → Extract hash
3. Freeze DAR immediately
4. Upload frozen DAR to Canton
5. Execute scripts with frozen DAR
6. Update backend to use frozen DAR
7. Rebuild backend (codegen)
8. Restart backend
9. Verify everything works
```

---

## Step-by-Step Instructions

### Phase 1: Build and Freeze

```bash
cd /root/cn-quickstart/quickstart/clearportx

# 1. Clean previous build
daml clean

# 2. Build new DAR (this generates a NEW hash)
daml build

# 3. Extract the package hash
jar xf .daml/dist/clearportx-amm-<VERSION>.dar META-INF/MANIFEST.MF
cat META-INF/MANIFEST.MF | grep "Main-Dalf"
# Output: Main-Dalf: clearportx-amm-<VERSION>-<HASH>.dalf

# 4. Save the hash (example: b0610de3c1e81e92dc64d669a5ac17641add976)
HASH="<PASTE_HASH_HERE>"

# 5. Freeze the DAR immediately (creates two copies)
rm -rf META-INF
cp .daml/dist/clearportx-amm-<VERSION>.dar artifacts/devnet/clearportx-amm-v<VERSION>-${HASH}.dar
cp .daml/dist/clearportx-amm-<VERSION>.dar artifacts/devnet/clearportx-amm-v<VERSION>-frozen.dar

echo "✅ DAR frozen with hash: $HASH"
```

**Why freeze?**
- Prevents accidental rebuilds that change the hash
- Creates permanent reference copy with hash in filename
- Backend and ledger will use this exact file forever

---

### Phase 2: Upload and Execute Scripts

```bash
cd /root/cn-quickstart/quickstart/clearportx

# 1. Upload frozen DAR to Canton Network
daml ledger upload-dar artifacts/devnet/clearportx-amm-v<VERSION>-frozen.dar \
  --host localhost \
  --port 5001 \
  --max-inbound-message-size 10000000

# 2. Execute pool creation script with frozen DAR
daml script \
  --dar artifacts/devnet/clearportx-amm-v<VERSION>-frozen.dar \
  --script-name CreateAllPools:createAllPools \
  --ledger-host localhost \
  --ledger-port 5001

# 3. Execute token minting script with frozen DAR
daml script \
  --dar artifacts/devnet/clearportx-amm-v<VERSION>-frozen.dar \
  --script-name GiveAliceBobTokens:giveTokens \
  --ledger-host localhost \
  --ledger-port 5001
```

**Critical:** Always use the frozen DAR for script execution! If you rebuild and use the new DAR, the contracts will have a different hash and won't be visible.

---

### Phase 3: Update Backend

```bash
cd /root/cn-quickstart/quickstart

# 1. Update daml/build.gradle.kts to point to frozen DAR
vim daml/build.gradle.kts

# Change:
# dar.from("$rootDir/clearportx/artifacts/devnet/clearportx-amm-v1.0.4-frozen.dar")
# To:
# dar.from("$rootDir/clearportx/artifacts/devnet/clearportx-amm-v<VERSION>-frozen.dar")

# 2. Rebuild backend with new codegen (generates Java classes from frozen DAR)
./gradlew backend:compileJava

# 3. Kill old backend
pkill -f bootRun

# 4. Start new backend
cd /root/cn-quickstart/quickstart/clearportx
./start-backend-production.sh
```

**Why rebuild backend?**
- Backend needs Java classes generated from the EXACT frozen DAR
- Codegen embeds the package hash into Java code
- Queries will use this hash to find contracts on ledger

---

### Phase 4: Verify

```bash
# 1. Check backend health
curl http://localhost:8080/actuator/health

# 2. Verify pools are visible
curl http://localhost:8080/api/pools | jq

# 3. Check token balances for Alice
curl "http://localhost:8080/api/tokens/Alice::<PARTY_ID>" | jq

# 4. Check token balances for Bob
curl "http://localhost:8080/api/tokens/Bob::<PARTY_ID>" | jq
```

**Expected:**
- All 4 pools visible (ETH-USDC, CANTON-USDC, CANTON-CBTC, CBTC-USDC)
- Alice has: 10 ETH, 50,000 USDC, 20 CANTON, 0.5 CBTC
- Bob has: 15 ETH, 30,000 USDC, 15 CANTON, 0.3 CBTC

---

## Canton Network Upgrade Constraints

**Key Rule:** Canton Network enforces upgrade compatibility when uploading a new version.

### What You CAN Do:
- Add new templates
- Add new choices to existing templates
- Add new fields with default values

### What You CANNOT Do:
- Remove modules (like we did with TestLiquidity)
- Remove templates
- Change field types in existing templates
- Remove choices from templates

### If You Need Breaking Changes:
1. **Option A (Recommended):** Deploy under a NEW package name
   - Change `name:` in `daml.yaml` from `clearportx-amm` to `clearportx-amm-v2`
   - This creates a completely new package, not an upgrade

2. **Option B:** Maintain backward compatibility
   - Keep all old modules even if unused
   - Use feature flags to disable old functionality

---

## Production Deployment Strategy

### For DevNet (Testing):
```bash
# Can experiment freely
# Can delete participant data and start fresh if needed
# No upgrade constraints if you reset
```

### For Testnet (Staging):
```bash
# MUST follow upgrade rules
# Cannot reset participant data
# Test your upgrade path here BEFORE mainnet
```

### For Mainnet (Production):
```bash
# Immutable
# MUST follow upgrade rules
# NEVER upload untested DARs
# ONE CHANCE to get it right

# Workflow:
1. Develop and test on devnet
2. Freeze DAR
3. Deploy to testnet
4. Test all functionality on testnet
5. If everything works, deploy SAME frozen DAR to mainnet
6. NEVER rebuild - use exact same frozen file
```

---

## Quick Reference: Current Frozen DAR

**Version:** v1.0.5
**Hash:** `b0610de3c1e81e92dc64d669a5ac17641add976`
**File:** `artifacts/devnet/clearportx-amm-v1.0.5-frozen.dar`

**Scripts included:**
- `CreateAllPools:createAllPools` - Creates 4 pools (ETH-USDC, CANTON-USDC, CANTON-CBTC, CBTC-USDC)
- `GiveAliceBobTokens:giveTokens` - Mints tokens for Alice and Bob

---

## Troubleshooting

### Problem: "DAR_NOT_VALID_UPGRADE" error
**Cause:** You removed/changed modules in a way that breaks upgrade compatibility
**Solution:** Either:
1. Keep old modules even if unused
2. Deploy under new package name (change `name:` in `daml.yaml`)
3. Reset participant (devnet only)

### Problem: Pools/tokens not visible in backend
**Cause:** Backend using different package hash than contracts
**Solution:**
1. Check backend is using frozen DAR in `daml/build.gradle.kts`
2. Rebuild backend: `./gradlew backend:compileJava`
3. Restart backend

### Problem: Script fails with "party not found"
**Cause:** Scripts use `listKnownParties` which requires parties exist
**Solution:**
1. Verify parties exist: `daml ledger list-parties --host localhost --port 5001`
2. Create missing parties: `daml ledger allocate-party Alice --host localhost --port 5001`

---

## Future Improvements

### Auto-versioning Script
Create a script that:
1. Extracts current version from `daml.yaml`
2. Increments patch version
3. Builds DAR
4. Freezes with extracted hash
5. Outputs instructions for next steps

### CI/CD Integration
```yaml
# .github/workflows/freeze-dar.yml
name: Freeze DAR for Production

on:
  push:
    tags:
      - 'v*'

jobs:
  freeze:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Build and freeze DAR
        run: |
          daml build
          # Extract hash, copy to artifacts
          # Upload to release
```

---

## Summary

**Golden Rule:** Once you create contracts on Canton Network, the package hash is LOCKED IN.

- Build once → Freeze immediately → Use frozen DAR everywhere
- Backend must regenerate code from frozen DAR
- Scripts must execute with frozen DAR
- Never rebuild unless starting completely fresh
- For production: Test upgrade path on testnet FIRST

This workflow ensures consistency across your entire deployment pipeline.
