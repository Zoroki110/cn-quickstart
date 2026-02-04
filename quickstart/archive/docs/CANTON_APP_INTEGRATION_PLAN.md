# Plan: ClearportX as a Full Canton Network Application

## Executive Summary
Transform ClearportX from a standalone AMM DEX into a fully integrated Canton Network application with proper authentication, party management, and ledger integration.

## Architecture Overview

### Current State
- ✅ Drain+Credit pattern implemented
- ✅ Backend running on Canton 3.4.7
- ✅ Manual party ID management
- ❌ No Canton wallet authentication
- ❌ No automatic party discovery
- ❌ No Canton Network OAuth2 integration

### Target State
- Full Canton Network authentication via wallet
- Automatic party provisioning and discovery
- OAuth2/JWT integration with Canton Network
- Multi-party transaction coordination
- CIP-0056 token metadata compliance

---

## Phase 1: Canton Authentication Infrastructure (Week 1)

### 1.1 Implement Result<T> Pattern for Error Handling
**Why**: Canton operations can fail in many ways (network, permissions, validation). Result pattern makes error handling explicit and composable.

**Implementation**:
```java
public class Result<T> {
    private final T value;
    private final CantonError error;

    // Chain operations without nested try/catch
    public <U> Result<U> flatMap(Function<T, Result<U>> mapper) {
        return isSuccess() ? mapper.apply(value) : Result.error(error);
    }

    // Recover from specific errors
    public Result<T> recover(Function<CantonError, T> recovery) {
        return isError() ? Result.ok(recovery.apply(error)) : this;
    }
}
```

**Files to Create**:
- `backend/src/main/java/com/digitalasset/quickstart/canton/Result.java`
- `backend/src/main/java/com/digitalasset/quickstart/canton/CantonError.java`
- `backend/src/main/java/com/digitalasset/quickstart/canton/ResultHelper.java`

### 1.2 Party Validation Service
**Why**: Canton party IDs have specific format (`namespace::fingerprint`) and must exist on ledger.

**Implementation**:
```java
@Service
public class PartyValidationService {

    public Result<ValidatedParty> validateParty(String partyId) {
        return validateFormat(partyId)
            .flatMap(this::validateOnLedger)
            .flatMap(this::validatePermissions)
            .flatMap(this::cacheValidation);
    }

    private Result<String> validateFormat(String partyId) {
        // Format: namespace::64-hex-chars
        Pattern pattern = Pattern.compile("^[\\w-]+::[0-9a-f]{64}$");
        return pattern.matcher(partyId).matches()
            ? Result.ok(partyId)
            : Result.error(INVALID_PARTY_FORMAT);
    }
}
```

**Files to Create**:
- `backend/src/main/java/com/digitalasset/quickstart/canton/PartyValidationService.java`
- `backend/src/main/java/com/digitalasset/quickstart/canton/ValidatedParty.java`

---

## Phase 2: Canton Wallet Integration (Week 1-2)

### 2.1 Challenge-Response Authentication
**Why**: Prove ownership of party private key without exposing it.

**Flow**:
1. Client sends party ID
2. Server generates challenge (32 random bytes)
3. Client signs challenge with private key
4. Server verifies signature on ledger
5. Server issues JWT session token

**Implementation**:
```java
@RestController
@RequestMapping("/api/canton/auth")
public class CantonAuthController {

    @PostMapping("/challenge")
    public Result<ChallengeResponse> createChallenge(@RequestBody ChallengeRequest req) {
        return partyValidation.validateParty(req.getPartyId())
            .flatMap(party -> challengeService.createChallenge(party))
            .map(challenge -> new ChallengeResponse(
                challenge.getSessionId(),
                challenge.getChallenge(),
                challenge.getExpiresAt()
            ));
    }

    @PostMapping("/verify")
    public Result<AuthToken> verifySignature(@RequestBody VerifyRequest req) {
        return challengeService.getChallenge(req.getSessionId())
            .flatMap(challenge -> signatureVerifier.verify(
                challenge.getPartyId(),
                challenge.getChallenge(),
                req.getSignature()
            ))
            .flatMap(verified -> jwtService.createToken(verified.getPartyId()));
    }
}
```

**Files to Create**:
- `backend/src/main/java/com/digitalasset/quickstart/canton/auth/CantonAuthController.java`
- `backend/src/main/java/com/digitalasset/quickstart/canton/auth/ChallengeService.java`
- `backend/src/main/java/com/digitalasset/quickstart/canton/auth/SignatureVerifier.java`
- `backend/src/main/java/com/digitalasset/quickstart/canton/auth/JwtService.java`

