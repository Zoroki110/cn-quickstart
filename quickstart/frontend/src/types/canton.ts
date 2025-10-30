// Types pour l'intégration avec Canton Network et les contrats AMM

export interface CantonConfig {
  ledgerApiUrl: string;
  adminApiUrl: string;
  participantId: string;
}

export interface Party {
  id: string;
  displayName: string;
}

export interface Amount {
  quantity: number;
  currency: string;
}

export interface Token {
  contractId: string;
  issuer: Party;
  owner: Party;
  amount: Amount;
}

export interface LiquidityPool {
  contractId: string;
  poolOperator: Party;
  tokenA: string;
  tokenB: string;
  reserveA: number;
  reserveB: number;
  totalLPSupply: number;
  feeRate: number;
  liquidityProviders: Party[];
}

export interface LPToken {
  contractId: string;
  pool: string;
  owner: Party;
  amount: number;
  poolTokenA: string;
  poolTokenB: string;
}

export interface SwapRequest {
  contractId: string;
  trader: Party;
  pool: string;
  inputToken: string;
  inputAmount: number;
  minOutputAmount: number;
  deadline: string;
}

// API Request/Response Types
export interface CreateContractRequest {
  templateId: {
    packageId: string;
    moduleName: string;
    entityName: string;
  };
  payload: Record<string, any>;
}

export interface ExerciseChoiceRequest {
  templateId: {
    packageId: string;
    moduleName: string;
    entityName: string;
  };
  contractId: string;
  choice: string;
  argument: Record<string, any>;
}

export interface QueryContractsRequest {
  templateIds: Array<{
    packageId: string;
    moduleName: string;
    entityName: string;
  }>;
}

export interface ContractResponse<T = any> {
  contractId: string;
  templateId: {
    packageId: string;
    moduleName: string;
    entityName: string;
  };
  payload: T;
  signatories: string[];
  observers: string[];
  createdAt: string;
}

export interface CommandResult {
  commandId: string;
  transactionId: string;
  events: ContractEvent[];
}

export interface ContractEvent {
  type: 'created' | 'archived';
  contractId: string;
  templateId: {
    packageId: string;
    moduleName: string;
    entityName: string;
  };
  payload?: any;
}

// Swap Interface Types (pour l'UI)
export interface TokenInfo {
  symbol: string;
  name: string;
  decimals: number;
  logoUrl?: string;
  balance?: number;
  contractId?: string;
}

export interface SwapQuote {
  inputAmount: number;
  outputAmount: number;
  priceImpact: number;
  fee: number;
  route: string[];
  slippage: number;
}

export interface SwapTransaction {
  id: string;
  status: 'pending' | 'confirmed' | 'failed';
  inputToken: TokenInfo;
  outputToken: TokenInfo;
  inputAmount: number;
  outputAmount: number;
  timestamp: number;
  transactionHash?: string;
  error?: string;
}

// Pool Interface Types
export interface PoolInfo {
  contractId: string;
  tokenA: TokenInfo;
  tokenB: TokenInfo;
  reserveA: number;
  reserveB: number;
  totalLiquidity: number;
  feeRate: number;
  apr: number;
  volume24h: number;
  userLiquidity?: number;
  userShare?: number;
}

export interface LiquidityPosition {
  poolId: string;
  tokenA: TokenInfo;
  tokenB: TokenInfo;
  lpTokens: number;
  shareOfPool: number;
  value: number;
}

// Error Types
export interface CantonError {
  code: string;
  message: string;
  details?: any;
}

// WebSocket Types
export interface ContractUpdate {
  type: 'created' | 'archived' | 'updated';
  contractId: string;
  templateId: {
    packageId: string;
    moduleName: string;
    entityName: string;
  };
  payload?: any;
  timestamp: number;
}

