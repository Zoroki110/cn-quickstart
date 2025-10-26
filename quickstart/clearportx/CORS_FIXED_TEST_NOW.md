# CORS Fixed - Ready to Test Frontend

## Changes Applied

### 1. Backend CORS Configuration
**File:** `backend/src/main/java/com/digitalasset/quickstart/security/oauth2/OAuth2Config.java`

Added complete CORS configuration with:
- Allowed origins: `http://localhost:3000,http://localhost:3001,http://localhost:4001`
- Allowed methods: `GET,POST,PUT,DELETE,OPTIONS`
- Allowed headers: `Authorization,Content-Type,X-Idempotency-Key,X-Request-ID`
- Exposed headers: `Retry-After,X-Request-ID`
- Allow credentials: `true`
- Max age: `3600` seconds (1 hour preflight cache)

### 2. Docker Environment
**File:** `docker/backend-service/env/app.env`

Added environment variable:
```
CORS_ALLOWED_ORIGINS: "http://localhost:3000,http://localhost:3001,http://localhost:4001"
```

### 3. WebSecurityConfig Default Values
**File:** `backend/src/main/java/com/digitalasset/quickstart/security/WebSecurityConfig.java`

Updated default allowed origins to include `http://localhost:4001`

## CORS Verification (Backend Side)

✅ **Preflight Request Test:**
```bash
curl -X OPTIONS http://localhost:8080/api/pools \
  -H "Origin: http://localhost:4001" \
  -H "Access-Control-Request-Method: GET" \
  -H "Access-Control-Request-Headers: Authorization" \
  -v 2>&1 | grep -i access-control
```

**Result:**
```
< Access-Control-Allow-Origin: http://localhost:4001
< Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS
< Access-Control-Allow-Headers: Authorization
< Access-Control-Expose-Headers: Retry-After, X-Request-ID
< Access-Control-Allow-Credentials: true
< Access-Control-Max-Age: 3600
```

✅ **Actual GET Request Test:**
```bash
curl http://localhost:8080/api/pools \
  -H "Origin: http://localhost:4001" \
  -v 2>&1 | grep -i access-control
```

**Result:**
```
< Access-Control-Allow-Origin: http://localhost:4001
< Access-Control-Expose-Headers: Retry-After, X-Request-ID
< Access-Control-Allow-Credentials: true
```

## Frontend Testing Instructions

### Step 1: Verify SSH Tunnels are Active
On your **local machine**, ensure these SSH tunnels are running:
```bash
ssh -L 4001:localhost:3001 -L 4080:localhost:8080 -L 4082:localhost:8082 root@5.9.70.48
```

Verify tunnels with:
```bash
curl http://localhost:4001         # Should return frontend HTML
curl http://localhost:4080/api/health/ledger  # Should return backend JSON
curl http://localhost:4082/realms/AppProvider/.well-known/openid-configuration  # Should return Keycloak JSON
```

### Step 2: Open Frontend in Browser
Open in your browser: **http://localhost:4001**

### Step 3: Login
1. Click "Connect Wallet" button
2. Login credentials:
   - Username: `alice`
   - Password: `alicepass`

### Step 4: Expected Behavior (No More CORS Errors!)

**Console Should Show:**
- ✅ Successful OAuth login
- ✅ JWT token stored in localStorage
- ✅ Successful GET request to `/api/pools` (should return 3 pools)
- ✅ Successful GET request to `/api/tokens/alice` (should return real token balances)

**Console Should NOT Show:**
- ❌ "Access to XMLHttpRequest blocked by CORS policy"
- ❌ "Network Error"

### Step 5: Verify Real Data Loading

**Pools Page:**
- Should show 3 pools (ETH/USDC, WBTC/USDC, LINK/USDC)
- Pool balances should be real values from Canton ledger
- TVL should be calculated from real reserves

**Swap Page:**
- Token dropdown should show real tokens with balances
- Alice's balances should NOT be 10000.50 (that was mock data)
- Real balances example: ETH: 100.0, USDC: 1000.0, etc.

**Liquidity Page:**
- Should load real pool data
- Add liquidity form should work with real backend

**History Page:**
- Should show empty list or past transactions (if any exist)

### Step 6: Test Real Swap Execution

