// ClearportX Backend API Service
// Connects to Spring Boot backend at http://localhost:8080
import axios, { AxiosInstance, AxiosError } from 'axios';
import { TokenInfo, PoolInfo, SwapQuote } from '../types/canton';
import { getAccessToken, getPartyId } from './auth';
import { BUILD_INFO } from '../config/build-info';

type DomainError = {
  code: string;
  message: string;
  retryAfterMs?: number;
  httpStatus?: number;
};
const runtimeBackendUrl = (typeof window !== 'undefined' && (window as any).__BACKEND_URL__) || undefined;
const BACKEND_URL =
  process.env.REACT_APP_BACKEND_API_URL ||
  BUILD_INFO?.features?.backendUrl ||
  runtimeBackendUrl ||
  'http://localhost:8080';

/**
 * Map frontend party names to Canton ledger party IDs
 * This allows using friendly names like "alice" in the frontend
 * while using the real Canton party IDs in backend calls
 */
const DEVNET_PARTY = process.env.REACT_APP_PARTY_ID 
  || 'ClearportX-DEX-1::122043801dccdfd8c892fa46ebc1dafc901f7992218886840830aeef1cf7eacedd09';

const PARTY_MAPPING: Record<string, string> = {
  'alice@clearportx': DEVNET_PARTY,
  'alice': DEVNET_PARTY,
  'bob': DEVNET_PARTY,
  'AppProvider': DEVNET_PARTY,
  'app-provider': DEVNET_PARTY,
};

/**
 * Map frontend party name to Canton party ID
 */
function mapPartyToBackend(frontendParty: string): string {
  return PARTY_MAPPING[frontendParty] || frontendParty;
}

export interface SwapParams {
  poolId?: string; // Optional - backend auto-discovers by token pair
  inputSymbol: string;
  outputSymbol: string;
  inputAmount: string; // BigDecimal string "0.1000000000"
  minOutput: string;
  maxPriceImpactBps: number; // e.g., 200 for 2%
}

export interface AtomicSwapResponse {
  receiptCid: string;
  trader: string;
  inputSymbol: string;
  outputSymbol: string;
  amountIn: string;
  amountOut: string;
  timestamp: string;
}

export interface AddLiquidityParams {
  poolId: string;
  amountA: string;
  amountB: string;
  minLPTokens: string;
}

export interface RemoveLiquidityParams {
  poolId: string;
  lpTokenAmount: string;
  minAmountA: string;
  minAmountB: string;
}

export interface HealthResponse {
  status: string;
  env: string;
  darVersion: string;
  atomicSwapAvailable: boolean;
  poolsActive: number;
  synced: boolean;
  pqsOffset?: number;
  clearportxContractCount?: number;
}

export class BackendApiService {
  private client: AxiosInstance;

  constructor() {
    this.client = axios.create({
      baseURL: BACKEND_URL,
      timeout: 30000,
      headers: {
        'Content-Type': 'application/json',
        'ngrok-skip-browser-warning': 'true', // Skip ngrok warning page
      },
    });

    // JWT and X-Party interceptor
    this.client.interceptors.request.use((config) => {
      // Get token from Keycloak auth service
      const token = getAccessToken();

      // Get party ID from token or fallback
      const party = getPartyId() || DEVNET_PARTY;

      // Public endpoints that don't need JWT authentication
      const publicEndpoints = [
        '/api/pools',
        '/api/health',
        '/api/tokens/',
        '/api/debug/',
        '/api/clearportx/debug/',
      ];
      const isPublicEndpoint = publicEndpoints.some(endpoint => config.url?.includes(endpoint));

      // Add Authorization header for all protected endpoints (swap, liquidity)
      if (token && !isPublicEndpoint) {
        config.headers.Authorization = `Bearer ${token}`;
        console.log('🔐 Adding JWT to request:', config.url);
      } else if (!token && !isPublicEndpoint) {
        console.warn('⚠️ No JWT token found for protected endpoint:', config.url);
      }

      // Always inject X-Party header (backend uses this for party context)
      if (party) {
        config.headers['X-Party'] = party;
        console.log('👤 Adding X-Party header:', party.substring(0, 30) + '...');
      }

      return config;
    });

    // Retry on 429 (rate limit) only; 409 handled by request() wrapper
    this.client.interceptors.response.use(
      (response) => response,
      async (error: AxiosError) => {
        if (error.response?.status === 429) {
          const retryAfter = parseInt((error.response.headers['retry-after'] as string) || '3', 10);
          const waitMs = isNaN(retryAfter) ? 3000 : retryAfter * 1000;
          console.log(`⚠️  Rate limited, retrying after ${Math.round(waitMs / 1000)}s...`);
          await new Promise(resolve => setTimeout(resolve, waitMs));
          // Retry the original request
          return this.client.request(error.config!);
        }
        throw error;
      }
    );
  }

