# Canton Loop Wallet Integration - Complete Implementation Plan

**Date:** October 26, 2025
**Status:** Research Complete - Ready for Implementation
**SDK Version:** @canton-network/dapp-sdk@0.10.0
**Target:** Production wallet connection for ClearportX DEX

---

## Executive Summary

This document provides a comprehensive, step-by-step plan to integrate **Canton Loop wallet** into ClearportX using the official **@canton-network/dapp-sdk** (v0.10.0). This will replace the current mock authentication system with real wallet-based authentication.

### What is Canton Loop?

**Canton Loop** is the first non-custodial wallet for Canton Network, featuring:
- **Passkey-based authentication** (biometric, no seed phrases)
- **WebAuthn security** (hardware-level protection)
- **Privacy-first transactions** (amounts and recipients confidential)
- **FIDO2/YubiKey support** (enterprise-grade security)

### Current State vs Target State

| Aspect | Current (DevNet Mock) | Target (Canton Loop) |
|--------|----------------------|---------------------|
| Authentication | Mock JWT token (`devnet-mock-token`) | Real JWT from Canton Loop |
| Party ID | Hardcoded `app-provider::122...` | User's actual Canton party |
| Connection | Always "Connected (DevNet)" | "Connect Wallet" button |
| Transaction Signing | Automatic (no user approval) | User signs with biometric/passkey |
| Security | Development only | Production-ready |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    ClearportX Frontend                       │
│                     (React + TypeScript)                     │
│                                                              │
│  ┌────────────────┐        ┌─────────────────────────────┐ │
│  │  Connect Wallet│───────>│  @canton-network/dapp-sdk   │ │
│  │     Button     │        │                             │ │
│  └────────────────┘        │  - Provider.connect()       │ │
│                            │  - requestAccounts()        │ │
│  ┌────────────────┐        │  - prepareExecute()         │ │
│  │   Swap / Add   │───────>│  - onTxChanged events       │ │
│  │   Liquidity    │        └─────────────────────────────┘ │
│  └────────────────┘                    │                    │
└────────────────────────────────────────┼────────────────────┘
                                         │
                          RPC over Window PostMessage
                          or HTTP (http://localhost:3030)
                                         │
                                         ▼
                ┌────────────────────────────────────────────┐
                │          Canton Loop Wallet                │
                │       (Wallet Gateway / Kernel)            │
                │                                            │
                │  - User authentication (passkey)           │
                │  - Party selection                         │
                │  - Transaction approval UI                 │
                │  - Biometric signing                       │
                └────────────────────────────────────────────┘
                                         │
                                         │ Ledger API
                                         ▼
                ┌────────────────────────────────────────────┐
                │      Canton Network DevNet                 │
                │                                            │
                │  - 14 Super Validators (BFT)               │
                │  - DAML smart contracts                    │
                │  - Transaction finality                    │
                └────────────────────────────────────────────┘
```

---

## Phase 1: SDK Installation and Setup

### 1.1 Install Dependencies

```bash
cd /root/canton-website/app
npm install @canton-network/dapp-sdk@0.10.0
```

**Installed packages:**
- `@canton-network/dapp-sdk@0.10.0` - Main dApp SDK
- `@canton-network/core-splice-provider` - Provider interface
- `@canton-network/core-types` - TypeScript types
- `@canton-network/core-wallet-dapp-rpc-client` - RPC client
- `@canton-network/core-wallet-ui-components` - UI components

### 1.2 Verify Canton Loop Wallet Gateway is Running

The SDK expects a **Wallet Gateway** at `http://localhost:3030/api/v0/dapp`.

**Check if running:**
```bash
curl http://localhost:3030/api/v0/dapp/status
```

**Expected response:**
```json
{
  "kernel": {
    "id": "canton-wallet-local",
    "clientType": "browser",
    "url": "http://localhost:3030/api/v0/dapp",
    "userUrl": "http://localhost:3030"
  },
  "isConnected": true,
  "chainId": "canton-devnet"
}
```

**If not running:** You need to install and run the Canton Loop wallet locally. This may require:
1. Downloading Canton Loop from official Canton Network repository
2. Running `npm install && npm start` in the wallet directory
3. Ensuring it connects to Canton Network DevNet

---

## Phase 2: Frontend Integration - Wallet Connection

### 2.1 Create Canton Provider Service

**File:** `/root/canton-website/app/src/services/cantonProvider.ts`

```typescript
import { Provider, dappController } from '@canton-network/dapp-sdk';

/**
 * Canton Loop Provider Service
 * Manages connection to Canton wallet and transaction signing
 */
class CantonProviderService {
  private provider: Provider | null = null;
  private controller: any = null;
  private sessionToken: string | null = null;
  private party: string | null = null;

  /**
   * Connect to Canton Loop wallet
   * Opens wallet popup for user authentication
   */
  async connect(): Promise<{ token: string; party: string }> {
    try {
      console.log('🔗 Connecting to Canton Loop wallet...');

      // Discover wallet gateway (checks for window provider or HTTP)
      const discoverResult = await this.discoverWallet();

      // Create provider instance
      this.provider = new Provider(discoverResult);
      this.controller = dappController(this.provider);

      // Call connect() to authenticate user
      const connectResult = await this.controller.connect();

      console.log('✅ Connected to Canton Loop:', connectResult);

      // Store session token
      this.sessionToken = connectResult.sessionToken;

      // Request user's party/account
      const accountsResult = await this.controller.requestAccounts();
      this.party = accountsResult.accounts[0]; // Primary account

      console.log('👤 User party:', this.party);

      // Store in localStorage
      localStorage.setItem('canton_session_token', this.sessionToken);
      localStorage.setItem('user_party', this.party);

      return {
        token: this.sessionToken,
        party: this.party,
      };
    } catch (error) {
      console.error('❌ Canton Loop connection failed:', error);
      throw new Error(`Failed to connect to Canton Loop: ${error.message}`);
    }
  }

  /**
   * Discover Canton wallet gateway
   * Checks for window provider (browser extension) or HTTP gateway (localhost)
   */
  private async discoverWallet(): Promise<any> {
    // Check for window provider first (browser extension)
    if ((window as any).cantonWallet) {
      return {
        walletType: 'window',
        url: null,
      };
    }

    // Fallback to HTTP gateway (local Canton Loop instance)
    const gatewayUrl = 'http://localhost:3030/api/v0/dapp';

    try {
      const response = await fetch(`${gatewayUrl}/status`);
      if (response.ok) {
        return {
          walletType: 'http',
          url: gatewayUrl,
        };
      }
    } catch (error) {
      console.warn('Canton wallet gateway not found at localhost:3030');
    }

    throw new Error(
      'Canton Loop wallet not found. Please install Canton Loop or run the wallet gateway locally.'
    );
  }

  /**
   * Disconnect from Canton Loop
   */
  async disconnect() {
    this.provider = null;
    this.controller = null;
    this.sessionToken = null;
    this.party = null;
    localStorage.removeItem('canton_session_token');
    localStorage.removeItem('user_party');
    console.log('🔌 Disconnected from Canton Loop');
  }

  /**
   * Check if connected
   */
  isConnected(): boolean {
    return this.sessionToken !== null && this.party !== null;
  }

  /**
   * Get current party
   */
  getParty(): string | null {
    return this.party || localStorage.getItem('user_party');
  }

  /**
   * Get session token
   */
  getSessionToken(): string | null {
    return this.sessionToken || localStorage.getItem('canton_session_token');
  }

  /**
   * Prepare and execute a transaction
   * @param commands - DAML commands to execute
   */
  async executeTransaction(commands: any): Promise<any> {
    if (!this.controller) {
      throw new Error('Not connected to Canton Loop');
    }

    try {
      console.log('📝 Preparing transaction...');

      // Prepare transaction
      const prepareResult = await this.controller.prepareExecute({
        commands,
      });

      console.log('✍️ Transaction prepared:', prepareResult);

      // Listen for transaction events
      return new Promise((resolve, reject) => {
        const commandId = prepareResult.commandId;

        // Listen for tx status changes
        this.controller.onTxChanged().then((event: any) => {
          if (event.commandId !== commandId) return;

          switch (event.status) {
            case 'signed':
              console.log('✅ Transaction signed by user');
              break;
            case 'pending':
              console.log('⏳ Transaction pending on Canton Network...');
              break;
            case 'executed':
              console.log('🎉 Transaction executed successfully!');
              resolve(event.payload);
              break;
            case 'failed':
              console.error('❌ Transaction failed:', event);
              reject(new Error('Transaction failed'));
              break;
          }
        });
      });
    } catch (error) {
      console.error('❌ Transaction execution failed:', error);
      throw error;
    }
  }

  /**
   * Listen for account changes (user switches account in wallet)
   */
  onAccountsChanged(callback: (accounts: string[]) => void) {
    if (!this.controller) return;

    this.controller.onAccountsChanged().then((event: any) => {
      console.log('👤 User switched account:', event.accounts);
      this.party = event.accounts[0];
      localStorage.setItem('user_party', this.party);
      callback(event.accounts);
    });
  }
}

export const cantonProvider = new CantonProviderService();
```

### 2.2 Update Auth Service to Use Canton Provider

**File:** `/root/canton-website/app/src/services/auth.ts`

Replace the existing mock authentication with Canton Loop:

```typescript
import { cantonProvider } from './cantonProvider';

const DISABLE_AUTH = process.env.REACT_APP_DISABLE_AUTH === 'true';

class AuthService {
  /**
   * Login via Canton Loop wallet
   */
  async login(): Promise<{ token: string; party: string }> {
    // DevNet mode: Use mock authentication
    if (DISABLE_AUTH) {
      console.log('🔓 Auth disabled - using mock authentication');
      const token = 'devnet-mock-token';
      const party = process.env.REACT_APP_DEFAULT_PARTY || 'app-provider::122...';
      localStorage.setItem('jwt_token', token);
      localStorage.setItem('user_party', party);
      return { token, party };
    }

    // Production mode: Use Canton Loop wallet
    try {
      const result = await cantonProvider.connect();
      return result;
    } catch (error) {
      console.error('Canton Loop login failed:', error);
      throw error;
    }
  }

  /**
   * Logout
   */
  async logout() {
    if (!DISABLE_AUTH) {
      await cantonProvider.disconnect();
    }
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('user_party');
    localStorage.removeItem('canton_session_token');
  }

  /**
   * Check if authenticated
   */
  isAuthenticated(): boolean {
    if (DISABLE_AUTH) return true;
    return cantonProvider.isConnected();
  }

  /**
   * Get current party
   */
  getParty(): string {
    if (DISABLE_AUTH) {
      return localStorage.getItem('user_party') || process.env.REACT_APP_DEFAULT_PARTY || '';
    }
    return cantonProvider.getParty() || '';
  }

  /**
   * Get session token
   */
  getToken(): string | null {
    if (DISABLE_AUTH) {
      return localStorage.getItem('jwt_token');
    }
    return cantonProvider.getSessionToken();
  }
}

export const authService = new AuthService();
```

### 2.3 Update Header Component

**File:** `/root/canton-website/app/src/components/Header.tsx`

The existing header already has the correct logic - it will automatically switch between DevNet mode (showing "Connected (DevNet)") and production mode (showing "Connect Wallet" button) based on the `REACT_APP_DISABLE_AUTH` environment variable.

**No changes needed!**

### 2.4 Update LoginModal to Use Canton Loop

**File:** `/root/canton-website/app/src/components/LoginModal.tsx`

```typescript
import React, { useState } from 'react';
import { authService } from '../services/auth';

interface LoginModalProps {
  onClose: () => void;
}

const LoginModal: React.FC<LoginModalProps> = ({ onClose }) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleConnectWallet = async () => {
    try {
      setLoading(true);
      setError(null);

      // Connect to Canton Loop wallet
      await authService.login();

      console.log('✅ Wallet connected successfully');
      onClose(); // Close modal
      window.location.reload(); // Refresh to update UI
    } catch (err: any) {
      console.error('Wallet connection failed:', err);
      setError(err.message || 'Failed to connect wallet');
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
      <div className="bg-white dark:bg-dark-900 rounded-2xl shadow-2xl p-8 max-w-md w-full">
        <h2 className="text-2xl font-bold mb-4 text-gray-900 dark:text-gray-100">
          Connect Wallet
        </h2>

        <p className="text-gray-600 dark:text-gray-400 mb-6">
          Connect your Canton Loop wallet to trade on ClearportX. Your keys stay secure on your device.
        </p>

        {error && (
          <div className="bg-error-100 border border-error-300 text-error-700 px-4 py-3 rounded mb-4">
            {error}
          </div>
        )}

        <button
          onClick={handleConnectWallet}
          disabled={loading}
          className="w-full btn-primary py-4 text-lg font-semibold disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {loading ? (
            <span className="flex items-center justify-center">
              <svg className="animate-spin h-5 w-5 mr-2" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none"/>
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
              </svg>
              Connecting...
            </span>
          ) : (
            '🔗 Connect Canton Loop'
          )}
        </button>

        <button
          onClick={onClose}
          className="w-full mt-4 py-2 text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100"
        >
          Cancel
        </button>

        <div className="mt-6 pt-6 border-t border-gray-200 dark:border-gray-700">
          <p className="text-xs text-gray-500 dark:text-gray-400 text-center">
            Don't have Canton Loop?{' '}
            <a
              href="https://cantonwallet.com"
              target="_blank"
              rel="noopener noreferrer"
              className="text-primary-600 hover:underline"
            >
              Get it here
            </a>
          </p>
        </div>
      </div>
    </div>
  );
};

export default LoginModal;
```

---

## Phase 3: Transaction Signing Integration

### 3.1 Update Swap API to Use Canton Provider

**File:** `/root/canton-website/app/src/services/backendApi.ts`

Currently, swaps are sent directly to the backend. With Canton Loop, we need to:
1. **Prepare the transaction** on the backend
2. **Send to Canton Loop** for user signing
3. **Submit signed transaction** to Canton Network

**Updated flow:**

```typescript
import { cantonProvider } from './cantonProvider';

class BackendApiService {
  // ... existing code ...

  /**
   * Execute swap with Canton Loop signature
   */
  async executeSwap(params: {
    poolId: string;
    tokenIn: string;
    amountIn: number;
    minAmountOut: number;
  }): Promise<any> {
    const DISABLE_AUTH = process.env.REACT_APP_DISABLE_AUTH === 'true';

    // DevNet mode: Direct backend call (no wallet signing)
    if (DISABLE_AUTH) {
      return this.client.post('/api/swap/execute', params);
    }

    // Production mode: Canton Loop signing flow
    try {
      // Step 1: Prepare swap command
      const commands = {
        swap: {
          poolId: params.poolId,
          tokenIn: params.tokenIn,
          amountIn: params.amountIn,
          minAmountOut: params.minAmountOut,
          party: cantonProvider.getParty(),
        },
      };

      // Step 2: Send to Canton Loop for signing & execution
      const result = await cantonProvider.executeTransaction(commands);

      console.log('✅ Swap executed:', result);
      return result;
    } catch (error) {
      console.error('❌ Swap failed:', error);
      throw error;
    }
  }

  /**
   * Add liquidity with Canton Loop signature
   */
  async addLiquidity(params: {
    poolId: string;
    amountA: number;
    amountB: number;
    minLPTokens: number;
  }): Promise<any> {
    const DISABLE_AUTH = process.env.REACT_APP_DISABLE_AUTH === 'true';

    if (DISABLE_AUTH) {
      return this.client.post('/api/liquidity/add', params);
    }

    try {
      const commands = {
        addLiquidity: {
          poolId: params.poolId,
          amountA: params.amountA,
          amountB: params.amountB,
          minLPTokens: params.minLPTokens,
          party: cantonProvider.getParty(),
        },
      };

      const result = await cantonProvider.executeTransaction(commands);
      console.log('✅ Liquidity added:', result);
      return result;
    } catch (error) {
      console.error('❌ Add liquidity failed:', error);
      throw error;
    }
  }

  // ... rest of existing methods ...
}

export const backendApi = new BackendApiService();
```

---

## Phase 4: Backend Updates

### 4.1 Re-enable OAuth2 JWT Validation

**File:** `/root/cn-quickstart/quickstart/backend/src/main/java/com/digitalasset/quickstart/security/DevNetSecurityConfig.java`

Currently OAuth2 is disabled. We need to re-enable it to validate Canton Loop session tokens:

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
  http
    .cors(Customizer.withDefaults())
    .csrf(csrf -> csrf.disable())
    .authorizeHttpRequests(auth -> auth
      // Public endpoints
      .requestMatchers("/api/health/**", "/api/pools").permitAll()

      // Authenticated endpoints (require Canton Loop JWT)
      .requestMatchers("/api/swap/**", "/api/liquidity/**").authenticated()
      .anyRequest().authenticated()
    )
    // Re-enable OAuth2 with Canton Network issuer
    .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
      .issuer("https://canton-network-devnet") // TODO: Update with actual Canton Network issuer
      .jwkSetUri("https://canton-network-devnet/.well-known/jwks.json") // TODO: Update
    ));

  return http.build();
}
```

**TODO:** Get the actual Canton Network DevNet OAuth2 issuer URL and JWKS endpoint from Canton Network documentation.

### 4.2 Extract Party from JWT Token

**File:** `/root/cn-quickstart/quickstart/backend/src/main/java/com/digitalasset/quickstart/security/PartyExtractor.java`

```java
package com.digitalasset.quickstart.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class PartyExtractor {

  /**
   * Extract Canton party ID from JWT token
   */
  public String extractParty(Authentication authentication) {
    if (authentication == null) {
      throw new IllegalStateException("No authentication found");
    }

    // Canton Loop JWT should have party ID in claims
    if (authentication.getPrincipal() instanceof Jwt jwt) {
      // Try different claim names (check Canton Network JWT structure)
      String party = jwt.getClaimAsString("party");
      if (party != null) return party;

      party = jwt.getClaimAsString("canton_party");
      if (party != null) return party;

      party = jwt.getSubject(); // Fallback to subject
      if (party != null) return party;

      throw new IllegalStateException("No party claim found in JWT");
    }

    throw new IllegalStateException("Invalid authentication type");
  }
}
```

### 4.3 Update Controllers to Use JWT Party

**File:** `/root/cn-quickstart/quickstart/backend/src/main/java/com/digitalasset/quickstart/controller/SwapController.java`

```java
@RestController
@RequestMapping("/api/swap")
public class SwapController {

