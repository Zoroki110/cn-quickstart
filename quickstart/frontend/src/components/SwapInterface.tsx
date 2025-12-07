import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useAppStore } from '../stores';
import { useContractStore } from '../stores/useContractStore';
import { backendApi } from '../services/backendApi';
import { TokenInfo, SwapQuote, PoolInfo } from '../types/canton';
import TokenSelector from './TokenSelector';
import SlippageSettings from './SlippageSettings';
import toast from 'react-hot-toast';
import { useWalletAuth } from '../wallet';
import { useLegacyBalances } from '../hooks';
import { calculateSwapQuoteFromPool } from '../utils/poolMath';

const SwapInterface: React.FC = () => {
  const queryClient = useQueryClient();
  const { selectedTokens, setSelectedTokens, swapTokens: swapSelectedTokens, slippage } = useAppStore();
  const { partyId } = useWalletAuth();
  const { balances: legacyBalances, reload: reloadLegacyBalances } = useLegacyBalances(partyId || null);
  const [inputAmount, setInputAmount] = useState('');
  const [outputAmount, setOutputAmount] = useState('');
  const [tokens, setTokens] = useState<TokenInfo[]>([]);
  const [quote, setQuote] = useState<SwapQuote | null>(null);
  const [loading, setLoading] = useState(false);
  const [calculating, setCalculating] = useState(false);
  const [showFromSelector, setShowFromSelector] = useState(false);
  const [showToSelector, setShowToSelector] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [pools, setPools] = useState<PoolInfo[]>([]);
  const [volume24h, setVolume24h] = useState<number>(0);
  const lastConnectedParty = useRef<string | null>(null);

  const resolvedPoolId = useMemo(() => {
    if (!selectedTokens.from || !selectedTokens.to || pools.length === 0) return null;
    const fromSymbol = selectedTokens.from.symbol.toUpperCase();
    const toSymbol = selectedTokens.to.symbol.toUpperCase();
    const match = pools.find(pool => {
      const symbolA = pool.tokenA.symbol.toUpperCase();
      const symbolB = pool.tokenB.symbol.toUpperCase();
      return (
        (symbolA === fromSymbol && symbolB === toSymbol) ||
        (symbolA === toSymbol && symbolB === fromSymbol)
      );
    });
    return match?.contractId ?? null;
  }, [pools, selectedTokens.from, selectedTokens.to]);

  const activePool = useMemo(() => {
    if (!resolvedPoolId) {
      return null;
    }
    return pools.find(pool => pool.contractId === resolvedPoolId || pool.poolId === resolvedPoolId) ?? null;
  }, [pools, resolvedPoolId]);

  const formatCurrency = (value: number) => {
    if (!isFinite(value) || value <= 0) return '$0';
    if (value >= 1_000_000) return `$${(value / 1_000_000).toFixed(1)}M`;
    if (value >= 1_000) return `$${(value / 1_000).toFixed(1)}K`;
    return `$${value.toFixed(0)}`;
  };

  const getBalanceEntry = useCallback((symbol?: string) => {
    if (!symbol) {
      return undefined;
    }
    return legacyBalances[symbol.toUpperCase()];
  }, [legacyBalances]);

  const formatBalanceDisplay = useCallback((symbol?: string) => {
    const entry = getBalanceEntry(symbol);
    if (!entry) {
      return '0.0000';
    }
    const numeric = Number(entry.amount);
    if (!Number.isFinite(numeric)) {
      return entry.amount;
    }
    return numeric.toLocaleString('en-US', { maximumFractionDigits: 4 });
  }, [getBalanceEntry]);

  const getNumericBalance = useCallback((symbol?: string) => {
    const entry = getBalanceEntry(symbol);
    if (!entry) {
      return 0;
    }
    const numeric = Number(entry.amount);
    return Number.isFinite(numeric) ? numeric : 0;
  }, [getBalanceEntry]);

  const parsedInputAmount = parseFloat(inputAmount || '0');
  const normalizedInputAmount = Number.isFinite(parsedInputAmount) ? parsedInputAmount : 0;
  const insufficientBalance = Boolean(partyId && selectedTokens.from && normalizedInputAmount > getNumericBalance(selectedTokens.from.symbol));
  const disableSwap = loading || !partyId || !selectedTokens.from || !selectedTokens.to || !inputAmount || !quote || insufficientBalance;

  useEffect(() => {
    const previous = lastConnectedParty.current;
    if (partyId && partyId !== previous) {
      reloadLegacyBalances();
    }
    if (!partyId) {
      lastConnectedParty.current = null;
      return;
    }
    lastConnectedParty.current = partyId;
  }, [partyId, reloadLegacyBalances]);

  // Charger les tokens depuis les pools actifs + balances utilisateur
  useEffect(() => {
    const loadTokens = async () => {
      try {
        const pools = await backendApi.getPools();
        setPools(pools);

        const uniqueTokensMap = new Map<string, TokenInfo>();
        pools.forEach(pool => {
          if (!uniqueTokensMap.has(pool.tokenA.symbol)) {
            uniqueTokensMap.set(pool.tokenA.symbol, { ...pool.tokenA, balance: 0 });
          }
          if (!uniqueTokensMap.has(pool.tokenB.symbol)) {
            uniqueTokensMap.set(pool.tokenB.symbol, { ...pool.tokenB, balance: 0 });
          }
        });

        let tokensWithBalances = Array.from(uniqueTokensMap.values()).map(token => ({ ...token, balance: 0 }));

        if (partyId) {
          try {
            const userTokens = await backendApi.getWalletTokens(partyId);
            tokensWithBalances = tokensWithBalances.map(token => {
              const userToken = userTokens.find(t => t.symbol === token.symbol);
              return {
                ...token,
                balance: userToken?.balance || 0,
              };
            });
          } catch (balanceError) {
            console.warn('Failed to load wallet tokens for party', balanceError);
          }
        }

        setTokens(tokensWithBalances);
        useContractStore.getState().setTokens(tokensWithBalances);
      } catch (error) {
        console.error('Error loading tokens:', error);
        toast.error('Failed to load tokens from backend');
      }
    };

    loadTokens();
  }, [partyId]);

  // Compute 24h volume for selected pair from current pools
  useEffect(() => {
    if (!selectedTokens.from || !selectedTokens.to || pools.length === 0) {
      setVolume24h(0);
      return;
    }
    const a = selectedTokens.from.symbol.toUpperCase();
    const b = selectedTokens.to.symbol.toUpperCase();
    const match = pools.find(p => {
      const pa = p.tokenA.symbol.toUpperCase();
      const pb = p.tokenB.symbol.toUpperCase();
      return (pa === a && pb === b) || (pa === b && pb === a);
    });
    setVolume24h(match?.volume24h || 0);
  }, [selectedTokens.from, selectedTokens.to, pools]);

  // Calculer le quote quand le montant change
  useEffect(() => {
    if (!selectedTokens.from || !selectedTokens.to || !inputAmount || !activePool) {
      setOutputAmount('');
      setQuote(null);
      return;
    }

    const timeout = setTimeout(() => {
      setCalculating(true);
      try {
        const amount = parseFloat(inputAmount);
        if (!Number.isFinite(amount) || amount <= 0) {
          setOutputAmount('');
          setQuote(null);
          return;
        }

        const estimatedQuote = calculateSwapQuoteFromPool(
          activePool,
          selectedTokens.from!.symbol,
          selectedTokens.to!.symbol,
          amount
        );

        if (!estimatedQuote) {
          setOutputAmount('');
          setQuote(null);
          return;
        }

        setQuote(estimatedQuote);
        setOutputAmount(estimatedQuote.outputAmount.toFixed(6));
      } finally {
        setCalculating(false);
      }
    }, 250);

    return () => clearTimeout(timeout);
  }, [inputAmount, selectedTokens.from, selectedTokens.to, activePool]);

  const handleSwap = async () => {
    if (!selectedTokens.from || !selectedTokens.to || !inputAmount || !quote) {
      toast.error('Please fill in all fields');
      return;
    }

    if (!partyId) {
      toast.error('Connect a wallet to swap tokens');
      return;
    }

    const amount = parseFloat(inputAmount);
    if (isNaN(amount) || amount <= 0) {
      toast.error('Please enter a valid amount');
      return;
    }

    const availableBalance = getNumericBalance(selectedTokens.from.symbol);
    if (amount > availableBalance) {
      toast.error('Insufficient balance for this swap');
      return;
    }

    if (!resolvedPoolId) {
      toast.error('No liquidity pool available for this pair');
      return;
    }

    try {
      setLoading(true);
      const minOutput = quote.outputAmount * (1 - slippage / 100);

      const response = await backendApi.executeAtomicSwap({
        poolId: resolvedPoolId,
        inputSymbol: selectedTokens.from.symbol,
        outputSymbol: selectedTokens.to.symbol,
        inputAmount: amount.toFixed(10),
        minOutput: minOutput.toFixed(10),
        maxPriceImpactBps: Math.round(slippage * 100),
      });

      toast.success(`Swap successful! Received ${parseFloat(response.amountOut).toFixed(4)} ${response.outputSymbol}`);

      if (partyId) {
        let updatedTokens = await backendApi.getWalletTokens(partyId);
        for (let i = 0; i < 6; i++) {
          await new Promise(resolve => setTimeout(resolve, 600));
          const next = await backendApi.getWalletTokens(partyId);
          const fromBal = next.find(t => t.symbol === selectedTokens.from?.symbol)?.balance ?? 0;
          const toBal = next.find(t => t.symbol === selectedTokens.to?.symbol)?.balance ?? 0;
          const prevFromBal = updatedTokens.find(t => t.symbol === selectedTokens.from?.symbol)?.balance ?? 0;
          const prevToBal = updatedTokens.find(t => t.symbol === selectedTokens.to?.symbol)?.balance ?? 0;
          if (fromBal !== prevFromBal || toBal !== prevToBal) {
            updatedTokens = next;
            break;
          }
          updatedTokens = next;
        }
        setTokens(updatedTokens);
        useContractStore.getState().setTokens(updatedTokens);

        const updatedFrom = updatedTokens.find(t => t.symbol === selectedTokens.from?.symbol);
        const updatedTo = updatedTokens.find(t => t.symbol === selectedTokens.to?.symbol);
        if (updatedFrom && updatedTo) {
          setSelectedTokens({ from: updatedFrom, to: updatedTo });
        }
      }

      await reloadLegacyBalances();
      queryClient.invalidateQueries({ queryKey: ['tokens'] });
      queryClient.invalidateQueries({ queryKey: ['pools'] });

      setInputAmount('');
      setOutputAmount('');
      setQuote(null);
    } catch (error: any) {
      console.error('Error executing swap:', error);

      const httpStatus: number | undefined =
        error?.httpStatus ?? error?.response?.status;
      const code: string | undefined =
        error?.code ?? error?.response?.data?.error;
      const message: string =
        error?.message ??
        error?.response?.data?.message ??
        error?.response?.data?.error ??
        'Unknown error';

      if (httpStatus === 422 ||
          /slippage|price impact|Min output not met/i.test(message) ||
          code === 'SLIPPAGE_MIN_OUTPUT_NOT_MET' ||
          code === 'PRICE_IMPACT_TOO_HIGH') {
        toast.error(`Slippage/price impact too high. Try increasing tolerance in settings ⚙️`);
      } else if (httpStatus === 429) {
        toast.error('Rate limit exceeded. Please wait and try again.');
      } else if (httpStatus === 401) {
        toast.error('Authentication required. Please connect your wallet.');
      } else if (httpStatus === 400) {
        toast.error('Invalid swap parameters. Try reducing amount or increasing slippage ⚙️');
      } else if (httpStatus === 409) {
        toast.error('Temporary ledger visibility issue. Please try again.');
      } else {
        toast.error(`Swap failed: ${message}`);
      }
    } finally {
      setLoading(false);
    }
  };

  const handleTokenSwap = () => {
    swapSelectedTokens();
    setInputAmount('');
    setOutputAmount('');
    setQuote(null);
  };

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

  return (
    <div className="min-h-[calc(100vh-220px)] flex items-center justify-center px-4 py-10">
      <div className="relative w-full max-w-md mx-auto">
        <div className="card-glow bg-white dark:bg-dark-900 relative overflow-hidden">
        {/* Background Pattern */}
        <div className="absolute inset-0 bg-mesh opacity-30"></div>
        
        {/* Header */}
        <div className="relative flex items-center justify-between mb-6">
          <div>
            <h2 className="heading-3">Swap Tokens</h2>
            <p className="body-small">Trade tokens instantly with low fees</p>
          </div>
          <button
            onClick={() => setShowSettings(!showSettings)}
            className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-dark-800 transition-colors"
            title="Transaction Settings"
          >
            <svg
              className="w-5 h-5 text-gray-600 dark:text-gray-400"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
              />
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
              />
            </svg>
          </button>
        </div>

        {/* Settings Panel */}
        {showSettings && (
          <div className="relative mb-4">
            <SlippageSettings onClose={() => setShowSettings(false)} />
          </div>
        )}

        {/* From Token */}
        <div className="relative mb-2">
          <div className="glass-subtle rounded-2xl p-4">
            <div className="flex items-center justify-between mb-3">
              <span className="body-small">From</span>
              <span className="body-small">
                Balance: {formatBalanceDisplay(selectedTokens.from?.symbol)}
              </span>
            </div>

            <div className="flex items-center space-x-3">
              <button
                onClick={() => setShowFromSelector(true)}
                className="flex items-center space-x-2 px-3 py-2 rounded-xl bg-gray-100 dark:bg-dark-800 hover:bg-gray-200 dark:hover:bg-dark-700 transition-colors duration-200 min-w-fit whitespace-nowrap"
              >
                {selectedTokens.from ? (
                  <>
                    {renderTokenBadge(selectedTokens.from, 'bg-primary-100 dark:bg-primary-900/20', 'text-primary-600 dark:text-primary-400')}
                    <span className="font-semibold text-gray-900 dark:text-gray-100">
                      {selectedTokens.from.symbol}
                    </span>
                  </>
                ) : (
                  <span className="text-gray-500">Select token</span>
                )}
              </button>

              <input
                type="text"
                value={inputAmount}
                onChange={(e) => setInputAmount(e.target.value)}
                placeholder="0.0"
                className="flex-1 max-w-[220px] text-right text-2xl font-semibold bg-transparent border-none outline-none text-gray-900 dark:text-gray-100 placeholder-gray-400"
              />
            </div>
          </div>
        </div>

        {/* Swap Button */}
        <div className="flex justify-center -my-1 relative z-10">
          <button
            onClick={handleTokenSwap}
            className="p-2 rounded-xl bg-white dark:bg-dark-900 border-4 border-gray-50 dark:border-dark-950 shadow-lg hover:shadow-glow transition-all duration-200 hover:rotate-180 transform hover:scale-110"
          >
            <svg
              className="w-5 h-5 text-gray-600 dark:text-gray-400"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4"
              />
            </svg>
          </button>
        </div>

        {/* To Token */}
        <div className="relative mb-6">
          <div className="glass-subtle rounded-2xl p-4">
            <div className="flex items-center justify-between mb-3">
              <span className="body-small">To</span>
              <span className="body-small">
                Balance: {formatBalanceDisplay(selectedTokens.to?.symbol)}
              </span>
            </div>

            <div className="flex items-center space-x-3">
              <button
                onClick={() => setShowToSelector(true)}
                className="flex items-center space-x-2 px-3 py-2 rounded-xl bg-gray-100 dark:bg-dark-800 hover:bg-gray-200 dark:hover:bg-dark-700 transition-colors duration-200 min-w-fit whitespace-nowrap"
              >
                {selectedTokens.to ? (
                  <>
                    {renderTokenBadge(selectedTokens.to, 'bg-success-100 dark:bg-success-900/20', 'text-success-600 dark:text-success-400')}
                    <span className="font-semibold text-gray-900 dark:text-gray-100">
                      {selectedTokens.to.symbol}
                    </span>
                  </>
                ) : (
                  <span className="text-gray-500">Select token</span>
                )}
              </button>

              <div className="flex-1 max-w-[220px] text-right text-2xl font-semibold text-gray-900 dark:text-gray-100">
                {calculating ? '...' : outputAmount || '0.0'}
              </div>
            </div>
          </div>
        </div>

        {/* Quote Details */}
        {quote && (
          <div className="mb-6 glass-subtle rounded-xl p-4 space-y-2">
            <div className="flex justify-between body-small">
              <span className="text-gray-600 dark:text-gray-400">Rate:</span>
              <span className="font-semibold text-gray-900 dark:text-gray-100">
                1 {selectedTokens.from?.symbol} = {(quote.outputAmount / quote.inputAmount).toFixed(4)} {selectedTokens.to?.symbol}
              </span>
            </div>
            <div className="flex justify-between body-small">
              <span className="text-gray-600 dark:text-gray-400">Price Impact:</span>
              <span className={`font-semibold ${quote.priceImpact > 5 ? 'text-error-600' : 'text-success-600'}`}>
                {quote.priceImpact.toFixed(2)}%
              </span>
            </div>
            <div className="flex justify-between body-small">
              <span className="text-gray-600 dark:text-gray-400">Fee:</span>
              <span className="font-semibold text-gray-900 dark:text-gray-100">
                {quote.fee.toFixed(6)} {selectedTokens.from?.symbol}
              </span>
            </div>
            <div className="flex justify-between body-small">
              <span className="text-gray-600 dark:text-gray-400">Slippage Tolerance:</span>
              <span className="font-semibold text-gray-900 dark:text-gray-100">
                {slippage}%
              </span>
            </div>
          </div>
        )}

        {/* Swap Button */}
        <button
          onClick={handleSwap}
          disabled={disableSwap}
          className={`w-full py-4 rounded-xl font-semibold text-lg transition-all duration-200 flex items-center justify-center space-x-2 ${
            disableSwap
              ? 'bg-gray-200 dark:bg-dark-800 text-gray-400 dark:text-gray-600 cursor-not-allowed'
              : 'btn-primary'
          }`}
        >
          {loading ? (
            <>
              <div className="spinner w-5 h-5"></div>
              <span>Swapping...</span>
            </>
          ) : !partyId ? (
            'Connect Wallet'
          ) : insufficientBalance ? (
            'Insufficient Balance'
          ) : (
            'Swap Tokens'
          )}
        </button>

        {/* Quick Stats */}
        <div className="mt-6 grid grid-cols-3 gap-4">
          <div className="text-center">
            <div className="text-success-500 mb-1">
              <span className="body-small font-medium">24h Volume</span>
            </div>
            <span className="text-sm font-semibold text-gray-900 dark:text-gray-100">
              {formatCurrency(volume24h)}
            </span>
          </div>

          <div className="text-center">
            <div className="text-primary-500 mb-1">
              <span className="body-small font-medium">Avg Time</span>
            </div>
            <span className="text-sm font-semibold text-gray-900 dark:text-gray-100">~2s</span>
          </div>

          <div className="text-center">
            <div className="text-warning-500 mb-1">
              <span className="body-small font-medium">Fee</span>
            </div>
            <span className="text-sm font-semibold text-gray-900 dark:text-gray-100">0.3%</span>
          </div>
        </div>
      </div>

      {/* Token Selectors */}
      <TokenSelector
        isOpen={showFromSelector}
        onClose={() => setShowFromSelector(false)}
        balances={legacyBalances}
        onSelect={(token) => {
          setSelectedTokens({ from: token });
          setShowFromSelector(false);
        }}
        tokens={tokens}
        selectedToken={selectedTokens.from}
        type="from"
      />

      <TokenSelector
        isOpen={showToSelector}
        onClose={() => setShowToSelector(false)}
        balances={legacyBalances}
        onSelect={(token) => {
          setSelectedTokens({ to: token });
          setShowToSelector(false);
        }}
        tokens={tokens}
        selectedToken={selectedTokens.to}
        type="to"
      />
      </div>
    </div>
  );
};

export default SwapInterface;