  private hasJwt(): boolean {
    const token = getAccessToken();
    return !!token && token !== 'devnet-mock-token';
  }

  // Resolve a fresh pool CID for a given poolId by choosing a pool whose canonical token CIDs are alive for poolParty
  private async resolveFreshPoolCidById(poolId: string): Promise<string | null> {
    try {
      const appParty = getPartyId() || DEVNET_PARTY;
      // Resolve poolParty from poolId
      const parties = await this.client.post('/api/debug/pool-parties', { poolId }).then(r => r.data?.parties).catch(() => null);
      const poolParty = parties?.poolParty;
      if (!poolParty) return null;
      // Fetch all pools visible to appParty
      const cidsRes = await this.client.get('/api/clearportx/debug/party-acs', {
        params: { template: 'AMM.Pool.Pool' },
        headers: { 'X-Party': appParty },
      });
      const poolCids: string[] = cidsRes.data?.cids || [];
      // Fetch poolParty tokens once
      const poolPartyTokensRes = await this.client.get('/api/clearportx/debug/party-acs', {
        params: { template: 'Token.Token.Token' },
        headers: { 'X-Party': poolParty },
      }).catch(() => null);
      const poolPartyTokenCidSet: Set<string> =
        new Set<string>((poolPartyTokensRes?.data?.cids as string[] | undefined) || []);
      const emptyPools: string[] = [];
      const aliveCanonPools: string[] = [];
      for (const cid of poolCids) {
        try {
          const meta = await this.client.get('/api/clearportx/debug/pool-by-cid', {
            params: { cid },
            headers: { 'X-Party': appParty },
          }).then(r => r.data);
          if (!meta?.success) continue;
          if (meta.poolId !== poolId) continue;
          const ca = meta.tokenACid as string | null;
          const cb = meta.tokenBCid as string | null;
          if (!ca && !cb) emptyPools.push(cid);
          else if (ca && cb && poolPartyTokenCidSet.has(ca) && poolPartyTokenCidSet.has(cb)) aliveCanonPools.push(cid);
        } catch {
          // ignore and continue
        }
      }
      if (emptyPools.length > 0) return emptyPools[0];
      if (aliveCanonPools.length > 0) return aliveCanonPools[0];
      return null;
    } catch {
      return null;
    }
  }

  // Sleep helper for pacing (DevNet-friendly)
  private async sleep(ms: number) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  private currentParty(): string {
    return getPartyId() || DEVNET_PARTY;
  }

  // Expose current acting party to UI (for consistent token refresh)
  getCurrentParty(): string {
    return this.currentParty();
  }

  // Always resolve a fresh, party-visible CID for a poolId (server guarantees visibility)
  private async resolveAndGrant(poolId: string, party: string): Promise<{ poolCid: string; poolId: string }> {
    const res = await this.request<any>(() =>
      this.client.post('/api/debug/resolve-and-grant', { poolId, party }, { headers: { 'X-Party': party } })
    );
    if (!res?.success || !res?.poolCid) {
      throw new Error(`Resolver failed for ${poolId}`);
    }
    return { poolCid: res.poolCid as string, poolId: res.poolId as string };
  }