  @Autowired
  private PartyExtractor partyExtractor;

  @PostMapping("/execute")
  public ResponseEntity<?> executeSwap(
    @RequestBody SwapRequest request,
    Authentication authentication
  ) {
    // Extract party from JWT
    String party = partyExtractor.extractParty(authentication);

    log.info("Executing swap for party: {}", party);

    // Execute swap using this party
    // ...
  }
}
```

---

## Phase 5: Environment Configuration

### 5.1 Production Environment Variables

**File:** `/root/canton-website/app/.env.production`

Update to disable mock auth for production:

```bash
# ========================================
# CLEARPORTX PRODUCTION CONFIGURATION
# Canton Loop Wallet Integration
# Updated: 2025-10-26
# ========================================

# Backend Configuration
REACT_APP_BACKEND_API_URL=https://api.clearportx.com
REACT_APP_CANTON_API_URL=https://api.clearportx.com

# Authentication Configuration
REACT_APP_DISABLE_AUTH=false  # CHANGED: Enable real Canton Loop auth
# REACT_APP_DEFAULT_PARTY is removed (no longer needed)

# Feature Flags
REACT_APP_USE_MOCK=false
REACT_APP_USE_MOCK_DATA=false

# Build Configuration
GENERATE_SOURCEMAP=false
CI=false
NODE_ENV=production
```

### 5.2 Development Environment (Keep Mock Auth)

**File:** `/root/canton-website/app/.env.development`

```bash
# Development with Canton Loop
REACT_APP_BACKEND_API_URL=http://localhost:8080
REACT_APP_DISABLE_AUTH=false  # Test real Canton Loop locally
```

### 5.3 DevNet Testing Environment

**File:** `/root/canton-website/app/.env.devnet`

```bash
# DevNet testing with mock auth
REACT_APP_BACKEND_API_URL=https://nonexplicable-lacily-leesa.ngrok-free.dev
REACT_APP_DISABLE_AUTH=true  # Keep mock auth for DevNet testing
REACT_APP_DEFAULT_PARTY=app-provider::1220414f85e74ed69ca162b9874f3cf9dfa94fb4968823bd8ac9755544fcb5d72388
```

---

## Phase 6: Testing Checklist

### 6.1 Local Development Testing

- [ ] Canton Loop wallet gateway running on `localhost:3030`
- [ ] Click "Connect Wallet" button
- [ ] Canton Loop popup opens
- [ ] Authenticate with passkey/biometric
- [ ] Wallet connects successfully
- [ ] User party ID displayed in console
- [ ] Session token stored in localStorage
- [ ] Navigate to Swap page
- [ ] Input swap amount
- [ ] Click "Swap" button
- [ ] Canton Loop approval popup appears
- [ ] Review transaction details
- [ ] Sign with biometric
- [ ] Transaction submits to Canton Network
- [ ] Swap completes successfully
- [ ] Pool reserves updated
- [ ] Transaction appears in History

### 6.2 Error Handling Testing

- [ ] Canton Loop wallet not installed → Show friendly error message
- [ ] Canton Loop wallet not running → Show "Start Canton Loop" message
- [ ] User rejects connection → Handle gracefully
- [ ] User rejects transaction signature → Show "Transaction cancelled"
- [ ] Network timeout → Retry mechanism
- [ ] Insufficient balance → Show clear error
- [ ] Invalid party ID → Authentication error

### 6.3 Edge Cases

- [ ] User switches account in Canton Loop → Update UI with new party
- [ ] User disconnects wallet → Reset to "Connect Wallet" button
- [ ] Session token expires → Prompt to reconnect
- [ ] Multiple tabs open → Sync state across tabs
- [ ] Page refresh → Restore session from localStorage

---

## Phase 7: Open Questions for Canton Network Team

### Critical Information Needed

1. **Canton Loop Installation:**
   - Where to download Canton Loop for developers?
   - Is there a Canton Loop browser extension for end users?
   - How do end users install Canton Loop?

2. **Wallet Gateway Setup:**
   - How to run Canton Loop wallet gateway locally?
   - Default port and configuration?
   - Required dependencies (Node.js version, etc.)?

3. **OAuth2 / JWT Configuration:**
   - What is the OAuth2 issuer URL for Canton Network DevNet?
   - Where is the JWKS endpoint?
   - What claims are included in the JWT? (party, sub, aud?)
   - How long is the session token valid?

4. **Party Onboarding:**
   - How do users get a Canton party ID?
   - Is party creation automatic when connecting Canton Loop?
   - Or does user need to register via Canton Network portal first?

5. **Transaction Signing:**
   - Are DAML commands passed directly to `prepareExecute()`?
   - Or do we need to convert to a specific format?
   - How does Canton Loop display transaction details to users?

6. **Browser Support:**
   - Which browsers are supported for Canton Loop?
   - Desktop only or mobile too?
   - Any known compatibility issues?

---

## Implementation Timeline

### Week 1: Setup and Basic Connection
- **Day 1-2:** Install Canton Loop wallet gateway, test local connection
- **Day 3:** Implement `cantonProvider.ts` service
- **Day 4:** Update `auth.ts` to use Canton provider
- **Day 5:** Test "Connect Wallet" flow end-to-end

### Week 2: Transaction Signing
- **Day 1-2:** Implement swap transaction signing flow
- **Day 3:** Implement liquidity transaction signing flow
- **Day 4-5:** Handle all error cases and edge cases

### Week 3: Backend Integration
- **Day 1-2:** Re-enable OAuth2 JWT validation
- **Day 3:** Implement party extraction from JWT
- **Day 4-5:** End-to-end testing with real Canton Network

### Week 4: Polish and Deploy
- **Day 1-2:** UI/UX improvements
- **Day 3:** Performance optimization
- **Day 4:** Security audit
- **Day 5:** Deploy to production

**Total Estimated Time:** 4 weeks (80 hours)

---

## Success Criteria

- ✅ Users can connect Canton Loop wallet with one click
- ✅ Party ID correctly extracted and displayed
- ✅ Swaps require biometric signature from user
- ✅ Transaction details shown in Canton Loop approval popup
- ✅ Failed transactions handled gracefully
- ✅ Session persists across page refreshes
- ✅ Multi-account support (user can switch accounts)
- ✅ No mock authentication in production
- ✅ All operations recorded on Canton Network ledger
- ✅ <2 second connection time
- ✅ <5 second transaction signing time

---

## Security Considerations

### What Canton Loop Provides

- ✅ **Private key security:** Keys never leave user's device
- ✅ **Biometric authentication:** Hardware-level liveness checks
- ✅ **Transaction transparency:** Users see full transaction details before signing
- ✅ **WebAuthn standard:** Industry-proven security (used by banks)

### What ClearportX Must Ensure

- ✅ **JWT validation:** Always validate Canton Loop session tokens on backend
- ✅ **Party verification:** Ensure party in JWT matches party in transaction
- ✅ **HTTPS only:** Never send session tokens over HTTP
- ✅ **CORS strict:** Only allow requests from trusted origins
- ✅ **Rate limiting:** Prevent spam from malicious actors
- ✅ **Input validation:** Sanitize all user inputs before sending to Canton Loop

---

## Rollback Plan

If Canton Loop integration fails or has critical bugs:

1. **Immediate:** Set `REACT_APP_DISABLE_AUTH=true` in production
2. **Frontend:** Revert to mock authentication (no deployment needed, just env var)
3. **Backend:** Re-disable OAuth2 (comment out `.oauth2ResourceServer()`)
4. **Users:** No disruption, instant rollback

**Rollback time:** <5 minutes

---

## Next Immediate Actions

1. **Contact Canton Network team** with open questions (see Phase 7)
2. **Install Canton Loop wallet gateway** locally for development
3. **Review Canton Loop documentation** for any recent updates
4. **Test SDK installation** (`npm install @canton-network/dapp-sdk@0.10.0`)
5. **Start Phase 1 implementation** once Canton Loop gateway is running

---

## Resources

- **dApp SDK:** https://www.npmjs.com/package/@canton-network/dapp-sdk
- **Canton Network Docs:** https://docs.digitalasset.com/integrate/devnet/
- **Canton Loop Info:** https://info.send.it/docs/canton-wallet/overview
- **Splice Wallet Kernel:** https://github.com/hyperledger-labs/splice-wallet-kernel
- **Canton Network:** https://www.canton.network/

---

**Document Version:** 1.0
**Last Updated:** October 26, 2025
**Author:** Claude (AI Assistant)
**Status:** Ready for Implementation
