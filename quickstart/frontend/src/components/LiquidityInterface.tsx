import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAppStore } from '../stores';
import { backendApi } from '../services/backendApi';
import { TokenInfo, PoolInfo, LpTokenInfo } from '../types/canton';
import toast from 'react-hot-toast';
import { useWalletAuth } from '../wallet';
import TokenSelector from './TokenSelector';

const LiquidityInterface: React.FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { pools, setPools } = useAppStore();
  const { partyId } = useWalletAuth();

  const [mode, setMode] = useState<'add' | 'remove'>('add');
  const [tokens, setTokens] = useState<TokenInfo[]>([]);
  const [lpTokens, setLpTokens] = useState<LpTokenInfo[]>([]);
  const [selectedTokenA, setSelectedTokenA] = useState<TokenInfo | null>(null);
  const [selectedTokenB, setSelectedTokenB] = useState<TokenInfo | null>(null);
  const [showTokenASelector, setShowTokenASelector] = useState(false);
  const [showTokenBSelector, setShowTokenBSelector] = useState(false);
  const [amountA, setAmountA] = useState('');
  const [amountB, setAmountB] = useState('');
  const [loading, setLoading] = useState(false);
  const [calculating, setCalculating] = useState(false);
  const [rawPools, setRawPools] = useState<PoolInfo[]>([]);
  const LP_BASELINES_STORAGE_KEY = 'clearportx:lp-baselines:v1';
  const [lpBaselines, setLpBaselines] = useState<Record<string, number>>(() => {
    if (typeof window === 'undefined') {
      return {};
    }
    try {
      const stored = window.localStorage.getItem(LP_BASELINES_STORAGE_KEY);
      return stored ? JSON.parse(stored) : {};
    } catch {
      return {};
    }
  });
  const lpBaselinesRef = useRef(lpBaselines);
  useEffect(() => {
    lpBaselinesRef.current = lpBaselines;
  }, [lpBaselines]);
  const baselinesInitializedRef = useRef(Object.keys(lpBaselinesRef.current).length > 0);

  const persistLpBaselines = useCallback((next: Record<string, number>) => {
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(LP_BASELINES_STORAGE_KEY, JSON.stringify(next));
    }
  }, []);

  const computePoolTotals = useCallback((positions: LpTokenInfo[]) => {
    const totals = new Map<string, number>();
    positions.forEach(pos => {
      if (!pos.poolId) {
        return;
      }
      totals.set(pos.poolId, (totals.get(pos.poolId) ?? 0) + (pos.amount || 0));
    });
    return totals;
  }, []);

  const seedBaselinesIfNeeded = useCallback((party: string, positions: LpTokenInfo[]) => {
    if (baselinesInitializedRef.current) {
      return lpBaselinesRef.current;
    }
    const totals = computePoolTotals(positions);
    const snapshot = { ...lpBaselinesRef.current };
    let changed = false;
    totals.forEach((sum, poolId) => {
      const key = `${party}::${poolId}`;
      if (snapshot[key] === undefined) {
        snapshot[key] = sum;
        changed = true;
      }
    });
    if (changed) {
      persistLpBaselines(snapshot);
      lpBaselinesRef.current = snapshot;
      setLpBaselines(snapshot);
    }
    baselinesInitializedRef.current = true;
    return snapshot;
  }, [computePoolTotals, persistLpBaselines]);

  const augmentPoolsWithUserLiquidity = useCallback((
    poolList: PoolInfo[],
    lpPositions: LpTokenInfo[],
    baselineMap: Record<string, number>,
    actingParty: string
  ) => {
    return poolList.map(pool => {
      const normalizedPoolId = pool.poolId || pool.contractId;
      const poolPositions = lpPositions.filter(position => position.poolId === normalizedPoolId);
      const totalHeld = poolPositions.reduce((sum, position) => sum + (position.amount || 0), 0);
      const baselineKey = `${actingParty}::${normalizedPoolId}`;
      const baseline = baselineMap[baselineKey] ?? 0;
      const userLiquidity = Math.max(0, totalHeld - baseline);
      const userShare = userLiquidity > 0 && pool.totalLiquidity > 0
        ? (userLiquidity / pool.totalLiquidity) * 100
        : 0;
      return {
        ...pool,
        poolId: normalizedPoolId,
        userLiquidity,
        userShare,
      };
    });
  }, []);

  const handleResetLiquidityView = useCallback(() => {
    if (!partyId) {
      toast.error('Connect a wallet to resync liquidity view');
      return;
    }
    const totals = computePoolTotals(lpTokens);
    const snapshot = { ...lpBaselinesRef.current };
    let changed = false;
    totals.forEach((sum, poolId) => {
      const key = `${partyId}::${poolId}`;
      if (snapshot[key] !== sum) {
        snapshot[key] = sum;
        changed = true;
      }
    });
    if (changed) {
      persistLpBaselines(snapshot);
      lpBaselinesRef.current = snapshot;
      setLpBaselines(snapshot);
      toast.success('Liquidity view resynced with ledger balances');
    } else {
      toast.success('Liquidity view already matches ledger balances');
    }
  }, [partyId, computePoolTotals, lpTokens, persistLpBaselines]);

  const renderTokenBadge = (
    token: TokenInfo | null,
    fallbackColor: string,
    fallbackTextColor: string = 'text-gray-900 dark:text-gray-100'
  ) => (
    <div
      className={`w-8 h-8 rounded-full overflow-hidden border border-white/40 shadow-inner flex items-center justify-center ${
        token?.logoUrl ? 'bg-white dark:bg-dark-700' : fallbackColor
      }`}
    >
      {token?.logoUrl ? (
        <img
          src={token.logoUrl}
          alt={`${token.symbol} logo`}
          className="w-full h-full object-cover"
          loading="lazy"
        />
      ) : (
        <span className={`text-xs font-bold uppercase ${fallbackTextColor}`}>
          {token?.symbol?.charAt(0) ?? '?'}
        </span>
      )}
    </div>
  );

  const tokenBOptions = useMemo(
    () => tokens.filter(token => token.symbol !== selectedTokenA?.symbol),
    [tokens, selectedTokenA]
  );

  // Charger les tokens depuis les pools actifs + balances utilisateur
  useEffect(() => {
    const loadTokens = async () => {
      try {
        const poolsData = await backendApi.getPools();

        const uniqueTokensMap = new Map<string, TokenInfo>();
        poolsData.forEach(pool => {
          if (!uniqueTokensMap.has(pool.tokenA.symbol)) {
            uniqueTokensMap.set(pool.tokenA.symbol, { ...pool.tokenA, balance: 0 });
          }
          if (!uniqueTokensMap.has(pool.tokenB.symbol)) {
            uniqueTokensMap.set(pool.tokenB.symbol, { ...pool.tokenB, balance: 0 });
          }
        });

        let tokensWithBalances = Array.from(uniqueTokensMap.values()).map(token => ({ ...token, balance: 0 }));
        let userLpTokens: LpTokenInfo[] = [];

        if (partyId) {
          try {
            const [userTokens, lpPositions] = await Promise.all([
              backendApi.getWalletTokens(partyId),
              backendApi.getLpTokens(partyId),
            ]);
            userLpTokens = lpPositions;
            tokensWithBalances = tokensWithBalances.map(token => {
              const userToken = userTokens.find(t => t.symbol === token.symbol);
              return {
                ...token,
                balance: userToken?.balance || 0,
              };
            });
          } catch (walletError) {
            console.warn('Failed to load wallet state for party', walletError);
          }
        }

        setTokens(tokensWithBalances);
        setLpTokens(userLpTokens);
        setRawPools(poolsData);
        setSelectedTokenA(prev => (prev ? tokensWithBalances.find(t => t.symbol === prev.symbol) || prev : null));
        setSelectedTokenB(prev => (prev ? tokensWithBalances.find(t => t.symbol === prev.symbol) || prev : null));

        if (partyId) {
          const baselineSnapshot = seedBaselinesIfNeeded(partyId, userLpTokens);
          const enhancedPools = augmentPoolsWithUserLiquidity(poolsData, userLpTokens, baselineSnapshot, partyId);
          setPools(enhancedPools);
        } else {
          setPools(poolsData.map(pool => ({ ...pool, userLiquidity: 0, userShare: 0 })));
        }

        const pool = location.state?.pool as PoolInfo | undefined;
        if (pool) {
          const presetA = tokensWithBalances.find(t => t.symbol === pool.tokenA.symbol) || { ...pool.tokenA, balance: 0 };
          const presetB = tokensWithBalances.find(t => t.symbol === pool.tokenB.symbol) || { ...pool.tokenB, balance: 0 };
          setSelectedTokenA(presetA);
          setSelectedTokenB(presetB);
        }

        console.log('✅ Loaded tokens from pools for liquidity:', tokensWithBalances);
      } catch (error) {
        console.error('Error loading tokens:', error);
        toast.error('Failed to load tokens');
      }
    };

    loadTokens();
  }, [partyId, location.state, setPools, augmentPoolsWithUserLiquidity, seedBaselinesIfNeeded]);

  const refreshWalletState = useCallback(async (): Promise<{ walletTokens: TokenInfo[]; lpPositions: LpTokenInfo[] }> => {
    if (!partyId) {
      return { walletTokens: [], lpPositions: [] };
    }
    try {
      const [walletTokens, lpPositions] = await Promise.all([
        backendApi.getWalletTokens(partyId),
        backendApi.getLpTokens(partyId),
      ]);
      setTokens(currentTokens => {
        const updated = currentTokens.map(token => {
          const match = walletTokens.find(t => t.symbol === token.symbol);
          return { ...token, balance: match?.balance || 0 };
        });
        setSelectedTokenA(prev => (prev ? updated.find(t => t.symbol === prev.symbol) || prev : null));
        setSelectedTokenB(prev => (prev ? updated.find(t => t.symbol === prev.symbol) || prev : null));
        return updated;
      });
      setLpTokens(lpPositions);
      return { walletTokens, lpPositions };
    } catch (error) {
      console.error('Failed to refresh wallet balances:', error);
      return { walletTokens: [], lpPositions: [] };
    }
  }, [partyId]);

  useEffect(() => {
    if (!rawPools.length) {
      return;
    }
    if (!partyId) {
      setPools(rawPools.map(pool => ({ ...pool, userLiquidity: 0, userShare: 0 })));
      return;
    }
    const baselineSnapshot = lpBaselinesRef.current;
    const enhancedPools = augmentPoolsWithUserLiquidity(rawPools, lpTokens, baselineSnapshot, partyId);
    setPools(enhancedPools);
  }, [rawPools, lpTokens, lpBaselines, augmentPoolsWithUserLiquidity, setPools, partyId]);

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

    if (!partyId) {
      toast.error('Connect a wallet to add liquidity');
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

      const pool = pools.find(p =>
        (p.tokenA.symbol === selectedTokenA.symbol && p.tokenB.symbol === selectedTokenB.symbol) ||
        (p.tokenA.symbol === selectedTokenB.symbol && p.tokenB.symbol === selectedTokenA.symbol)
      );

      if (!pool) {
        toast.error('Pool not found for this token pair');
        return;
      }

      const targetPoolId = pool.poolId || pool.contractId;
      if (!targetPoolId) {
        toast.error('Pool identifier missing; refresh pools and try again.');
        return;
      }

      const poolCid = await backendApi.resolvePoolCid(targetPoolId);
      if (!poolCid) {
        toast.error('Unable to resolve pool CID. Refresh pools and try again.');
        return;
      }

      const response = await backendApi.addLiquidityByCid({
        poolCid,
        poolId: targetPoolId,
        amountA: amountANum.toFixed(10),
        amountB: amountBNum.toFixed(10),
        minLPTokens: '0.0000000001',
      });

      const toastAmount = (() => {
        const raw = typeof response?.lpAmount === 'string' ? response.lpAmount.trim() : '';
        if (raw) {
          const numeric = Number(raw);
          if (Number.isFinite(numeric) && numeric > 0) {
            if (numeric >= 1) {
              return numeric.toLocaleString('en-US', { maximumFractionDigits: 4 });
            }
            return numeric.toPrecision(4);
          }
          return raw;
        }
        return estimatedLPTokens.toFixed(4);
      })();
      toast.success(`Liquidity added! LP tokens: ${toastAmount}`);

      const updatedPools = await backendApi.getPools();
      setRawPools(updatedPools);
      await refreshWalletState();

      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('clearportx:transactions:refresh', {
          detail: { source: 'liquidity:add', historyEntryId: response?.historyEntryId }
        }));
      }

      setAmountA('');
      setAmountB('');

      setTimeout(() => {
        navigate('/pools');
      }, 1500);
    } catch (error: any) {
      console.error('Error adding liquidity:', error);
      console.error('Error response:', error.response?.data);

      const backendMessage = error.response?.data?.message;
      const backendDetails = error.response?.data?.details;
      const detailSuffix = backendDetails ? ` (${backendDetails})` : '';
      const composedMessage = backendMessage ? `${backendMessage}${detailSuffix}` : null;

      if (composedMessage) {
        toast.error(`Failed: ${composedMessage}`);
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

  const existingPool = useMemo(() => (
    pools.find(p =>
      (p.tokenA.symbol === selectedTokenA?.symbol && p.tokenB.symbol === selectedTokenB?.symbol) ||
      (p.tokenA.symbol === selectedTokenB?.symbol && p.tokenB.symbol === selectedTokenA?.symbol)
    ) || null
  ), [pools, selectedTokenA, selectedTokenB]);

  const estimatedLPTokens = useMemo(() => {
    const valueA = parseFloat(amountA);
    const valueB = parseFloat(amountB);
    if (!Number.isFinite(valueA) || !Number.isFinite(valueB) || valueA <= 0 || valueB <= 0) {
      return 0;
    }
    if (!existingPool || existingPool.totalLiquidity <= 0 || existingPool.reserveA <= 0 || existingPool.reserveB <= 0) {
      return Math.sqrt(valueA * valueB);
    }
    const shareA = (valueA * existingPool.totalLiquidity) / existingPool.reserveA;
    const shareB = (valueB * existingPool.totalLiquidity) / existingPool.reserveB;
    return Math.min(shareA, shareB);
  }, [amountA, amountB, existingPool]);

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
                <button
                  onClick={() => setShowTokenASelector(true)}
                  className="flex items-center space-x-2 px-3 py-2 rounded-xl bg-gray-100 dark:bg-dark-800 hover:bg-gray-200 dark:hover:bg-dark-700 transition-colors duration-200 min-w-fit whitespace-nowrap"
                >
                  {selectedTokenA ? (
                    <>
                      {renderTokenBadge(selectedTokenA, 'bg-primary-100 dark:bg-primary-900/20', 'text-primary-600 dark:text-primary-400')}
                      <span className="font-semibold text-gray-900 dark:text-gray-100">
                        {selectedTokenA.symbol}
                      </span>
                    </>
                  ) : (
                    <span className="text-gray-500">Select token</span>
                  )}
                </button>

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
                <button
                  onClick={() => setShowTokenBSelector(true)}
                  className="flex items-center space-x-2 px-3 py-2 rounded-xl bg-gray-100 dark:bg-dark-800 hover:bg-gray-200 dark:hover:bg-dark-700 transition-colors duration-200 min-w-fit whitespace-nowrap"
                >
                  {selectedTokenB ? (
                    <>
                      {renderTokenBadge(selectedTokenB, 'bg-success-100 dark:bg-success-900/20', 'text-success-600 dark:text-success-400')}
                      <span className="font-semibold text-gray-900 dark:text-gray-100">
                        {selectedTokenB.symbol}
                      </span>
                    </>
                  ) : (
                    <span className="text-gray-500">Select token</span>
                  )}
                </button>

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
              disabled={loading || !partyId || !selectedTokenA || !selectedTokenB || !amountA || !amountB}
              className={`w-full py-4 rounded-xl font-semibold text-lg transition-all duration-200 ${
                loading || !partyId || !selectedTokenA || !selectedTokenB || !amountA || !amountB
                  ? 'bg-gray-200 dark:bg-dark-800 text-gray-400 dark:text-gray-600 cursor-not-allowed'
                  : 'btn-primary'
              }`}
            >
              {loading ? (
                <span className="flex items-center justify-center">
                  <div className="spinner w-5 h-5 mr-2"></div>
                  Adding Liquidity...
                </span>
              ) : !partyId ? (
                'Connect Wallet'
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
        <div className="flex items-center justify-between mb-4">
          <h2 className="heading-3">Your Liquidity Positions</h2>
          <button
            type="button"
            onClick={handleResetLiquidityView}
            className="text-sm font-semibold text-primary-600 hover:text-primary-700 dark:text-primary-400 dark:hover:text-primary-300 transition-colors"
          >
            Re-sync view
          </button>
        </div>

        {pools.filter(p => p.userLiquidity && p.userLiquidity > 0).length === 0 ? (
          <div className="text-center py-8">
          <div className="text-4xl mb-3"></div>
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

      <TokenSelector
        isOpen={showTokenASelector}
        onClose={() => setShowTokenASelector(false)}
        tokens={tokens}
        selectedToken={selectedTokenA}
        type="from"
        onSelect={(token) => {
          setSelectedTokenA(token);
          if (token.symbol === selectedTokenB?.symbol) {
            setSelectedTokenB(null);
          }
          setShowTokenASelector(false);
        }}
      />

      <TokenSelector
        isOpen={showTokenBSelector}
        onClose={() => setShowTokenBSelector(false)}
        tokens={tokenBOptions}
        selectedToken={selectedTokenB}
        type="to"
        onSelect={(token) => {
          setSelectedTokenB(token);
          setShowTokenBSelector(false);
        }}
      />
    </div>
  );
};

export default LiquidityInterface;