  // Centralized request wrapper with single paced 409 retry
  private async request<T>(call: () => Promise<{ data: T }>): Promise<T> {
    try {
      const res = await call();
      return res.data;
    } catch (e: any) {
      const err = e as AxiosError<any>;
      const status = err.response?.status;
      const body = err.response?.data || {};
      if (status === 409) {
        const retryMs = typeof body.retry_after_ms === 'number' && body.retry_after_ms > 0
          ? body.retry_after_ms
          : 3500;
        console.log(`⚠️  409 Stale/Visibility, retrying once after ${Math.round(retryMs / 1000)}s...`);
        await this.sleep(retryMs);
        const res2 = await call();
        return res2.data;
      }
      const domain: DomainError = {
        code: body.code || 'INTERNAL',
        message: body.message || body.error || err.message || 'Internal error',
        retryAfterMs: body.retry_after_ms,
        httpStatus: status,
      };
      throw domain;
    }
  }

  // Use backend summary of pools visible to a party (includes poolCid and reserves)
  private async fetchPoolsForParty(party: string): Promise<Array<{ poolId: string; poolCid: string; reserveA: string; reserveB: string; symbolA: string; symbolB: string }>> {
    try {
      // Prefer POST to match backend behavior; fallback to GET for compatibility
      let list: any[] = [];
      try {
        const resPost = await this.client.post('/api/debug/pools-for-party', { party });
        list = Array.isArray(resPost.data) ? resPost.data : [];
      } catch {
        const resGet = await this.client.get('/api/debug/pools-for-party', { params: { party } });
        list = Array.isArray(resGet.data) ? resGet.data : [];
      }
      return list as any[];
    } catch {
      return [];
    }
  }

  // Directory mapping (poolId -> latest poolCid); maintained by backend on updates
  private async getDirectoryLatestCid(poolId: string): Promise<string | null> {
    try {
      const res = await this.client.get('/api/clearportx/debug/directory');
      const mapping = res.data?.mapping || {};
      const entry = mapping[poolId];
      const cid = entry?.poolCid || null;
      return cid;
    } catch {
      return null;
    }
  }

  // Ensure chosen poolCid is visible to acting party; grant if needed; return (possibly) updated CID
  private async ensurePoolCidVisible(poolCid: string, poolId: string, party: string): Promise<string> {
    try {
      const probe = await this.request<any>(() => this.client.get('/api/clearportx/debug/pool/fetch-cid', {
        params: { cid: poolCid },
        headers: { 'X-Party': party },
      }));
      if (probe?.visible === true) return poolCid;
    } catch {
      // fall through to grant
    }
    // Do NOT attempt grant over remote. Pick a party-visible CID for same poolId or by pair.
    try {
      const rows = await this.fetchPoolsForParty(party);
      const sameId = rows.find(r => r.poolId === poolId);
      if (sameId) return sameId.poolCid;
      const m = (poolId || '').toUpperCase().match(/([A-Z]+)-([A-Z]+)/);
      if (m && m.length >= 3) {
        const [_, a, b] = m;
        const byPair = rows.filter(r =>
          (r.symbolA === a && r.symbolB === b) || (r.symbolA === b && r.symbolB === a)
        );
        if (byPair.length > 0) {
          const chosen = [...byPair].sort((x, y) =>
            (parseFloat(y.reserveA) * parseFloat(y.reserveB)) - (parseFloat(x.reserveA) * parseFloat(x.reserveB))
          )[0];
          return chosen.poolCid;
        }
      }
    } catch {
      // ignore
    }
    // Last resort: return original and let caller retry candidates
    return poolCid;
  }

