# ClearportX Working Flow (DevNet Phase 3.x)

## Architecture
- Backend: `backend/` Spring Boot service (REST, Result<T, DomainError>) fronted by JWT auth (HMAC wallet tokens). Domain logic drives AMM flows and DevNet debug helpers.
- Frontend (active): `quickstart/frontend` React/TypeScript app (Netlify). Talks to backend via Axios with shared auth headers.
- Ledger: AMM pools and tokens live on-ledger via DAML templates (no DAML changes in this phase); backend mediates ledger access.

## Auth & Wallet Flow
- Wallet connectors: Dev, Loop, and Zoro implement `IWalletConnector` and are orchestrated by `WalletManager`.
- Challenge/verify: UI calls `/api/auth/challenge` (gets challengeId), signs with wallet, then `/api/auth/verify` to obtain a JWT.
- JWT: Contains `sub`/party, `wallet`, `iss`, `iat`, `exp`; signed with HMAC. Backend resource server validates it and resolves the party.
- Storage/headers: Frontend stores the wallet session in localStorage plus an in-memory cache; Axios interceptor attaches `Authorization: Bearer <jwt>` and `X-Party` for every request (public and private).

## Core User Flows (current)
- View pools: `/api/pools` is public; backend normalizes/merges pools. Showcase pool `cc-cbtc-showcase` is the reference pool and can be reset via debug. Pools remain visible when logged out.
- View balances: Legacy token balances via `/api/wallet/tokens/{partyId}` (Token.Token). UI shows balances in Swap/Liquidity when a wallet is connected.
- Add liquidity (DevNet debug): Reset showcase with `/api/clearportx/debug/showcase/reset-pool` using JWT/X-Party. Add liquidity with `/api/clearportx/debug/add-liquidity-by-cid`; mirrors real flow and returns `lpAmount` for the toast.
- Swap: UI resolves the pool CID (resolve-and-grant + cached poolId) and calls `/api/clearportx/debug/swap-by-cid` with JWT + `X-Party`. Response `resolvedOutput` drives the “Swap successful” message.
- Session: Wallet stays connected across refresh until JWT expiry. Pools are visible when logged out; balances/CTAs activate once connected.

## Quick Sanity Checklist
- Backend health: `curl -s http://localhost:8080/actuator/health`
- Pools visible: `curl -s http://localhost:8080/api/pools` → contains `cc-cbtc-showcase`
- Auth flow: call `/api/auth/challenge`, sign, then `/api/auth/verify`; reuse the JWT with `Authorization: Bearer ...` on `/api/pools`
- Wallet tokens: `curl -s http://localhost:8080/api/wallet/tokens/<partyId>` using a valid party (e.g., Dev/manual)
- End-to-end (DevNet debug):
  - Reset pool: POST `/api/clearportx/debug/showcase/reset-pool` (JWT + `X-Party`)
  - Add liquidity: POST `/api/clearportx/debug/add-liquidity-by-cid` (returns `lpAmount`)
  - Swap: POST `/api/clearportx/debug/swap-by-cid` (JWT + `X-Party`, expect `resolvedOutput` > 0)