1. Go to Swap page
2. Select: FROM = ETH, TO = USDC
3. Enter amount: `0.01` ETH
4. Click "Swap"
5. **Expected Result:**
   - Swap executes on Canton ledger
   - Success toast notification
   - Balances update (ETH decreases, USDC increases)
   - History page shows new transaction

### Step 7: Monitor Backend Logs (Server Side)

On the **server**, monitor backend logs:
```bash
docker logs -f backend-service 2>&1 | grep -E "alice|swap|pools|tokens"
```

**Expected Logs:**
```
GET /api/tokens/alice - public access (TESTING ONLY)
GET /api/pools - public access
POST /api/swap/atomic - party: alice
Executing swap: 0.01 ETH → USDC (pool: ETH/USDC)
Swap successful: outputAmount=29.95 USDC
```

## Troubleshooting

### If CORS Errors Still Appear:

1. **Hard Refresh Browser:**
   - Chrome/Firefox: `Ctrl+Shift+R` (Windows/Linux) or `Cmd+Shift+R` (Mac)
   - This clears cached preflight responses

2. **Check Browser Console Origin:**
   - Open DevTools → Network tab
   - Check request headers: `Origin: http://localhost:4001`
   - If origin is different, update CORS_ALLOWED_ORIGINS

3. **Verify Backend Logs:**
   ```bash
   docker logs backend-service 2>&1 | grep -i cors
   ```

4. **Check Backend Environment:**
   ```bash
   docker exec backend-service env | grep CORS
   ```

### If Data Not Loading:

1. **Check JWT Token:**
   - Open DevTools → Application → Local Storage
   - Should see: `jwt_token`, `refresh_token`, `user_party`

2. **Check Network Requests:**
   - DevTools → Network tab
   - Filter: `XHR`
   - Should see requests to `http://localhost:4080/api/pools`, `/api/tokens/alice`
   - Status should be `200 OK` (not 401, 403, or CORS error)

3. **Check Authorization Header:**
   - Click on a request in Network tab
   - Headers → Request Headers
   - Should see: `Authorization: Bearer eyJhbGc...` (JWT token)

## Technical Details

### CORS Configuration Applied:

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(Arrays.asList(
        "http://localhost:3000",
        "http://localhost:3001",
        "http://localhost:4001"
    ));
    configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Idempotency-Key", "X-Request-ID"));
    configuration.setExposedHeaders(Arrays.asList("Retry-After", "X-Request-ID"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
}
```

### Security Filter Chain:

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .cors(Customizer.withDefaults())  // ← CORS enabled with above config
        .csrf(csrf -> ...)
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/api/pools").permitAll()           // Public endpoint
            .requestMatchers("/api/tokens/*").permitAll()        // Public for testing
            .anyRequest().authenticated()                        // All other endpoints require JWT
        )
        .oauth2ResourceServer(oauth -> oauth.jwt(...));
    return http.build();
}
```

## Current System State

- **Backend Health:** ✅ OK (ledger synced, PQS connected)
- **CORS Configuration:** ✅ Applied (localhost:4001 allowed)
- **OAuth Login:** ✅ Working (alice can authenticate)
- **SSH Tunnels:** ✅ Required (4001→3001, 4080→8080, 4082→8082)
- **Public Endpoints:** `/api/pools`, `/api/tokens/{party}`, `/api/health/**`
- **Protected Endpoints:** `/api/swap/atomic`, `/api/liquidity/add`, `/api/liquidity/remove`

## Next Steps After Testing

1. **Remove Public Access to /api/tokens/{party}**
   - Change from `.permitAll()` to `.authenticated()`
   - Require JWT for all sensitive endpoints

2. **Add CSRF Protection**
   - Currently disabled for `/api/clearportx/**`
   - TODO comment: "Add CSRF back with proper token handling"

3. **Restrict CORS Origins for Production**
   - Remove localhost origins
   - Add devnet frontend URL

4. **Rate Limiting**
   - Already implemented (0.4 TPS global limit)
   - Monitor in Grafana dashboard

---

**Status:** CORS issue resolved ✅
**Backend uptime:** 21h (pool contracts working)
**Ready for:** E2E frontend testing with real Canton data
**User:** alice (logged in via OAuth)
**Environment:** Local development with SSH tunnels