  // Choose pool CID for a poolId: prefer empty pool (0/0) for first add; else pick highest TVL
  private async resolvePoolCidForIdPreferEmpty(poolId: string): Promise<string | null> {
    const party = this.currentParty();
    const pools = await this.fetchPoolsForParty(party);
    const forId = pools.filter(p => p.poolId === poolId);
    if (forId.length === 0) return null;
    const empties = forId.filter(p => parseFloat(p.reserveA) === 0 && parseFloat(p.reserveB) === 0);
    if (empties.length > 0) return empties[0].poolCid;
    // pick by TVL proxy (reserveA*reserveB) descending
    const sorted = [...forId].sort((a, b) => {
      const ta = parseFloat(a.reserveA) * parseFloat(a.reserveB);
      const tb = parseFloat(b.reserveA) * parseFloat(b.reserveB);
      return tb - ta;
    });
    return sorted[0].poolCid;
  }

  /**
   * Health check - verify backend is running and synced
   */
  async healthCheck(): Promise<HealthResponse> {
    try {
      const res = await this.client.get('/api/health/ledger');
      return res.data;
    } catch (error) {
      console.error('Health check failed:', error);
      // Return a default response indicating the backend is down
      return {
        status: 'DOWN',
        env: 'unknown',
        darVersion: 'unknown',
        atomicSwapAvailable: false,
        poolsActive: 0,
        synced: false
      };
    }
  }

  /**
   * Get all active pools (party-scoped if possible; fallback to global)
   * Returns unique pools by token pair, sorted by TVL proxy
   */
  async getPools(): Promise<PoolInfo[]> {
    try {
      // Prefer party-scoped pools for freshness and correct CID instance
      const party = this.currentParty();
      const partyRows = await this.fetchPoolsForParty(party);
      let allPools: PoolInfo[];
      if (partyRows.length > 0) {
        allPools = partyRows.map((row: any) => this.mapPartyPool(row));
      } else {
        // Fallback to global pools endpoint for public browsing
        const res = await this.client.get('/api/pools');
        const poolData = Array.isArray(res.data) ? res.data : [];
        allPools = poolData.map((data: any) => this.mapPool(data));
      }

      // Filter pools with liquidity (reserveA > 0 and reserveB > 0)
      const activePools = allPools.filter((pool: PoolInfo) =>
        pool.reserveA > 0 && pool.reserveB > 0
      );

      // Remove duplicates by token pair
      const uniquePools: PoolInfo[] = [];
      const seenPairs = new Set<string>();

      for (const pool of activePools) {
        const pairKey = [pool.tokenA.symbol, pool.tokenB.symbol].sort().join('/');

        if (!seenPairs.has(pairKey)) {
          seenPairs.add(pairKey);
          uniquePools.push(pool);
        }
      }

      // Sort by TVL proxy
      const sortedPools = uniquePools.sort((a: PoolInfo, b: PoolInfo) => {
        const tvlA = a.reserveA * a.reserveB;
        const tvlB = b.reserveA * b.reserveB;
        return tvlB - tvlA;
      });

      return sortedPools;
    } catch (error) {
      console.error('Error loading pools:', error);
      return [];
    }
  }

