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
      const party = getPartyId();

      // Public endpoints that don't need JWT authentication
      const publicEndpoints = ['/api/pools', '/api/health', '/api/tokens/', '/api/debug/'];
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

    // Retry on 429 (rate limit)
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
   * Execute atomic swap (PrepareSwap + ExecuteSwap in 1 transaction)
   * This is the recommended endpoint for production use
   */
  async executeAtomicSwap(params: SwapParams): Promise<AtomicSwapResponse> {
    const idempotencyKey = this.generateIdempotencyKey();

    // Use debug endpoint when auth is disabled (no real JWT)
    if (!this.hasJwt()) {
      const debugBody = {
        poolId: params.poolId,
        inputSymbol: params.inputSymbol,
        outputSymbol: params.outputSymbol,
        amountIn: params.inputAmount,
        minOutput: params.minOutput,
      } as any;
      const res = await this.client.post('/api/debug/swap-debug', debugBody);
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
   * Add liquidity to a pool
   */
  async addLiquidity(params: AddLiquidityParams): Promise<{ lpTokenCid: string; lpAmount: string }> {
    // Use debug endpoint when auth is disabled (no real JWT)
    if (!this.hasJwt()) {
      const debugBody = {
        poolId: params.poolId,
        amountA: Number(params.amountA),
        amountB: Number(params.amountB),
        minLPTokens: Number(params.minLPTokens),
      };
      const res = await this.client.post('/api/debug/add-liquidity', debugBody);
      return { lpTokenCid: res.data?.lpTokenCid ?? '', lpAmount: params.minLPTokens };
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