### 2.2 Canton Signature Verification
**Why**: Must verify signatures using Canton's cryptographic standards.

**Implementation**:
```java
@Service
public class CantonSignatureVerifier {

    public Result<Boolean> verifySignature(
        String partyId,
        byte[] challenge,
        String signatureBase64
    ) {
        // Extract public key from party ID
        String fingerprint = extractFingerprint(partyId);

        // Reconstruct public key from fingerprint
        PublicKey publicKey = reconstructPublicKey(fingerprint);

        // Verify signature using Canton's algorithm (Ed25519/ECDSA)
        return verifyEd25519(publicKey, challenge, decode(signatureBase64));
    }
}
```

---

## Phase 3: Canton Network OAuth2 Integration (Week 2)

### 3.1 OAuth2 Client Configuration
**Why**: Canton Network uses OAuth2 for production authentication.

**Configuration**:
```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          canton-network:
            client-id: ${CANTON_CLIENT_ID}
            client-secret: ${CANTON_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/canton"
            scope: openid, profile, ledger:read, ledger:write
        provider:
          canton-network:
            issuer-uri: https://login.canton.network/realms/canton-network
            authorization-uri: https://login.canton.network/realms/canton-network/protocol/openid-connect/auth
            token-uri: https://login.canton.network/realms/canton-network/protocol/openid-connect/token
            jwk-set-uri: https://login.canton.network/realms/canton-network/protocol/openid-connect/certs
```

### 3.2 JWT Token Validation
**Why**: Validate Canton Network JWTs for API access.

**Implementation**:
```java
@Component
public class CantonJwtValidator {

    public Result<CantonClaims> validateToken(String token) {
        return extractClaims(token)
            .flatMap(this::validateIssuer)
            .flatMap(this::validateExpiry)
            .flatMap(this::validatePartyId)
            .flatMap(this::validatePermissions);
    }

    private Result<CantonClaims> extractClaims(String token) {
        // Decode JWT and extract Canton-specific claims
        // - party_id: The authenticated party
        // - permissions: Array of ledger permissions
        // - namespace: Canton namespace
    }
}
```

---

## Phase 4: Multi-Party Transaction Coordination (Week 2-3)

### 4.1 Transaction Builder Service
**Why**: Complex transactions require multiple party signatures.

**Implementation**:
```java
@Service
public class MultiPartyTransactionService {

    public Result<TransactionResult> executeMultiPartySwap(
        SwapRequest request,
        Set<String> requiredParties
    ) {
        return collectSignatures(requiredParties, request)
            .flatMap(signatures -> buildTransaction(request, signatures))
            .flatMap(this::submitToLedger)
            .flatMap(this::waitForConfirmation);
    }

    private Result<Map<String, Signature>> collectSignatures(
        Set<String> parties,
        SwapRequest request
    ) {
        // Parallel signature collection with timeout
        return ResultHelper.parallel(
            parties.stream()
                .map(party -> requestSignature(party, request))
                .collect(Collectors.toList())
        );
    }
}
```

### 4.2 Party Discovery Service
**Why**: Automatically find parties for pools, tokens, and operations.

**Implementation**:
```java
@Service
public class PartyDiscoveryService {

    public Result<Set<String>> discoverPoolParties(String poolId) {
        return ledgerApi.queryPool(poolId)
            .map(pool -> Set.of(
                pool.getPoolParty(),
                pool.getIssuerA(),
                pool.getIssuerB(),
                pool.getLpIssuer()
            ));
    }

    public Result<String> discoverTokenIssuer(String symbol) {
        // Query ledger for token template with matching symbol
        return ledgerApi.queryActiveContracts(Token.class)
            .map(contracts -> contracts.stream()
                .filter(c -> c.payload.getSymbol().equals(symbol))
                .map(c -> c.payload.getIssuer())
                .findFirst()
                .orElseThrow(() -> new TokenNotFound(symbol)));
    }
}
```

---

## Phase 5: CIP-0056 Token Metadata Integration (Week 3)

### 4.1 Token Metadata Service
**Why**: CIP-0056 standardizes token metadata on Canton Network.

**Implementation**:
```java
@Service
public class TokenMetadataService {

    public Result<TokenMetadata> getMetadata(String tokenSymbol) {
        return tokenRegistry.lookup(tokenSymbol)
            .recover(error -> fetchFromLedger(tokenSymbol))
            .map(this::enrichWithCIP0056);
    }

    private TokenMetadata enrichWithCIP0056(BasicToken token) {
        return TokenMetadata.builder()
            .symbol(token.getSymbol())
            .name(token.getName())
            .decimals(token.getDecimals())
            .logoUri(getCIP0056LogoUri(token))
            .chainId("canton-network-devnet")
            .contractAddress(token.getContractId())
            .build();
    }
}
```