  /**
   * Get tokens owned by a party
   * Maps frontend party names (like 'alice') to Canton party IDs
   */
  async getTokens(party: string): Promise<TokenInfo[]> {
    const cantonParty = mapPartyToBackend(party);
    console.log(`Getting tokens for ${party} (mapped to ${cantonParty})`);
    
    try {
      const res = await this.client.get(`/api/tokens/${cantonParty}`);
      
      // Ensure res.data is an array
      const tokenData = Array.isArray(res.data) ? res.data : [];
      
      // Map all tokens
      const allTokens = tokenData.map((data: any) => this.mapToken(data));

    // Aggregate tokens by symbol (sum balances of same token)
    const tokenMap = new Map<string, TokenInfo>();

    for (const token of allTokens) {
      const existing = tokenMap.get(token.symbol);
      if (existing) {
        // Sum the balances
        existing.balance = (existing.balance || 0) + (token.balance || 0);
      } else {
        // First occurrence of this symbol
        tokenMap.set(token.symbol, { ...token });
      }
    }

    // Convert map to array and filter out tokens with 0 balance
    const aggregatedTokens = Array.from(tokenMap.values())
      .filter(token => (token.balance || 0) > 0)
      .sort((a, b) => (b.balance || 0) - (a.balance || 0)); // Sort by balance descending

    console.log(`Aggregated ${allTokens.length} tokens into ${aggregatedTokens.length} unique tokens`);

    return aggregatedTokens;
    } catch (error) {
      console.error('Error loading tokens:', error);
      return [];
    }
  }

  /**
   * Get pool CID for a given pool by matching token symbols
   * Fetches pool CIDs from the backend and finds matching pool
   */
  private async getPoolCidBySymbols(tokenASymbol: string, tokenBSymbol: string): Promise<string | null> {
    try {
      // Get the party ID
      const party = getPartyId() || DEVNET_PARTY;

      // Fetch all pools visible to this party
      const res = await this.client.get('/api/clearportx/debug/party-acs', {
        params: { template: 'AMM.Pool.Pool' },
        headers: { 'X-Party': party }
      });

      const poolCids = res.data?.cids || [];
      console.log(`Found ${poolCids.length} pool CIDs for party`);

      // For each pool CID, fetch details to find matching symbols
      for (const cid of poolCids) {
        try {
          const poolRes = await this.client.get('/api/clearportx/debug/pool-by-cid', {
            params: { cid },
            headers: { 'X-Party': party }
          });

          const poolData = poolRes.data;
          if (poolData.success &&
              ((poolData.symbolA === tokenASymbol && poolData.symbolB === tokenBSymbol) ||
               (poolData.symbolA === tokenBSymbol && poolData.symbolB === tokenASymbol))) {
            console.log(`Found matching pool: ${poolData.poolId} with CID: ${cid}`);
            return cid;
          }
        } catch (error) {
          console.error(`Error fetching pool details for CID ${cid}:`, error);
        }
      }

      console.warn(`No pool found for ${tokenASymbol}/${tokenBSymbol}`);
      return null;
    } catch (error) {
      console.error('Error fetching pool CIDs:', error);
      return null;
    }
  }

  /**
   * Execute atomic swap (PrepareSwap + ExecuteSwap in 1 transaction)
   * This is the recommended endpoint for production use
   */
  async executeAtomicSwap(params: SwapParams): Promise<AtomicSwapResponse> {
    const idempotencyKey = this.generateIdempotencyKey();

    // Use debug endpoint when auth is disabled (no real JWT)
    if (!this.hasJwt()) {
      const party = this.currentParty();
      const resolvedPoolId = params.poolId || `${params.inputSymbol}-${params.outputSymbol}`;
      // Always ask server for a fresh, party-visible CID
      const { poolCid } = await this.resolveAndGrant(resolvedPoolId, party);

      // Snapshot balances before swap for accurate delta measurement
      const beforeTokens = await this.getTokens(party);
      const beforeMap = new Map<string, number>(beforeTokens.map(t => [t.symbol, t.balance || 0]));

      // Respect caller-provided minOutput (slippage) when available
      const minOutputStr = params.minOutput ?? '0';
      const body = {
        poolCid,
        poolId: resolvedPoolId,
        inputSymbol: params.inputSymbol,
        outputSymbol: params.outputSymbol,
        amountIn: params.inputAmount,
        minOutput: minOutputStr,
      };
      const res = await this.request<any>(() =>
        this.client.post('/api/clearportx/debug/swap-by-cid', body, { headers: { 'X-Party': party } })
      );

      // Poll for ACS propagation and compute balance delta for output symbol
      const outSym = (params.outputSymbol || '').toUpperCase();
      const outBefore = beforeMap.get(outSym) ?? beforeMap.get(params.outputSymbol) ?? 0;
      let received = 0;
      for (let i = 0; i < 8; i++) { // ~ up to ~4.8s (8 * 600ms)
        await this.sleep(600);
        const afterTokens = await this.getTokens(party);
        const afterMap = new Map<string, number>(afterTokens.map(t => [t.symbol.toUpperCase(), t.balance || 0]));
        const outAfter = afterMap.get(outSym) ?? 0;
        const delta = outAfter - outBefore;
        if (delta > 0) {
          received = delta;
          break;
        }
      }

      return {
        receiptCid: res?.receiptCid ?? '',
        trader: getPartyId() || '',
        inputSymbol: params.inputSymbol,
        outputSymbol: params.outputSymbol,
        amountIn: params.inputAmount,
        amountOut: received.toString(),
        timestamp: new Date().toISOString(),
      };
    }

    const res = await this.client.post('/api/swap/atomic', params, {
      headers: { 'X-Idempotency-Key': idempotencyKey },
    });
    return res.data;
  }

