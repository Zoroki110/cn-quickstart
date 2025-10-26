#!/bin/bash

echo "🔧 FIXING FRONTEND API CONFIGURATION"
echo "===================================="
echo ""

# Create a service that uses the backend API instead of direct Canton API
cat > /root/canton-website/app/src/services/cantonService.ts << 'EOF'
// Canton Service - Uses ClearportX Backend API
import { backendApi } from './backendApi';
import { TokenInfo, PoolInfo } from '../types/canton';

class CantonService {
  private initialized = false;

  initialize(config: any) {
    this.initialized = true;
    console.log('Canton Service initialized with backend API');
    return this;
  }

  async isConnected(): Promise<boolean> {
    try {
      const health = await backendApi.healthCheck();
      return health.status === 'UP';
    } catch {
      return false;
    }
  }

  async getHealth(): Promise<boolean> {
    return this.isConnected();
  }

  async queryContracts(templateType: string): Promise<any[]> {
    try {
      if (templateType.includes('Pool')) {
        const pools = await backendApi.getPools();
        return pools.map(pool => ({
          contractId: pool.contractId || `pool-${pool.tokenA.symbol}-${pool.tokenB.symbol}`,
          payload: pool
        }));
      }
      return [];
    } catch (error) {
      console.error('Query contracts failed:', error);
      return [];
    }
  }

  async getTokens(party?: string): Promise<TokenInfo[]> {
    try {
      if (!party) {
        // Get tokens for current user
        const currentUser = localStorage.getItem('current_user') || 'alice@clearportx';
        return await backendApi.getTokens(currentUser);
      }
      return await backendApi.getTokens(party);
    } catch (error) {
      console.error('Get tokens failed:', error);
      return [];
    }
  }

  async getPools(): Promise<PoolInfo[]> {
    try {
      return await backendApi.getPools();
    } catch (error) {
      console.error('Get pools failed:', error);
      return [];
    }
  }
}

// Export singleton instance
export const cantonService = new CantonService();

// For compatibility with existing code
export const initializeCantonApi = (config: any) => {
  return cantonService.initialize(config);
};

export const cantonApi = {
  isConnected: () => cantonService.isConnected(),
  getHealth: () => cantonService.getHealth(),
  queryContracts: (templateType: string) => cantonService.queryContracts(templateType),
  getTokens: (party?: string) => cantonService.getTokens(party),
  getPools: () => cantonService.getPools(),
};

export default cantonService;
EOF

echo "✅ Created cantonService.ts"

# Update the index.ts to export the new service
cat > /root/canton-website/app/src/services/index.ts << 'EOF'
export * from './cantonService';
export * from './cantonAuth';
export * from './backendApi';
export * from './auth';
// Keep old exports for compatibility but they won't be used
export * from './realCantonApi';
export * from './mockCantonApi';
EOF

echo "✅ Updated service exports"

# Ensure REACT_APP_USE_MOCK is set to true to avoid realCantonApi
cat > /root/canton-website/app/.env.local << 'EOF'
REACT_APP_USE_MOCK=true
REACT_APP_BACKEND_API_URL=https://nonexplicable-lacily-leesa.ngrok-free.dev
REACT_APP_CANTON_API_URL=https://nonexplicable-lacily-leesa.ngrok-free.dev
EOF

echo "✅ Created .env.local with backend URLs"

echo ""
echo "📋 NEXT STEPS:"
echo "1. Build and deploy the frontend locally to test:"
echo "   cd /root/canton-website"
echo "   npm run build"
echo "   npm run start"
echo ""
echo "2. Or commit and push to GitHub for Netlify:"
echo "   cd /root/canton-website"
echo "   git add -A"
echo "   git commit -m 'Fix API calls to use backend endpoints'"
echo "   git push"
echo ""
echo "3. Make sure Netlify has these environment variables:"
echo "   REACT_APP_BACKEND_API_URL=https://nonexplicable-lacily-leesa.ngrok-free.dev"
echo "   REACT_APP_USE_MOCK=true"
