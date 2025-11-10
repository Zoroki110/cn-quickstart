// ClearportX Backend API Service
// Connects to Spring Boot backend at http://localhost:8080
import axios, { AxiosInstance, AxiosError } from 'axios';
import { TokenInfo, PoolInfo, SwapQuote } from '../types/canton';
import { getAccessToken, getPartyId } from './auth';
import { BUILD_INFO } from '../config/build-info';

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

    // Retry on 429 (rate limit) and 409 (stale CID) with small backoff
    this.client.interceptors.response.use(
      (response) => response,
      async (error: AxiosError) => {
        if (error.response?.status === 429) {
          const retryAfter = parseInt(error.response.headers['retry-after'] || '3');
          console.log(`⚠️  Rate limited, retrying after ${retryAfter}s...`);
          await new Promise(resolve => setTimeout(resolve, retryAfter * 1000));
          // Retry the original request
          return this.client.request(error.config!);
        }
        if (error.response?.status === 409) {
          const cfg: any = error.config || {};
          cfg.__retry409Count = (cfg.__retry409Count || 0) + 1;
          if (cfg.__retry409Count <= 2) {
            console.log(`⚠️  Stale CID (409), retrying attempt ${cfg.__retry409Count} after 250ms...`);
            await new Promise(resolve => setTimeout(resolve, 250));
            return this.client.request(cfg);
          }
          console.warn('⚠️  Stale CID (409) persisted after retries; surfacing error.');
        }
        throw error;
      }
    );
  }

  private hasJwt(): boolean {
    const token = getAccessToken();
    return !!token && token !== 'devnet-mock-token';
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
   * Get all active pools with liquidity
   * Filters to show only top 3 unique pools by TVL (Total Value Locked)
   */
  async getPools(): Promise<PoolInfo[]> {
    try {
      const res = await this.client.get('/api/pools');
      
      // Ensure res.data is an array
      const poolData = Array.isArray(res.data) ? res.data : [];
      
      const allPools = poolData.map((data: any) => this.mapPool(data));

    // Filter pools with significant liquidity (reserveA > 0 and reserveB > 0)
    const activePools = allPools.filter((pool: PoolInfo) =>
      pool.reserveA > 0 && pool.reserveB > 0
    );

    // Remove duplicates based on token pair (keep first occurrence of each pair)
    const uniquePools: PoolInfo[] = [];
    const seenPairs = new Set<string>();

    for (const pool of activePools) {
      // Create a normalized pair key (alphabetically sorted)
      const pairKey = [pool.tokenA.symbol, pool.tokenB.symbol].sort().join('/');

      if (!seenPairs.has(pairKey)) {
        seenPairs.add(pairKey);
        uniquePools.push(pool);
      }
    }

    // Sort by TVL (Total Value Locked) - using reserveA * reserveB as proxy
    const sortedPools = uniquePools.sort((a: PoolInfo, b: PoolInfo) => {
      const tvlA = a.reserveA * a.reserveB;
      const tvlB = b.reserveA * b.reserveB;
      return tvlB - tvlA; // Descending order
    });

    // Return all unique pools (sorted by TVL)
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
      // Resolve pool ID from pool CID to align with backend debug flow
      const poolCid = await this.getPoolCidBySymbols(params.inputSymbol, params.outputSymbol);

      if (!poolCid) {
        throw new Error(`No pool found for ${params.inputSymbol}/${params.outputSymbol}`);
      }

      // Fetch pool details to obtain canonical poolId
      const party = getPartyId() || DEVNET_PARTY;
      const poolMeta = await this.client.get('/api/clearportx/debug/pool-by-cid', {
        params: { cid: poolCid },
        headers: { 'X-Party': party }
      }).then(r => r.data).catch(() => ({}));
      const resolvedPoolId = params.poolId || poolMeta?.poolId || `${params.inputSymbol}-${params.outputSymbol}`;

      const debugBody = {
        poolId: resolvedPoolId,
        inputSymbol: params.inputSymbol,
        outputSymbol: params.outputSymbol,
        amountIn: params.inputAmount,
        minOutput: params.minOutput || '0.001',
      };

      console.log('Executing swap (debug flow) with:', debugBody);

      // Prefer robust CID-driven swap endpoint with built-in recovery/diagnostics
      const res = await this.client.post('/api/clearportx/debug/swap-by-cid', {
        poolCid,
        poolId: resolvedPoolId,
        inputSymbol: params.inputSymbol,
        outputSymbol: params.outputSymbol,
        amountIn: params.inputAmount,
        minOutput: params.minOutput || '0.001',
      });
      return {
        receiptCid: res.data?.receiptCid ?? '',
        trader: getPartyId() || '',
        inputSymbol: params.inputSymbol,
        outputSymbol: params.outputSymbol,
        amountIn: params.inputAmount,
        amountOut: res.data?.amountOut ?? '0',
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
      // Get pool CID by pool ID
      const poolCid = await this.getPoolCidById(params.poolId);

      if (!poolCid) {
        // Try to extract symbols from pool data to find the pool
        const pools = await this.getPools();
        const pool = pools.find(p =>
          p.contractId === params.poolId ||
          (p.tokenA.symbol && p.tokenB.symbol &&
           params.poolId.toUpperCase().includes(p.tokenA.symbol) &&
           params.poolId.toUpperCase().includes(p.tokenB.symbol))
        );

        if (pool) {
          const fallbackCid = await this.getPoolCidBySymbols(pool.tokenA.symbol, pool.tokenB.symbol);
          if (!fallbackCid) {
            throw new Error(`Pool ${params.poolId} not found or not visible`);
          }
          const body = {
            poolId: params.poolId,
            amountA: params.amountA.toString(),
            amountB: params.amountB.toString(),
            minLPTokens: params.minLPTokens.toString(),
          };

          console.log('Adding liquidity (for-party) with:', body);
          const res = await this.client.post('/api/debug/add-liquidity-for-party', body);
          return {
            lpTokenCid: res.data?.lpTokenCid ?? '',
            lpAmount: res.data?.lpAmount ?? params.minLPTokens
          };
        }

        throw new Error(`Pool ${params.poolId} not found or not visible`);
      }

      const body = {
        poolId: params.poolId,
        amountA: params.amountA.toString(),
        amountB: params.amountB.toString(),
        minLPTokens: params.minLPTokens.toString(),
      };

      console.log('Adding liquidity (for-party) with:', body);

      // Use the robust party-scoped add-liquidity flow with backoff
      const res = await this.client.post('/api/debug/add-liquidity-for-party', body);
      return {
        lpTokenCid: res.data?.lpTokenCid ?? '',
        lpAmount: res.data?.lpAmount ?? params.minLPTokens
      };
    }

    const res = await this.client.post('/api/liquidity/add', params);
    return res.data;
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
    // For now, we calculate client-side using pool reserves
    // Backend doesn't have a quote endpoint yet
    const pools = await this.getPools();
    const pool = pools.find(p =>
      (p.tokenA.symbol === params.inputSymbol && p.tokenB.symbol === params.outputSymbol) ||
      (p.tokenA.symbol === params.outputSymbol && p.tokenB.symbol === params.inputSymbol)
    );

    if (!pool) {
      throw new Error('Pool not found');
    }

    const inputAmount = parseFloat(params.inputAmount);
    const isAtoB = pool.tokenA.symbol === params.inputSymbol;
    const reserveIn = isAtoB ? pool.reserveA : pool.reserveB;
    const reserveOut = isAtoB ? pool.reserveB : pool.reserveA;

    // Constant product formula: k = x * y
    // outputAmount = (inputAfterFee * reserveOut) / (reserveIn + inputAfterFee)
    const feeBps = pool.feeRate * 10000; // 0.003 → 30 bps
    const feeAmount = (inputAmount * feeBps) / 10000;
    const inputAfterFee = inputAmount - feeAmount;
    const outputAmount = (inputAfterFee * reserveOut) / (reserveIn + inputAfterFee);

    // Price impact
    const priceBefore = reserveOut / reserveIn;
    const priceAfter = (reserveOut - outputAmount) / (reserveIn + inputAmount);
    const priceImpact = Math.abs((priceAfter - priceBefore) / priceBefore) * 100;

    return {
      inputAmount: inputAmount,        // number (parsed above)
      outputAmount: outputAmount,       // number (calculated)
      priceImpact: priceImpact,         // number (percentage)
      fee: feeAmount,                   // number
      route: [params.inputSymbol, params.outputSymbol],  // string[]
      slippage: 0.5,                    // default 0.5%
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