  /**
   * Get pool CID for a given pool ID
   * Fetches pool CIDs from the backend and finds matching pool by ID
   */
  private async getPoolCidById(poolId: string): Promise<string | null> {
    try {
      // Get the party ID
      const party = getPartyId() || DEVNET_PARTY;

      // Fetch all pools visible to this party
      const res = await this.client.get('/api/clearportx/debug/party-acs', {
        params: { template: 'AMM.Pool.Pool' },
        headers: { 'X-Party': party }
      });

      const poolCids = res.data?.cids || [];
      console.log(`Found ${poolCids.length} pool CIDs for party, looking for poolId: ${poolId}`);

      // For each pool CID, fetch details to find matching pool ID
      for (const cid of poolCids) {
        try {
          const poolRes = await this.client.get('/api/clearportx/debug/pool-by-cid', {
            params: { cid },
            headers: { 'X-Party': party }
          });

          const poolData = poolRes.data;
          // Check if pool ID matches (partial match since frontend may have simplified IDs)
          if (poolData.success &&
              (poolData.poolId === poolId ||
               poolData.poolId.includes(poolId) ||
               poolId.includes(poolData.poolId))) {
            console.log(`Found matching pool by ID: ${poolData.poolId} with CID: ${cid}`);
            return cid;
          }
        } catch (error) {
          console.error(`Error fetching pool details for CID ${cid}:`, error);
        }
      }

      // If not found by ID, try by symbols extracted from the pool ID
      // Pool IDs often have format like "ETH-USDC-01" or "p0-gv-eth-usdc-225946"
      const symbols = poolId.toUpperCase().match(/([A-Z]+)-([A-Z]+)/);
      if (symbols && symbols.length >= 3) {
        const symbolA = symbols[1];
        const symbolB = symbols[2];
        console.log(`Trying to find pool by symbols: ${symbolA}/${symbolB}`);
        return this.getPoolCidBySymbols(symbolA, symbolB);
      }

      console.warn(`No pool found for poolId: ${poolId}`);
      return null;
    } catch (error) {
      console.error('Error fetching pool CID by ID:', error);
      return null;
    }
  }

  /**
   * Add liquidity to a pool
   */
  async addLiquidity(params: AddLiquidityParams): Promise<{ lpTokenCid: string; lpAmount: string }> {
    // Use debug endpoint when auth is disabled (no real JWT)
    if (!this.hasJwt()) {
      const party = this.currentParty();
      // Get a fresh, party-visible CID from server
      const { poolCid } = await this.resolveAndGrant(params.poolId, party);

      const byCid = {
        poolCid,
        poolId: params.poolId,
        amountA: params.amountA.toString(),
        amountB: params.amountB.toString(),
        minLPTokens: params.minLPTokens.toString(),
      };

      console.log('Adding liquidity (by-cid) with:', byCid);

      // CID-first endpoint with single paced retry inside request()
      const res = await this.request<any>(() => this.client.post('/api/clearportx/debug/add-liquidity-by-cid', byCid));
      return {
        lpTokenCid: res?.lpTokenCid ?? '',
        lpAmount: res?.lpAmount ?? params.minLPTokens
      };
    }

    const res = await this.client.post('/api/liquidity/add', params);
    return res.data;
  }