---

## Phase 6: Frontend Canton Wallet Integration (Week 3-4)

### 6.1 Wallet Connection Component
**Why**: Users need seamless wallet connection experience.

**Implementation**:
```typescript
// hooks/useCantonWallet.ts
export const useCantonWallet = () => {
    const [wallet, setWallet] = useState<CantonWallet | null>(null);

    const connect = async () => {
        // 1. Request party ID from wallet extension
        const partyId = await window.canton?.requestPartyId();

        // 2. Get challenge from backend
        const { sessionId, challenge } = await api.createChallenge(partyId);

        // 3. Sign challenge with wallet
        const signature = await window.canton?.signChallenge(challenge);

        // 4. Verify signature and get JWT
        const { token } = await api.verifySignature(sessionId, signature);

        // 5. Store token and update state
        localStorage.setItem('canton-jwt', token);
        setWallet({ partyId, token });
    };

    return { wallet, connect, disconnect };
};
```

### 6.2 Transaction Signing Flow
**Implementation**:
```typescript
// components/SwapModal.tsx
const executeSwap = async () => {
    // 1. Build transaction request
    const txRequest = {
        poolId,
        inputToken,
        inputAmount,
        outputToken,
        minOutput
    };

    // 2. Request signature from wallet
    const signature = await window.canton?.signTransaction(txRequest);

    // 3. Submit signed transaction to backend
    const result = await api.submitSignedSwap(txRequest, signature);

    // 4. Show confirmation
    showTxSuccess(result.receiptCid);
};
```

---

## Phase 7: Production Deployment (Week 4)

### 7.1 Security Hardening
- [ ] Rate limiting per party ID
- [ ] Signature replay protection
- [ ] Challenge expiry enforcement
- [ ] JWT rotation strategy
- [ ] Audit logging for all operations

### 7.2 Performance Optimization
- [ ] Party validation caching (Redis)
- [ ] Signature verification optimization
- [ ] Connection pooling for ledger API
- [ ] Batch transaction processing

### 7.3 Monitoring & Observability
- [ ] Canton-specific metrics (party count, signature success rate)
- [ ] Authentication flow tracing
- [ ] Multi-party coordination monitoring
- [ ] CIP-0056 compliance tracking

---

## Success Criteria

### Authentication
- [ ] Users can connect Canton wallet
- [ ] Challenge-response flow works
- [ ] JWT tokens properly validated
- [ ] OAuth2 integration with Canton Network

### Transactions
- [ ] Multi-party swaps execute correctly
- [ ] Signatures verified on-ledger
- [ ] Party discovery automatic
- [ ] Transaction receipts generated

### Compliance
- [ ] CIP-0056 metadata served
- [ ] Canton Network standards met
- [ ] Security audit passed
- [ ] Performance requirements achieved

---

## Timeline

| Week | Phase | Deliverables |
|------|-------|-------------|
| 1 | Authentication Infrastructure | Result pattern, Party validation |
| 1-2 | Wallet Integration | Challenge-response, Signature verification |
| 2 | OAuth2 Integration | Canton Network OAuth2, JWT validation |
| 2-3 | Multi-Party Coordination | Transaction builder, Party discovery |
| 3 | CIP-0056 Integration | Token metadata service |
| 3-4 | Frontend Integration | Wallet connection, Transaction signing |
| 4 | Production Deployment | Security, Performance, Monitoring |

---

## Risk Mitigation

### Technical Risks
- **Signature Algorithm Changes**: Abstract verification behind interface
- **Canton API Updates**: Use versioned endpoints
- **Network Latency**: Implement async operations with timeouts

### Operational Risks
- **Key Management**: Never store private keys, only public key fingerprints
- **Party Impersonation**: Always verify signatures on-ledger
- **Replay Attacks**: Include nonce/timestamp in challenges

---

## Next Steps

1. **Immediate Actions**:
   - Set up Canton Network DevNet credentials
   - Implement Result<T> pattern foundation
   - Create party validation service

2. **Week 1 Goals**:
   - Complete authentication infrastructure
   - Test challenge-response flow
   - Integrate with existing backend

3. **Success Metrics**:
   - 100% of transactions authenticated
   - <500ms signature verification time
   - Zero security vulnerabilities

---

## References

- Canton Network Authentication: https://docs.canton.network/auth
- CIP-0056 Token Standard: https://github.com/canton-network/CIPs/blob/main/CIP-0056.md
- OAuth2 Integration Guide: https://docs.canton.network/oauth2
- Party Management Best Practices: https://docs.canton.network/parties