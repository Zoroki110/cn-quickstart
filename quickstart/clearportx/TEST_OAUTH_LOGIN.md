# Test OAuth Login - Frontend ClearportX

**Status:** ✅ Keycloak Running, OAuth functional
**Frontend:** http://localhost:3001
**Backend:** http://localhost:8080 (Docker container)

---

## ✅ Keycloak Confirmed Working

```bash
curl http://localhost:8082/realms/AppProvider/.well-known/openid-configuration
# ✅ Returns issuer: http://keycloak.localhost:8082/realms/AppProvider
```

**Token Test:**
```bash
curl -X POST http://localhost:8082/realms/AppProvider/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=app-provider-unsafe" \
  -d "username=alice" \
  -d "password=alicepass"

# ✅ Returns JWT access_token (1407 chars)
```

---

## Steps to Test OAuth Login from Frontend

### 1. Open Frontend in Browser

```
http://localhost:3001
```

### 2. Click "Connect Wallet" Button

- You should see a Login Modal appear
- Default username: `alice`
- Default password: `alicepass`

### 3. Enter Credentials and Click "Connect"

**Expected behavior:**
1. Frontend calls `authService.login('alice', 'alicepass')`
2. POST request to Keycloak OAuth endpoint
3. Receives JWT token
4. Stores in `localStorage`:
   - `jwt_token`: JWT access token
   - `refresh_token`: Refresh token
   - `user_party`: "alice"
5. Toast message: "Logged in as alice"
6. Page reload
7. Header shows: "alice" [Disconnect] button

### 4. After Login - Verify localStorage

**Open Browser Console (F12 → Console tab):**
```javascript
localStorage.getItem('jwt_token')
// Should return: "eyJhbGc..."

localStorage.getItem('user_party')
// Should return: "alice"
```

### 5. Test Real Data Loading

**After successful login, the frontend should:**
1. Call `backendApi.getTokens('alice')` with JWT in Authorization header
2. Display **REAL token balances** from Canton ledger (not mocks!)
3. Call `backendApi.getPools()` to show real pools

---

## Troubleshooting

### Issue: "Login failed. Check credentials"

**Cause:** Keycloak returned 401 Unauthorized

**Solutions:**
1. Verify username/password: `alice` / `alicepass`
2. Check Keycloak is running:
   ```bash
   curl http://localhost:8082/realms/AppProvider/.well-known/openid-configuration
   ```
3. Check browser console for error details

### Issue: "Cannot connect to authentication server"

**Cause:** Frontend can't reach Keycloak (ECONNREFUSED)

**Solutions:**
1. Check Keycloak container:
   ```bash
   docker ps | grep keycloak
   ```
2. If not running:
   ```bash
   cd /root/cn-quickstart/quickstart
   docker compose up keycloak -d
   ```

### Issue: Login succeeds but tokens still show mocks (10000.50 USDC)

**Cause:** Backend `/api/tokens` endpoint returning 401 or timing out with JWT

**Current Status:** This is the current issue we're investigating.

**Possible causes:**
1. JWT subject → Canton party mapping issue
2. Backend OAuth config mismatch
3. Backend Docker using old code without our security changes

**Debug:**
```bash
# Test backend with JWT manually
TOKEN=$(curl -s -X POST http://localhost:8082/realms/AppProvider/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=app-provider-unsafe" \
  -d "username=alice" \
  -d "password=alicepass" | jq -r '.access_token')

# Try calling backend
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/tokens

# Expected: JSON array of tokens
# Actual: Might timeout or return error
```

---

## Workaround for Testing UI Now

Since the backend JWT → party mapping might have issues, we can temporarily use the frontend with mocks to test the UI flow:

### Option A: Test Login Flow Only

1. Open http://localhost:3001
2. Click "Connect Wallet"
3. Enter alice / alicepass
4. Verify toast "Logged in as alice"
5. Verify localStorage has jwt_token
6. Verify header shows "alice" [Disconnect]

**This tests:**
- ✅ OAuth integration works
- ✅ JWT storage works
- ✅ UI state updates correctly

**Does NOT test:**
- ❌ Real token data loading
- ❌ Real swaps

### Option B: Disable Backend Auth Temporarily

**NOT RECOMMENDED** but would allow full E2E test.

Modify backend Docker container's OAuth config to allow all endpoints without auth.

---

## Next Steps

### Immediate (if login works):

1. ✅ Verify login modal appears
2. ✅ Test alice login
3. ✅ Check localStorage for JWT
4. ✅ Verify header shows "alice"
5. ⏳ Debug why `/api/tokens` with JWT doesn't work
6. ⏳ Fix party mapping issue
7. ⏳ Test real token data loads
8. ⏳ Test real swap execution

### If party mapping is the issue:

The JWT subject is probably a UUID like `a17ddc36-9700-46ae-9183-7169c4d57cc5` (from the token we got earlier), but Canton expects a party ID like `alice::12201234...`.

**Backend needs:** `PartyMappingService.mapJwtSubjectToParty(uuid)` → `alice::1220...`

**Check if this mapping exists:**
```bash
docker exec backend-service cat /opt/backend/config/party-mapping.json
# or check environment variables
docker exec backend-service printenv | grep PARTY
```

---

## User Test Accounts

| Username | Password | Canton Party |
|----------|----------|--------------|
| alice | alicepass | alice::1220... |
| bob | bobpass | bob::1220... |
| charlie | charliepass | charlie::1220... |

---

## Success Criteria

**Login Flow:**
- ✅ Modal opens
- ✅ Credentials accepted
- ✅ JWT stored in localStorage
- ✅ Header updates to show username

**Real Data Loading:**
- ⏳ `/api/tokens` returns real balances (not 10000.50 USDC)
- ⏳ `/api/pools` returns real pools
- ⏳ Swap executes successfully

---

**Created:** 2025-10-21 21:43 UTC
**Frontend:** http://localhost:3001 ✅
**Keycloak:** http://localhost:8082 ✅
**Backend:** http://localhost:8080 (Docker) ✅