  /**
   * Add liquidity by explicit pool CID (CID-first path)
   */
  async addLiquidityByCid(params: {
    poolCid: string;
    poolId?: string;
    amountA: string;
    amountB: string;
    minLPTokens: string;
  }): Promise<{ lpTokenCid: string; lpAmount: string }> {
    const party = this.currentParty();
    const poolId = params.poolId || '';
    const visibleCid = await this.ensurePoolCidVisible(params.poolCid, poolId, party);
    const body = {
      poolCid: visibleCid,
      poolId,
      amountA: params.amountA,
      amountB: params.amountB,
      minLPTokens: params.minLPTokens,
    };
    const res = await this.request<any>(() => this.client.post('/api/clearportx/debug/add-liquidity-by-cid', body));
    return {
      lpTokenCid: res?.lpTokenCid ?? '',
      lpAmount: res?.lpAmount ?? params.minLPTokens
    };
  }
  /**
   * Remove liquidity from a pool
   */
  async removeLiquidity(params: RemoveLiquidityParams): Promise<{
    tokenACid: string;
    tokenBCid: string;
    amountA: string;
    amountB: string
  }> {
    const res = await this.client.post('/api/liquidity/remove', params);
    return res.data;
  }

  /**
   * Calculate swap quote (off-chain estimation)
   * Use this to show estimated output before executing swap
   */
  async calculateSwapQuote(params: {
    poolId: string;
    inputSymbol: string;
    outputSymbol: string;
    inputAmount: string;
  }): Promise<SwapQuote> {
    // Bind the quote to the exact resolver-selected pool CID to avoid mismatch
    const party = this.currentParty();
    const resolvedPoolId = params.poolId || `${params.inputSymbol}-${params.outputSymbol}`;
    const { poolCid } = await this.resolveAndGrant(resolvedPoolId, party);
    const poolMeta = await this.request<any>(() =>
      this.client.get('/api/clearportx/debug/pool-by-cid', {
        params: { cid: poolCid }, headers: { 'X-Party': party }
      })
    );
    if (!poolMeta?.success) {
      throw new Error('Pool not visible for party');
    }
    const symbolA: string = poolMeta.symbolA;
    const symbolB: string = poolMeta.symbolB;
    const reserveA = parseFloat(poolMeta.reserveA);
    const reserveB = parseFloat(poolMeta.reserveB);
    const isAtoB = (params.inputSymbol === symbolA && params.outputSymbol === symbolB);
    const isBtoA = (params.inputSymbol === symbolB && params.outputSymbol === symbolA);
    if (!isAtoB && !isBtoA) {
      throw new Error('Symbol mismatch for selected pool');
    }
    const inputAmount = parseFloat(params.inputAmount);
    // Choose reserves based on trade direction (input -> output)
    const reserveIn = isAtoB ? reserveA : reserveB;
    const reserveOut = isAtoB ? reserveB : reserveA;
    const feeRate = 0.003; // 0.30%
    const feeBps = feeRate * 10000;
    const feeAmount = (inputAmount * feeBps) / 10000;
    const inputAfterFee = inputAmount - feeAmount;
    const outputAmount = (inputAfterFee * reserveOut) / (reserveIn + inputAfterFee);
    const priceBefore = reserveOut / reserveIn;
    const priceAfter = (reserveOut - outputAmount) / (reserveIn + inputAmount);
    const priceImpact = Math.abs((priceAfter - priceBefore) / priceBefore) * 100;
    return {
      inputAmount,
      outputAmount,
      priceImpact,
      fee: feeAmount,
      route: [params.inputSymbol, params.outputSymbol],
      slippage: 0.5,
    };
  }

