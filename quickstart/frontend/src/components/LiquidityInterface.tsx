import React, { useState, useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAppStore } from '../stores';
import { backendApi } from '../services/backendApi';
import { TokenInfo, PoolInfo } from '../types/canton';
import toast from 'react-hot-toast';

const LiquidityInterface: React.FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { pools, setPools } = useAppStore();

  const [mode, setMode] = useState<'add' | 'remove'>('add');
  const [tokens, setTokens] = useState<TokenInfo[]>([]);
  const [selectedTokenA, setSelectedTokenA] = useState<TokenInfo | null>(null);
  const [selectedTokenB, setSelectedTokenB] = useState<TokenInfo | null>(null);
  const [amountA, setAmountA] = useState('');
  const [amountB, setAmountB] = useState('');
  const [loading, setLoading] = useState(false);
  const [calculating, setCalculating] = useState(false);

  // Charger les tokens depuis les pools actifs + balances utilisateur
  useEffect(() => {
    const loadTokens = async () => {
      try {
        // 1. Get all active pools to extract available tokens
        const poolsData = await backendApi.getPools();
        setPools(poolsData);

        // 2. Extract unique tokens from pools
        const uniqueTokensMap = new Map<string, TokenInfo>();
        poolsData.forEach(pool => {
          if (!uniqueTokensMap.has(pool.tokenA.symbol)) {
            uniqueTokensMap.set(pool.tokenA.symbol, { ...pool.tokenA, balance: 0 });
          }
          if (!uniqueTokensMap.has(pool.tokenB.symbol)) {
            uniqueTokensMap.set(pool.tokenB.symbol, { ...pool.tokenB, balance: 0 });
          }
        });

        // 3. Get user balances for these tokens
        // TEMPORARY: Use public endpoint (hardcoded 'app-provider')
        // TODO: Get party from authService.getParty() after OAuth login
        const userTokens = await backendApi.getTokens('app-provider');

        // 4. Merge pool tokens with user balances (default to 0 if user has no balance)
        const tokensWithBalances = Array.from(uniqueTokensMap.values()).map(token => {
          const userToken = userTokens.find(t => t.symbol === token.symbol);
          return {
            ...token,
            balance: userToken?.balance || 0, // Show 0 if user has no balance
          };
        });

        setTokens(tokensWithBalances);

        // Si un pool est passé en paramètre, pré-sélectionner les tokens
        const pool = location.state?.pool as PoolInfo | undefined;
        if (pool) {
          setSelectedTokenA(pool.tokenA);
          setSelectedTokenB(pool.tokenB);
        }

        console.log('✅ Loaded tokens from pools for liquidity:', tokensWithBalances);
      } catch (error) {
        console.error('Error loading tokens:', error);
        toast.error('Failed to load tokens');
      }
    };

    loadTokens();
  }, [location.state, setPools]);

  // Calculer automatiquement le montant B en fonction du ratio du pool
  useEffect(() => {
    if (!selectedTokenA || !selectedTokenB || !amountA || mode !== 'add') return;

    const calculateAmountB = () => {
      setCalculating(true);

      // Trouver le pool correspondant
      const pool = pools.find(p =>
        (p.tokenA.symbol === selectedTokenA.symbol && p.tokenB.symbol === selectedTokenB.symbol) ||
        (p.tokenA.symbol === selectedTokenB.symbol && p.tokenB.symbol === selectedTokenA.symbol)
      );

      if (pool) {
        const isTokenAFirst = pool.tokenA.symbol === selectedTokenA.symbol;
        const ratio = isTokenAFirst
          ? pool.reserveB / pool.reserveA
          : pool.reserveA / pool.reserveB;

        const calculatedAmountB = parseFloat(amountA) * ratio;
        setAmountB(calculatedAmountB.toFixed(6));
      } else {
        // Nouveau pool - ratio 1:1 par défaut
        setAmountB(amountA);
      }

      setCalculating(false);
    };

    const timeout = setTimeout(calculateAmountB, 300);
    return () => clearTimeout(timeout);
  }, [amountA, selectedTokenA, selectedTokenB, pools, mode]);

  const handleAddLiquidity = async () => {
    if (!selectedTokenA || !selectedTokenB || !amountA || !amountB) {
      toast.error('Please fill in all fields');
      return;
    }

    const amountANum = parseFloat(amountA);
    const amountBNum = parseFloat(amountB);

    if (isNaN(amountANum) || isNaN(amountBNum) || amountANum <= 0 || amountBNum <= 0) {
      toast.error('Please enter valid amounts');
      return;
    }

    try {
      setLoading(true);

      // Find pool by token pair
      const pool = pools.find(p =>
        (p.tokenA.symbol === selectedTokenA.symbol && p.tokenB.symbol === selectedTokenB.symbol) ||
        (p.tokenA.symbol === selectedTokenB.symbol && p.tokenB.symbol === selectedTokenA.symbol)
      );

      if (!pool) {
        toast.error('Pool not found for this token pair');
        return;
      }

      // Call real backend API
      const response = await backendApi.addLiquidity({
        poolId: pool.contractId,
        amountA: amountANum.toFixed(10),  // 10 decimal precision for DAML
        amountB: amountBNum.toFixed(10),
        minLPTokens: '0.0000000001',  // Allow any amount (no minimum for now)
      });

      toast.success(`Liquidity added! LP tokens: ${parseFloat(response.lpAmount).toFixed(4)}`);

      // Recharger les pools
      const updatedPools = await backendApi.getPools();
      setPools(updatedPools);

      // Reset form
      setAmountA('');
      setAmountB('');

      // Optionnel: rediriger vers la page des pools
      setTimeout(() => {
        navigate('/pools');
      }, 1500);
    } catch (error: any) {
      console.error('Error adding liquidity:', error);
      console.error('Error response:', error.response?.data);

      // Handle backend error responses
      if (error.response?.data?.message) {
        toast.error(`Failed: ${error.response.data.message}`);
      } else if (error.response?.status === 422) {
        toast.error('Invalid amounts. Check your token balances.');
      } else if (error.response?.status === 429) {
        toast.error('Rate limit exceeded. Please wait and try again.');
      } else if (error.response?.status === 401) {
        toast.error('Authentication required. Please connect your wallet.');
      } else if (error.response?.status === 404) {
        toast.error('Pool not found. Please select a valid pool.');
      } else {
        toast.error('An error occurred while adding liquidity');
      }
    } finally {
      setLoading(false);
    }
  };

  const existingPool = pools.find(p =>
    (p.tokenA.symbol === selectedTokenA?.symbol && p.tokenB.symbol === selectedTokenB?.symbol) ||
    (p.tokenA.symbol === selectedTokenB?.symbol && p.tokenB.symbol === selectedTokenA?.symbol)
  );

  const estimatedLPTokens = amountA && amountB
    ? Math.sqrt(parseFloat(amountA) * parseFloat(amountB))
    : 0;

  const poolShare = existingPool && estimatedLPTokens
    ? (estimatedLPTokens / (existingPool.totalLiquidity + estimatedLPTokens)) * 100
    : 100;

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="heading-2">Manage Liquidity</h1>
          <p className="body-small mt-1">Add or remove liquidity from pools</p>
        </div>
        <button
          onClick={() => navigate('/pools')}
          className="btn-secondary px-4 py-2"
        >
          ← Back to Pools
        </button>
      </div>

      {/* Mode Tabs */}
      <div className="glass-strong rounded-2xl p-2 flex space-x-2">
        <button
          onClick={() => setMode('add')}
          className={`flex-1 py-3 px-4 rounded-xl font-semibold transition-all duration-200 ${
            mode === 'add'
              ? 'bg-primary-500 text-white shadow-lg'
              : 'bg-transparent text-gray-600 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-dark-800'
          }`}
        >
          Add Liquidity
        </button>
        <button
          onClick={() => setMode('remove')}
          className={`flex-1 py-3 px-4 rounded-xl font-semibold transition-all duration-200 ${
            mode === 'remove'
              ? 'bg-primary-500 text-white shadow-lg'
              : 'bg-transparent text-gray-600 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-dark-800'
          }`}
        >
          Remove Liquidity
        </button>
      </div>

      {/* Add Liquidity Form */}
      {mode === 'add' && (
        <div className="card-glow bg-white dark:bg-dark-900 relative overflow-hidden">
          <div className="absolute inset-0 bg-mesh opacity-30"></div>

          <div className="relative space-y-4">
            <h2 className="heading-3 mb-4">Add Liquidity</h2>

            {/* Token A */}
            <div className="glass-subtle rounded-2xl p-4">
              <div className="flex items-center justify-between mb-3">
                <span className="body-small">Token A</span>
                <span className="body-small">
                  Balance: {selectedTokenA?.balance?.toFixed(4) || '0.00'}
                </span>
              </div>

              <div className="flex items-center space-x-3">
                <select
                  value={selectedTokenA?.symbol || ''}
                  onChange={(e) => {
                    const token = tokens.find(t => t.symbol === e.target.value);
                    setSelectedTokenA(token || null);
                  }}
                  className="flex-1 bg-gray-100 dark:bg-dark-800 rounded-xl px-4 py-3 text-gray-900 dark:text-gray-100 font-semibold border-none outline-none"
                >
                  <option value="">Select Token</option>
                  {tokens.map(token => (
                    <option key={token.symbol} value={token.symbol}>
                      {token.symbol} - {token.name}
                    </option>
                  ))}
                </select>

                <input
                  type="text"
                  value={amountA}
                  onChange={(e) => setAmountA(e.target.value)}
                  placeholder="0.0"
                  className="w-32 text-right text-xl font-semibold bg-transparent border-none outline-none text-gray-900 dark:text-gray-100 placeholder-gray-400"
                />
              </div>
            </div>

            {/* Plus Icon */}
            <div className="flex justify-center -my-2 relative z-10">
              <div className="p-3 rounded-xl bg-white dark:bg-dark-900 border-4 border-gray-50 dark:border-dark-950 shadow-lg">
                <span className="text-2xl">+</span>
              </div>
            </div>

            {/* Token B */}
            <div className="glass-subtle rounded-2xl p-4">
              <div className="flex items-center justify-between mb-3">
                <span className="body-small">Token B</span>
                <span className="body-small">
                  Balance: {selectedTokenB?.balance?.toFixed(4) || '0.00'}
                </span>
              </div>

              <div className="flex items-center space-x-3">
                <select
                  value={selectedTokenB?.symbol || ''}
                  onChange={(e) => {
                    const token = tokens.find(t => t.symbol === e.target.value);
                    setSelectedTokenB(token || null);
                  }}
                  className="flex-1 bg-gray-100 dark:bg-dark-800 rounded-xl px-4 py-3 text-gray-900 dark:text-gray-100 font-semibold border-none outline-none"
                >
                  <option value="">Select Token</option>
                  {tokens
                    .filter(t => t.symbol !== selectedTokenA?.symbol)
                    .map(token => (
                      <option key={token.symbol} value={token.symbol}>
                        {token.symbol} - {token.name}
                      </option>
                    ))}
                </select>

                <input
                  type="text"
                  value={calculating ? '...' : amountB}
                  onChange={(e) => setAmountB(e.target.value)}
                  placeholder="0.0"
                  disabled={calculating}
                  className="w-32 text-right text-xl font-semibold bg-transparent border-none outline-none text-gray-900 dark:text-gray-100 placeholder-gray-400"
                />
              </div>
            </div>

            {/* Pool Info */}
            {existingPool ? (
              <div className="glass-subtle rounded-xl p-4">
                <h4 className="font-semibold mb-3 text-gray-900 dark:text-gray-100">
                  Pool Information
                </h4>
                <div className="space-y-2 body-small">
                  <div className="flex justify-between">
                    <span className="text-gray-600 dark:text-gray-400">Current Liquidity:</span>
                    <span className="font-semibold text-gray-900 dark:text-gray-100">
                      {existingPool.reserveA.toFixed(2)} {existingPool.tokenA.symbol} / {existingPool.reserveB.toFixed(2)} {existingPool.tokenB.symbol}
                    </span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-600 dark:text-gray-400">Total LP Tokens:</span>
                    <span className="font-semibold text-gray-900 dark:text-gray-100">
                      {existingPool.totalLiquidity.toFixed(4)}
                    </span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-600 dark:text-gray-400">APR:</span>
                    <span className="font-semibold text-success-600 dark:text-success-400">
                      {existingPool.apr.toFixed(2)}%
                    </span>
                  </div>
                </div>
              </div>
            ) : selectedTokenA && selectedTokenB ? (
              <div className="glass-subtle rounded-xl p-4 border-2 border-primary-200 dark:border-primary-800">
                <div className="flex items-center space-x-2 mb-2">
                  <span className="text-xl">✨</span>
                  <h4 className="font-semibold text-gray-900 dark:text-gray-100">
                    Creating New Pool
                  </h4>
                </div>
                <p className="body-small text-gray-600 dark:text-gray-400">
                  You are the first liquidity provider for this pair. The ratio of tokens you add will set the initial price.
                </p>
              </div>
            ) : null}

            {/* Preview */}
            {amountA && amountB && selectedTokenA && selectedTokenB && (
              <div className="glass-subtle rounded-xl p-4 space-y-2">
                <h4 className="font-semibold mb-3 text-gray-900 dark:text-gray-100">
                  You will receive
                </h4>
                <div className="flex items-center justify-between">
                  <span className="body-small text-gray-600 dark:text-gray-400">LP Tokens:</span>
                  <span className="text-lg font-bold text-gradient">
                    {estimatedLPTokens.toFixed(4)}
                  </span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="body-small text-gray-600 dark:text-gray-400">Pool Share:</span>
                  <span className="text-lg font-bold text-primary-600 dark:text-primary-400">
                    {poolShare.toFixed(2)}%
                  </span>
                </div>
              </div>
            )}

            {/* Add Button */}
            <button
              onClick={handleAddLiquidity}
              disabled={loading || !selectedTokenA || !selectedTokenB || !amountA || !amountB}
              className={`w-full py-4 rounded-xl font-semibold text-lg transition-all duration-200 ${
                loading || !selectedTokenA || !selectedTokenB || !amountA || !amountB
                  ? 'bg-gray-200 dark:bg-dark-800 text-gray-400 dark:text-gray-600 cursor-not-allowed'
                  : 'btn-primary'
              }`}
            >
              {loading ? (
                <span className="flex items-center justify-center">
                  <div className="spinner w-5 h-5 mr-2"></div>
                  Adding Liquidity...
                </span>
              ) : (
                'Add Liquidity'
              )}
            </button>
          </div>
        </div>
      )}

      {/* Remove Liquidity Form */}
      {mode === 'remove' && (
        <div className="card text-center py-12">
          <p className="text-gray-500 dark:text-gray-400 mb-2">
            Remove liquidity feature coming soon...
          </p>
          <p className="body-small text-gray-400">
            You'll be able to remove liquidity and claim your tokens + earned fees
          </p>
        </div>
      )}

      {/* Your Positions */}
      <div className="glass-strong rounded-2xl p-6">
        <h2 className="heading-3 mb-4">Your Liquidity Positions</h2>

        {pools.filter(p => p.userLiquidity && p.userLiquidity > 0).length === 0 ? (
          <div className="text-center py-8">
            <div className="text-4xl mb-3">💧</div>
            <p className="text-gray-500 dark:text-gray-400">
              You don't have any liquidity positions yet
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {pools
              .filter(p => p.userLiquidity && p.userLiquidity > 0)
              .map(pool => (
                <div key={pool.contractId} className="glass-subtle rounded-xl p-4">
                  <div className="flex items-center justify-between">
                    <div>
                      <h4 className="font-bold text-gray-900 dark:text-gray-100">
                        {pool.tokenA.symbol}/{pool.tokenB.symbol}
                      </h4>
                      <p className="body-small">
                        {pool.userLiquidity?.toFixed(4)} LP Tokens • {pool.userShare?.toFixed(2)}% of pool
                      </p>
                    </div>
                    <button className="btn-secondary px-4 py-2">
                      Remove
                    </button>
                  </div>
                </div>
              ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default LiquidityInterface;