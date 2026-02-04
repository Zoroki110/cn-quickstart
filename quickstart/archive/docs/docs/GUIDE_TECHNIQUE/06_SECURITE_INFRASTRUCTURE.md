# MODULE 06 - SÉCURITÉ & INFRASTRUCTURE

**Auteur**: Documentation technique ClearportX  
**Date**: 2025-10-21  
**Version**: 1.0.0  
**Prérequis**: Module 01 (Architecture), Module 04 (Controllers)

---

## TABLE DES MATIÈRES

1. [Vue d'ensemble de la sécurité](#1-vue-densemble-de-la-sécurité)
2. [Rate Limiting - Canton Network Devnet](#2-rate-limiting---canton-network-devnet)
3. [Validation centralisée](#3-validation-centralisée)
4. [Métriques Prometheus](#4-métriques-prometheus)
5. [Configuration Spring Boot](#5-configuration-spring-boot)

---

## 1. VUE D'ENSEMBLE DE LA SÉCURITÉ

### 1.1 Modèle de sécurité ClearportX

```
┌────────────────────────────────────────────────────────────────┐
│ DEFENSE IN DEPTH ARCHITECTURE                                   │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│ Layer 1: Network Security (HTTPS/TLS)                          │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ • TLS 1.3 encryption (end-to-end)                        │   │
│ │ • Certificate validation (Canton Network CA)             │   │
│ │ • No plaintext credentials                               │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│ Layer 2: Authentication (OAuth2 JWT)                           │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ • Keycloak identity provider                             │   │
│ │ • JWT token validation (signature + expiry)              │   │
│ │ • Canton party mapping (JWT subject → party ID)          │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│ Layer 3: Authorization (Spring Security)                       │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ • @PreAuthorize method security                          │   │
│ │ • PartyGuard (isAuthenticated, isPoolParty)              │   │
│ │ • DAML signatory/observer validation                     │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│ Layer 4: Rate Limiting (Devnet Compliance)                     │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ • Global: 0.4 TPS (Canton Network devnet requirement)    │   │
│ │ • Per-party: 10 RPM (prevent abuse)                      │   │
│ │ • Token bucket algorithm (AtomicLong + CAS)              │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│ Layer 5: Input Validation (SwapValidator)                      │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ • Token pair validation (symbolA ≠ symbolB)              │   │
│ │ • Amount validation (> 0, scale=10, not NaN/infinity)    │   │
│ │ • Price impact limits (<= 50%)                           │   │
│ │ • Idempotency key format validation                      │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│ Layer 6: Business Logic Security (DAML)                        │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ • Signatory validation (only authorized parties)         │   │
│ │ • Slippage protection (minOutput check)                  │   │
│ │ • Deadline enforcement (expired swaps rejected)          │   │
│ │ • Reserve validation (reserves > 0)                      │   │
│ │ • Price impact checks (prevent manipulation)             │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

---

## 2. RATE LIMITING - CANTON NETWORK DEVNET

### 2.1 Canton Network Devnet Requirements

**Devnet rate limits** :
- **Global limit** : 0.5 TPS (transactions per second)
- **ClearportX implementation** : 0.4 TPS (safety margin)
- **Reason** : Prevent network congestion, fair resource allocation
- **Enforcement** : Canton Network validators reject excess transactions

**Production (mainnet)** :
- No global rate limit
- Higher throughput (100+ TPS)
- Pricing-based resource allocation

### 2.2 RateLimiterConfig - Token Bucket Implementation

**Fichier**: `config/RateLimiterConfig.java`

**Token Bucket Algorithm** :

```
┌────────────────────────────────────────────────────────────────┐
│ TOKEN BUCKET RATE LIMITER                                       │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│ Concept:                                                        │
│ • Bucket capacity: 1 token                                     │
│ • Refill rate: 0.4 tokens/second (1 token every 2.5 seconds)  │
│ • Request consumes: 1 token                                    │
│ • If bucket empty: HTTP 429 TOO_MANY_REQUESTS                  │
│                                                                 │
│ Timeline:                                                       │
│ t=0.0s  : Request 1 → Consume token → Bucket empty             │
│ t=1.0s  : Request 2 → No token yet → REJECT (429)              │
│ t=2.5s  : Refill complete → Bucket has 1 token                 │
│ t=2.5s  : Request 3 → Consume token → Success                  │
│ t=5.0s  : Refill complete → Bucket has 1 token                 │
│ t=5.0s  : Request 4 → Consume token → Success                  │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

**Implementation** :

```java
@Component
public class RateLimiterConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(RateLimiterConfig.class);
    
    // Global rate limit: 0.4 TPS = 1 request every 2.5 seconds
    private static final double TPS_LIMIT = 0.4;
    private static final long REFILL_INTERVAL_MS = (long) (1000 / TPS_LIMIT);  // 2500ms
    
    // Token bucket state (thread-safe with AtomicLong)
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private final AtomicLong availableTokens = new AtomicLong(1);  // Start with 1 token
    
    /**
     * Check if request is allowed under rate limit.
     * Uses Compare-And-Swap (CAS) for thread-safe token consumption.
     * 
     * @return true if request allowed, false if rate limited
     */
    public boolean allowRequest() {
        long now = System.currentTimeMillis();
        
        // Refill tokens based on elapsed time
        long lastRequest = lastRequestTime.get();
        long elapsed = now - lastRequest;
        
        if (elapsed >= REFILL_INTERVAL_MS) {
            // Refill 1 token (max capacity = 1)
            availableTokens.set(1);
            lastRequestTime.set(now);
        }
        
        // Try to consume 1 token (CAS for thread safety)
        while (true) {
            long tokens = availableTokens.get();
            
            if (tokens <= 0) {
                logger.warn("⚠️  Rate limit exceeded! Global TPS limit: {}", TPS_LIMIT);
                return false;  // No tokens available
            }
            
            // CAS: compare availableTokens with 'tokens', set to tokens-1 if equal
            if (availableTokens.compareAndSet(tokens, tokens - 1)) {
                logger.debug("✓ Rate limit check passed ({} tokens remaining)", tokens - 1);
                return true;  // Token consumed successfully
            }
            
            // CAS failed (another thread consumed token) → retry
        }
    }
    
    /**
     * Spring interceptor to enforce rate limit on all /api/swap/* endpoints.
     */
    @Bean
    public WebMvcConfigurer rateLimiterInterceptor() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(
                        HttpServletRequest request, 
                        HttpServletResponse response, 
                        Object handler
                    ) throws Exception {
                        // Only rate limit swap endpoints (high cost)
                        String path = request.getRequestURI();
                        if (path.startsWith("/api/swap/")) {
                            if (!allowRequest()) {
                                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                                response.setContentType("application/json");
                                response.getWriter().write(
                                    "{\"error\":\"Rate limit exceeded (0.4 TPS)\",\"retryAfter\":2.5}"
                                );
                                return false;  // Block request
                            }
                        }
                        return true;  // Allow request
                    }
                }).addPathPatterns("/api/swap/**");
            }
        };
    }
}
```

**Pourquoi AtomicLong + CAS ?**

```java
// ❌ BAD: Synchronized (blocking, poor concurrency)
private long availableTokens = 1;

public synchronized boolean allowRequest() {
    if (availableTokens <= 0) {
        return false;
    }
    availableTokens--;
    return true;
}
// PROBLEM: All threads block on synchronized lock → bottleneck

// ✅ GOOD: AtomicLong + CAS (lock-free, high concurrency)
private final AtomicLong availableTokens = new AtomicLong(1);

public boolean allowRequest() {
    while (true) {
        long tokens = availableTokens.get();
        if (tokens <= 0) return false;
        
        // CAS: Only succeeds if no other thread modified availableTokens
        if (availableTokens.compareAndSet(tokens, tokens - 1)) {
            return true;  // Success!
        }
        // CAS failed → retry (another thread consumed token first)
    }
}
// BENEFIT: No blocking, multiple threads can check simultaneously
```

### 2.3 Response HTTP 429 TOO_MANY_REQUESTS

**Frontend handling** :

```typescript
async function executeAtomicSwap(request: SwapRequest): Promise<SwapResponse> {
  const response = await fetch('/api/swap/atomic', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${jwt}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(request)
  });
  
  if (response.status === 429) {
    // Rate limited! Parse retry-after
    const error = await response.json();
    const retryAfter = error.retryAfter || 2.5;  // 2.5 seconds
    
    console.warn(`Rate limited! Retry after ${retryAfter}s`);
    
    // Show user-friendly message
    toast.warning(`Too many requests. Please wait ${retryAfter} seconds.`);
    
    // Auto-retry after delay
    await new Promise(resolve => setTimeout(resolve, retryAfter * 1000));
    return executeAtomicSwap(request);  // Retry
  }
  
  return await response.json();
}
```

---

## 3. VALIDATION CENTRALISÉE

### 3.1 SwapValidator - Input Validation Service

**Fichier**: `validation/SwapValidator.java`

**Responsabilité** :
- Valider inputs avant submission à Canton
- Empêcher invalid DAML transactions (saves gas/cost)
- Fournir messages d'erreur clairs

**Méthodes de validation** :

```java
@Component
public class SwapValidator {
    
    /**
     * Validate token pair (symbolA ≠ symbolB)
     */
    public void validateTokenPair(String inputSymbol, String outputSymbol) {
        if (inputSymbol == null || inputSymbol.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Input symbol cannot be empty");
        }
        
        if (outputSymbol == null || outputSymbol.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Output symbol cannot be empty");
        }
        
        if (inputSymbol.equals(outputSymbol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Input and output symbols must be different (cannot swap " + 
                inputSymbol + " for " + inputSymbol + ")");
        }
    }
    
    /**
     * Validate input amount (> 0, not NaN/infinity, scale <= 10)
     */
    public void validateInputAmount(BigDecimal inputAmount) {
        if (inputAmount == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Input amount cannot be null");
        }
        
        // Check if NaN or infinity (shouldn't happen with BigDecimal, but defensive)
        String amountStr = inputAmount.toPlainString();
        if (amountStr.equalsIgnoreCase("NaN") || amountStr.contains("Infinity")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Input amount must be a valid number");
        }
        
        // Check positive
        if (inputAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Input amount must be greater than 0 (got: " + inputAmount + ")");
        }
        
        // Check scale (DAML Numeric 10 = max 10 decimals)
        if (inputAmount.scale() > SwapConstants.SCALE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Input amount has too many decimals (max " + SwapConstants.SCALE + 
                ", got: " + inputAmount.scale() + ")");
        }
        
        // Check reasonable upper bound (prevent overflow)
        BigDecimal maxAmount = new BigDecimal("1000000000");  // 1 billion
        if (inputAmount.compareTo(maxAmount) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Input amount exceeds maximum allowed (" + maxAmount + ")");
        }
    }
    
    /**
     * Validate min output (>= 0, not NaN/infinity)
     */
    public void validateMinOutput(BigDecimal minOutput) {
        if (minOutput == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Min output cannot be null");
        }
        
        if (minOutput.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Min output cannot be negative (got: " + minOutput + ")");
        }
        
        // Check scale
        if (minOutput.scale() > SwapConstants.SCALE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Min output has too many decimals (max " + SwapConstants.SCALE + ")");
        }
    }
    
    /**
     * Validate max price impact (0 <= bps <= 5000)
     */
    public void validateMaxPriceImpact(Integer maxPriceImpactBps) {
        if (maxPriceImpactBps == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Max price impact cannot be null");
        }
        
        if (maxPriceImpactBps < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Max price impact cannot be negative (got: " + maxPriceImpactBps + ")");
        }
        
        // Hardcoded limit: max 50% price impact
        if (maxPriceImpactBps > 5000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Max price impact too high (max 5000 bps = 50%, got: " + 
                maxPriceImpactBps + " bps = " + (maxPriceImpactBps / 100.0) + "%)");
        }
    }
}
```

**Usage dans SwapController** :

```java
@PostMapping("/atomic")
public CompletableFuture<AtomicSwapResponse> atomicSwap(@Valid @RequestBody PrepareSwapRequest req) {
    // HARDENED INPUT VALIDATION using centralized validator
    swapValidator.validateTokenPair(req.inputSymbol, req.outputSymbol);
    swapValidator.validateInputAmount(req.inputAmount);
    swapValidator.validateMinOutput(req.minOutput);
    swapValidator.validateMaxPriceImpact(req.maxPriceImpactBps);
    
    // Proceed with swap (all inputs validated!)
    // ...
}
```

### 3.2 SwapConstants - Centralized Constants

**Fichier**: `constants/SwapConstants.java`

```java
public class SwapConstants {
    
    // DAML Numeric scale (max decimals)
    public static final int SCALE = 10;
    
    // Idempotency cache duration (15 minutes)
    public static final long IDEMPOTENCY_CACHE_DURATION_SECONDS = 900;
    
    // Idempotency header name
    public static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";
    
    // Rate limiting
    public static final double DEVNET_TPS_LIMIT = 0.4;
    public static final double MAINNET_TPS_LIMIT = 100.0;
    
    // Price impact limits
    public static final int MAX_PRICE_IMPACT_BPS = 5000;  // 50%
    
    // Deadline defaults
    public static final long DEFAULT_SWAP_DEADLINE_SECONDS = 300;     // 5 minutes
    public static final long DEFAULT_LIQUIDITY_DEADLINE_SECONDS = 600; // 10 minutes
    
    // Fee structure
    public static final double TOTAL_FEE_RATE = 0.003;     // 0.3%
    public static final double PROTOCOL_FEE_SHARE = 0.25;  // 25% of total fee
    public static final double LP_FEE_SHARE = 0.75;        // 75% of total fee
}
```

---

## 4. MÉTRIQUES PROMETHEUS

### 4.1 PoolMetricsRefresher - Background Metrics Collection

**Fichier**: `metrics/PoolMetricsRefresher.java`

**Rôle** :
- Periodically query pool reserves (every 30 seconds)
- Calculate TVL (Total Value Locked)
- Expose metrics to Prometheus

**Implementation** :

```java
@Component
public class PoolMetricsRefresher {
    
    private static final Logger logger = LoggerFactory.getLogger(PoolMetricsRefresher.class);
    
    private final LedgerReader ledgerReader;
    private final SwapMetrics swapMetrics;
    
    // Prometheus gauge for TVL (per pool)
    private final Map<String, Gauge> tvlGauges = new ConcurrentHashMap<>();
    
    /**
     * Scheduled task: refresh pool metrics every 30 seconds
     */
    @Scheduled(fixedRate = 30000)  // 30 seconds
    public void refreshPoolMetrics() {
        logger.debug("Refreshing pool metrics...");
        
        ledgerReader.pools()
            .thenAccept(pools -> {
                for (PoolDTO pool : pools) {
                    String pairKey = pool.getSymbolA() + "-" + pool.getSymbolB();
                    
                    // Calculate TVL (reserve A + reserve B in USD)
                    BigDecimal reserveA = new BigDecimal(pool.getReserveA());
                    BigDecimal reserveB = new BigDecimal(pool.getReserveB());
                    
                    // For simplicity, assume 1:1 USD conversion (real: use oracle prices)
                    BigDecimal tvlUSD = reserveA.add(reserveB);
                    
                    // Update Prometheus gauge
                    Gauge tvlGauge = tvlGauges.computeIfAbsent(pairKey, key ->
                        Gauge.build()
                            .name("pool_tvl_usd")
                            .help("Total Value Locked in pool (USD)")
                            .labelNames("pair")
                            .register()
                    );
                    
                    tvlGauge.labels(pairKey).set(tvlUSD.doubleValue());
                    
                    logger.debug("Updated TVL for {}: ${}", pairKey, tvlUSD);
                }
                
                logger.info("✅ Refreshed metrics for {} pools", pools.size());
            })
            .exceptionally(ex -> {
                logger.error("❌ Failed to refresh pool metrics: {}", ex.getMessage());
                return null;
            });
    }
}
```

### 4.2 Prometheus Metrics Exposés

**Endpoint** : `http://localhost:8080/actuator/prometheus`

**Métriques disponibles** :

```promql
# Swap counters
swap_prepared_total{pair="ETH-USDC"} 1234
swap_executed_total{pair="ETH-USDC"} 1200
swap_failed_total{pair="ETH-USDC",reason="slippage"} 34

# Swap volume
swap_volume_total{pair="ETH-USDC"} 5432100.50

# Fees collected
protocol_fee_collected_total{token="ETH"} 1.875
lp_fee_collected_total{token="ETH"} 5.625

# Pool metrics
active_pools_count 3
pool_tvl_usd{pair="ETH-USDC"} 3000000.00
pool_tvl_usd{pair="ETH-USDT"} 1500000.00

# Swap execution time (histogram)
swap_execution_time_seconds_bucket{le="0.5"} 800
swap_execution_time_seconds_bucket{le="1.0"} 1100
swap_execution_time_seconds_bucket{le="2.0"} 1200
swap_execution_time_seconds_count 1200
swap_execution_time_seconds_sum 720.5

# PQS sync
pqs_offset 123456
clearportx_contract_count 42
```

**Scraping config** (`prometheus-clearportx.yml`) :

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'clearportx-backend'
    static_configs:
      - targets: ['backend:8080']
    metrics_path: '/actuator/prometheus'
```

---

## 5. CONFIGURATION SPRING BOOT

### 5.1 application.yml - Configuration Production

**Fichier**: `backend/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: clearportx-backend
  
  # Database (PQS PostgreSQL)
  datasource:
    url: jdbc:postgresql://pqs:5432/pqs_db
    username: pqs_user
    password: ${PQS_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 30000
  
  # Security (OAuth2 JWT)
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_URL}/realms/AppProvider
          jwk-set-uri: ${KEYCLOAK_URL}/realms/AppProvider/protocol/openid-connect/certs

# Canton Ledger API
canton:
  ledger:
    host: ${CANTON_HOST:localhost}
    port: ${CANTON_PORT:3901}
    application-id: clearportx-backend
    party-id: ${APP_PROVIDER_PARTY}

# Actuator (metrics, health)
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
  metrics:
    export:
      prometheus:
        enabled: true

# Logging
logging:
  level:
    root: INFO
    com.digitalasset.quickstart: DEBUG
    com.digitalasset.transcode: INFO
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

### 5.2 application-devnet.yml - Devnet Override

**Fichier**: `backend/src/main/resources/application-devnet.yml`

```yaml
# Override for Canton Network devnet deployment
canton:
  ledger:
    host: api.sync.global
    port: 443
    tls: true

# Rate limiting (devnet requirement)
rate-limiter:
  enabled: true
  tps-limit: 0.4

# Keycloak (Canton Network)
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://keycloak.canton.network/realms/AppProvider
```

### 5.3 StartupValidation - Health Checks at Boot

**Fichier**: `config/StartupValidation.java`

```java
@Component
public class StartupValidation implements ApplicationListener<ApplicationReadyEvent> {
    
    private static final Logger logger = LoggerFactory.getLogger(StartupValidation.class);
    
    @Autowired
    private LedgerApi ledgerApi;
    
    @Autowired
    private LedgerHealthService healthService;
    
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        logger.info("🚀 Starting ClearportX backend validation...");
        
        // 1. Validate Canton Ledger API connection
        try {
            ledgerApi.getActiveContracts(Token.class).join();
            logger.info("✅ Canton Ledger API connection OK");
        } catch (Exception e) {
            logger.error("❌ Canton Ledger API connection FAILED: {}", e.getMessage());
            throw new IllegalStateException("Cannot connect to Canton Ledger API", e);
        }
        
        // 2. Validate PQS connection
        try {
            Map<String, Object> health = healthService.getHealthStatus().join();
            String status = (String) health.get("status");
            
            if ("OK".equals(status)) {
                logger.info("✅ PQS connection and sync OK");
            } else if ("SYNCING".equals(status)) {
                logger.warn("⚠️  PQS is syncing (may have lag)");
            } else {
                logger.error("❌ PQS status: {}", status);
            }
        } catch (Exception e) {
            logger.error("❌ PQS connection FAILED: {}", e.getMessage());
            // Don't fail startup (PQS optional for core functionality)
        }
        
        // 3. Log configuration
        logger.info("📋 Configuration:");
        logger.info("  • Rate limiter: {} TPS", getRateLimitTps());
        logger.info("  • Idempotency cache: {} seconds TTL", 
            SwapConstants.IDEMPOTENCY_CACHE_DURATION_SECONDS);
        logger.info("  • Max price impact: {} bps ({}%)", 
            SwapConstants.MAX_PRICE_IMPACT_BPS, 
            SwapConstants.MAX_PRICE_IMPACT_BPS / 100.0);
        
        logger.info("✅ ClearportX backend ready!");
    }
    
    private double getRateLimitTps() {
        String profile = System.getProperty("spring.profiles.active", "localnet");
        return "devnet".equals(profile) 
            ? SwapConstants.DEVNET_TPS_LIMIT 
            : SwapConstants.MAINNET_TPS_LIMIT;
    }
}
```

---

## RÉSUMÉ MODULE 06

Ce module couvre **sécurité et infrastructure** de ClearportX :

1. **Rate Limiting** :
   - Token bucket algorithm (AtomicLong + CAS)
   - Global limit: 0.4 TPS (devnet compliance)
   - HTTP 429 TOO_MANY_REQUESTS avec retry-after
   - Lock-free concurrency (haute performance)

2. **Validation centralisée** :
   - SwapValidator (token pairs, amounts, price impact)
   - SwapConstants (configuration centralisée)
   - Fail-fast (détection avant Canton submission)

3. **Métriques Prometheus** :
   - SwapMetrics (counters, histograms)
   - PoolMetricsRefresher (TVL calculation)
   - Actuator endpoint (/actuator/prometheus)
   - Grafana integration

4. **Configuration Spring Boot** :
   - application.yml (base config)
   - application-devnet.yml (devnet overrides)
   - StartupValidation (health checks at boot)

5. **Defense in Depth** :
   - 6 layers de sécurité (network → business logic)
   - Authentication (OAuth2 JWT)
   - Authorization (Spring Security)
   - Input validation (centralized)
   - Rate limiting (compliance)
   - DAML validation (smart contracts)

**Next Steps** : Module 07 (Configuration & Deployment).