  // Helper: Generate idempotency key for swap
  private generateIdempotencyKey(): string {
    return `swap-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }

  // Helper: Map backend pool DTO to frontend PoolInfo
  private mapPool(data: any): PoolInfo {
    // Use new tokenA/tokenB objects if available, fallback to deprecated symbolA/symbolB
    const tokenA = data.tokenA || { symbol: data.symbolA, name: data.symbolA, decimals: 10 };
    const tokenB = data.tokenB || { symbol: data.symbolB, name: data.symbolB, decimals: 10 };

    return {
      contractId: data.poolId || '',
      tokenA: {
        symbol: tokenA.symbol,
        name: tokenA.name,
        decimals: tokenA.decimals,
        balance: 0,
        contractId: '',
        logoUrl: this.getTokenLogo(tokenA.symbol),
      },
      tokenB: {
        symbol: tokenB.symbol,
        name: tokenB.name,
        decimals: tokenB.decimals,
        balance: 0,
        contractId: '',
        logoUrl: this.getTokenLogo(tokenB.symbol),
      },
      reserveA: parseFloat(data.reserveA),
      reserveB: parseFloat(data.reserveB),
      totalLiquidity: parseFloat(data.totalLPSupply || 0),
      feeRate: data.feeRate || 0.003,
      apr: 0, // TODO: Calculate from volume/liquidity metrics
      volume24h: parseFloat(data.volume24h || 0), // Real 24h volume from backend
    };
  }

  // Map party-scoped row to PoolInfo (row contains poolId, poolCid, symbolA/B, reserveA/B)
  private mapPartyPool(row: any): PoolInfo {
    const tokenA = { symbol: row.symbolA, name: row.symbolA, decimals: 10 };
    const tokenB = { symbol: row.symbolB, name: row.symbolB, decimals: 10 };
    return {
      // contractId keeps poolId for routing consistency in UI
      contractId: row.poolId || '',
      tokenA: {
        symbol: tokenA.symbol,
        name: tokenA.name,
        decimals: tokenA.decimals,
        balance: 0,
        contractId: '',
        logoUrl: this.getTokenLogo(tokenA.symbol),
      },
      tokenB: {
        symbol: tokenB.symbol,
        name: tokenB.name,
        decimals: tokenB.decimals,
        balance: 0,
        contractId: '',
        logoUrl: this.getTokenLogo(tokenB.symbol),
      },
      reserveA: parseFloat(row.reserveA || '0'),
      reserveB: parseFloat(row.reserveB || '0'),
      totalLiquidity: 0, // not provided; optional
      feeRate: 0.003,
      apr: 0,
      volume24h: 0,
    };
  }
  // Helper: Map backend token DTO to frontend TokenInfo
  private mapToken(data: any): TokenInfo {
    return {
      symbol: data.symbol,
      name: data.name || data.symbol,
      decimals: data.decimals || 10,
      balance: parseFloat(data.amount || data.quantity || '0'),
      contractId: data.contractId || '',
      logoUrl: this.getTokenLogo(data.symbol),
    };
  }

  // Helper: Get token logo URL
  private getTokenLogo(symbol: string): string {
    const logos: Record<string, string> = {
      ETH: 'https://cryptologos.cc/logos/ethereum-eth-logo.png',
      USDC: 'https://cryptologos.cc/logos/usd-coin-usdc-logo.png',
      BTC: 'https://cryptologos.cc/logos/bitcoin-btc-logo.png',
      USDT: 'https://cryptologos.cc/logos/tether-usdt-logo.png',
    };
    return logos[symbol] || '';
  }
}

// Export singleton instance
export const backendApi = new BackendApiService();
