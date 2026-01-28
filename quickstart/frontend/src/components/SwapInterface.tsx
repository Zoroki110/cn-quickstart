import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useAppStore } from '../stores';
import { useContractStore } from '../stores/useContractStore';
import { backendApi } from '../services/backendApi';
import { TokenInfo, SwapQuote, PoolInfo } from '../types/canton';
import TokenSelector from './TokenSelector';
import SlippageSettings from './SlippageSettings';
import toast from 'react-hot-toast';
import { useWalletAuth, walletManager } from '../wallet';
import { useLoopBalances, useUtxoBalances } from '../hooks';
import { submitTx } from '../loop/submitTx';
import { getLoopProvider } from '../loop/loopProvider';
import { calculateSwapQuoteFromPool } from '../utils/poolMath';

const AMULET_ADMIN = 'DSO::1220be58c29e65de40bf273be1dc2b266d43a9a002ea5b18955aeef7aac881bb471a';
const AMULET_ID = 'Amulet';
const CBTC_ADMIN = 'cbtc-network::12202a83c6f4082217c175e29bc53da5f2703ba2675778ab99217a5a881a949203ff';
const CBTC_ID = 'CBTC';
const OPERATOR_PARTY =
  process.env.REACT_APP_OPERATOR_PARTY ||
  process.env.REACT_APP_OPERATOR_PARTY_ID ||
  'ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37';

const SwapInterface: React.FC = () => {
  const { selectedTokens, setSelectedTokens, swapTokens: swapSelectedTokens, slippage } = useAppStore();
  const { partyId, walletType } = useWalletAuth();
  const partyForBackend = walletType === 'loop' ? null : partyId || null;
  const { balances: loopBalances, reload: reloadLoopBalances } = useLoopBalances(partyId || null, walletType, {
    refreshIntervalMs: 15000,
  });
  const { balances: utxoBalances, reload: reloadUtxoBalances } = useUtxoBalances(partyForBackend, {
    ownerOnly: true,
    refreshIntervalMs: 15000,
  });
  const reloadBalances = useCallback(async () => {
    if (walletType === 'loop') {
      await reloadLoopBalances();
      return;
    }
    await reloadUtxoBalances();
  }, [walletType, reloadLoopBalances, reloadUtxoBalances]);
  const [inputAmount, setInputAmount] = useState('');
  const [outputAmount, setOutputAmount] = useState('');
  const [tokens, setTokens] = useState<TokenInfo[]>([]);
  const [quote, setQuote] = useState<SwapQuote | null>(null);
  const [loading, setLoading] = useState(false);
  const [calculating, setCalculating] = useState(false);
  const [showFromSelector, setShowFromSelector] = useState(false);
  const [showToSelector, setShowToSelector] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [swapResult, setSwapResult] = useState<any>(null);
  const [swapStatus, setSwapStatus] = useState<string | null>(null);
  const [devnetConsumeAvailable, setDevnetConsumeAvailable] = useState(false);
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

  const balancesBySymbol = useMemo(() => {
    if (walletType === 'loop') {
      const map: Record<string, { amount: string; decimals: number }> = {};
      Object.entries(loopBalances).forEach(([symbol, entry]) => {
        map[symbol.toUpperCase()] = { amount: entry.amount, decimals: entry.decimals };
      });
      return map;
    }
    const map: Record<string, { amount: string; decimals: number }> = {};
    Object.values(utxoBalances).forEach((entry) => {
      const symbol = instrumentIdToSymbol(entry.instrumentId);
      const existing = map[symbol];
      map[symbol] = {
        amount: existing ? decimalAddStrings(existing.amount, entry.amount) : entry.amount,
        decimals: existing?.decimals ?? entry.decimals,
      };
    });
    return map;
  }, [walletType, loopBalances, utxoBalances]);

  const getBalanceEntry = useCallback((symbol?: string) => {
    if (!symbol) {
      return undefined;
    }
    return balancesBySymbol[symbol.toUpperCase()];
  }, [balancesBySymbol]);

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
  const insufficientBalance = Boolean(
    partyId && selectedTokens.from && normalizedInputAmount > getNumericBalance(selectedTokens.from.symbol)
  );
  const disableSwap = loading || !partyId || !selectedTokens.from || !selectedTokens.to || !inputAmount || !quote || insufficientBalance;
  const consumePayload = swapResult?.consume?.result;
  const payoutStatus = consumePayload?.payoutStatus;
  const payoutCid = consumePayload?.payoutCid;
  const payoutMessage =
    payoutStatus === 'COMPLETED'
      ? 'Payout completed on ledger (no Loop accept required).'
      : payoutStatus === 'CREATED'
      ? 'Payout TransferInstruction created. Accept in Loop wallet.'
      : null;

  useEffect(() => {
    const previous = lastConnectedParty.current;
    if (partyId && partyId !== previous) {
      reloadBalances();
    }
    if (!partyId) {
      lastConnectedParty.current = null;
      return;
    }
    lastConnectedParty.current = partyId;
  }, [partyId, reloadBalances]);

  useEffect(() => {
    if (typeof window === 'undefined') {
      return;
    }
    const handleWalletConnected = () => {
      reloadBalances();
    };
    window.addEventListener('clearportx:wallet:connected', handleWalletConnected as EventListener);
    return () => {
      window.removeEventListener('clearportx:wallet:connected', handleWalletConnected as EventListener);
    };
  }, [reloadBalances]);

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
        const tokensList = Array.from(uniqueTokensMap.values()).map(token => ({ ...token, balance: 0 }));
        setTokens(tokensList);
        useContractStore.getState().setTokens(tokensList);
      } catch (error) {
        console.error('Error loading tokens:', error);
        toast.error('Failed to load tokens from backend');
      }
    };

    loadTokens();
  }, []);

  useEffect(() => {
    let cancelled = false;
    const probeDevnetSwap = async () => {
      const res = await backendApi.inspectDevnetSwap('ping');
      if (cancelled) return;
      setDevnetConsumeAvailable(isDevnetSwapApiResponse(res));
    };
    probeDevnetSwap();
    return () => {
      cancelled = true;
    };
  }, []);

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

    if (walletType && walletType !== 'loop') {
      toast.error('Loop wallet required to submit swaps');
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
      setSwapResult(null);
      setSwapStatus(null);

      const instrument = resolveInstrumentForSymbol(selectedTokens.from.symbol);
      if (!instrument) {
        toast.error('Unsupported token for swap');
        return;
      }

      const minOutput = quote.outputAmount * (1 - slippage / 100);
      const minOutputStr = minOutput.toFixed(10);
      const poolCid = activePool?.contractId || resolvedPoolId;
      const direction = resolveSwapDirection(activePool, selectedTokens.from.symbol, selectedTokens.to.symbol);
      if (!direction) {
        toast.error('Token selection does not match active pool');
        return;
      }

      const deadline = new Date(Date.now() + 10 * 60 * 1000).toISOString();
      const requestId = `swap-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      const memoPayload = {
        v: 1,
        requestId,
        poolCid,
        direction,
        minOut: minOutputStr,
        receiverParty: partyId,
        deadline,
      };
      const memo = JSON.stringify(memoPayload);

      const connector = walletManager.getOrCreateLoopConnector();
      const status = await connector.ensureConnected(`swap-${Date.now()}`);
      if (!status.connected || !getLoopProvider()) {
        const message = status.error || 'Loop not connected';
        setSwapResult({ ok: false, error: { code: 'CONNECT', message } });
        toast.error(message);
        return;
      }

      const prepared = await prepareLoopTransfer(getLoopProvider(), {
        recipient: OPERATOR_PARTY,
        amount: amount.toFixed(10),
        instrument,
        memo,
        requestedAt: new Date().toISOString(),
        executeBefore: deadline,
      });

      const commands = extractPreparedCommands(prepared);
      const disclosedContracts = extractPreparedDisclosedContracts(prepared);
      const packagePreference = extractPreparedPackagePreference(prepared);
      const synchronizerId = extractPreparedSynchronizerId(prepared);
      const exerciseContractId = extractExerciseContractId(commands);
      const debugInfo = {
        disclosedContractsCount: disclosedContracts?.length ?? 0,
        contractId: exerciseContractId,
        synchronizerId,
        requestId,
      };
      console.log('[Swap submitTx debug]', debugInfo);
      if (commands.length === 0) {
        setSwapResult({
          ok: false,
          error: { code: 'PREPARE', message: 'Transfer preparation returned no commands', details: prepared },
        });
        toast.error('Transfer preparation failed');
        return;
      }

      const combinedCommands = commands;

      setSwapStatus('Submitting inbound transfer');
      const transferResult = await submitTx({
        commands: combinedCommands,
        actAs: extractPreparedActAs(prepared, partyId),
        readAs: extractPreparedReadAs(prepared, partyId),
        deduplicationKey: requestId,
        memo,
        mode: 'WAIT',
        disclosedContracts,
        packageIdSelectionPreference: packagePreference,
        synchronizerId,
      });
      let consumeResult: any = null;
      if (transferResult.ok && transferResult.value.txStatus === 'SUCCEEDED') {
        const shouldConsume = devnetConsumeAvailable;
        if (shouldConsume) {
          setSwapStatus('Consuming swap (backend)');
          consumeResult = await backendApi.consumeDevnetSwap({ requestId });
          if (consumeResult?.ok) {
            const payoutStatus = consumeResult?.result?.payoutStatus;
            const payoutCid = consumeResult?.result?.payoutCid;
            if (payoutStatus === 'COMPLETED' || !payoutCid) {
              setSwapStatus('Payout completed automatically. No Loop accept required.');
            } else {
              setSwapStatus('Payout created. Accept in Loop wallet.');
            }
            if (typeof window !== 'undefined') {
              window.dispatchEvent(new CustomEvent('clearportx:transactions:refresh', {
                detail: { source: 'swap', requestId },
              }));
            }
          }
        } else {
          consumeResult = {
            ok: false,
            error: { code: 'DEVNET_ONLY', message: 'Swap consume is only enabled in devnet' },
          };
        }
      }

      const resultWithDebug = {
        transfer: transferResult,
        consume: consumeResult,
        debug: debugInfo,
      };
      console.log('[Swap submitTx]', resultWithDebug);
      setSwapResult(resultWithDebug);

      if (transferResult.ok && transferResult.value.txStatus === 'SUCCEEDED') {
        toast.success(`Transfer submitted (update ${transferResult.value.ledgerUpdateId || 'pending'})`);
        setInputAmount('');
        setOutputAmount('');
        setQuote(null);
      } else if (transferResult.ok) {
        toast.error('Swap transfer failed');
      } else {
        toast.error(transferResult.error.message);
      }
    } catch (error: any) {
      console.error('Error executing swap:', error);
      const message: string =
        error?.message ??
        error?.response?.data?.message ??
        error?.response?.data?.error ??
        'Unknown error';
      setSwapResult({ ok: false, error: { code: 'ERROR', message, details: error } });
      toast.error(`Swap failed: ${message}`);
    } finally {
      setLoading(false);
      await reloadBalances();
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
              <span>Submitting...</span>
            </>
          ) : !partyId ? (
            'Connect Wallet'
          ) : insufficientBalance ? (
            'Insufficient Balance'
          ) : (
            'Swap Tokens'
          )}
        </button>

        {(swapStatus || swapResult) && (
          <div className="mt-4 glass-subtle rounded-xl p-4 text-sm">
            {swapStatus && (
              <div className="font-semibold text-gray-900 dark:text-gray-100">{swapStatus}</div>
            )}
            {consumePayload && (
              <div className="mt-2 rounded-lg border border-gray-200 dark:border-dark-700 bg-white/70 dark:bg-dark-900/40 p-3">
                <div className="text-xs font-semibold text-gray-800 dark:text-gray-200">Payout</div>
                <div className="mt-1 text-xs text-gray-700 dark:text-gray-300 space-y-1">
                  <div>Status: {payoutStatus || 'UNKNOWN'}</div>
                  {payoutMessage && <div>{payoutMessage}</div>}
                  <div>Amount out: {consumePayload?.amountOut ?? '-'}</div>
                  <div>Execute before: {consumePayload?.payoutExecuteBefore ?? '-'}</div>
                  <div>Payout CID: {payoutCid || 'none (completed)'}</div>
                </div>
              </div>
            )}
            {swapResult && (
              <pre className="mt-2 text-xs bg-gray-900 text-emerald-200 rounded-lg p-3 overflow-auto">
                {JSON.stringify(swapResult, null, 2)}
              </pre>
            )}
          </div>
        )}

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
        balances={balancesBySymbol}
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
        balances={balancesBySymbol}
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

type PreparedTransfer = Record<string, any>;

type InstrumentRef = {
  instrumentAdmin: string;
  instrumentId: string;
};

type PoolInstruments = {
  instrumentA: InstrumentRef;
  instrumentB: InstrumentRef;
};

function instrumentIdToSymbol(instrumentId?: string): string {
  const raw = instrumentId || '';
  const upper = raw.toUpperCase();
  if (upper === 'AMULET') return 'CC';
  return upper;
}

function resolveInstrumentForSymbol(symbol?: string): InstrumentRef | null {
  if (!symbol) return null;
  const upper = symbol.toUpperCase();
  if (upper === 'CC' || upper === 'AMULET') {
    return { instrumentAdmin: AMULET_ADMIN, instrumentId: AMULET_ID };
  }
  if (upper === 'CBTC') {
    return { instrumentAdmin: CBTC_ADMIN, instrumentId: CBTC_ID };
  }
  return null;
}

function resolvePoolInstruments(pool: PoolInfo | null): PoolInstruments | null {
  if (!pool) return null;
  const instA = resolveInstrumentForSymbol(pool.tokenA?.symbol);
  const instB = resolveInstrumentForSymbol(pool.tokenB?.symbol);
  if (!instA || !instB) return null;
  return { instrumentA: instA, instrumentB: instB };
}

function isDevnetSwapApiResponse(value: any, expectedRequestId = 'ping'): boolean {
  return Boolean(
    value &&
    typeof value === 'object' &&
    value.requestId === expectedRequestId &&
    typeof value.ok === 'boolean'
  );
}

function resolveSwapDirection(
  pool: PoolInfo | null,
  fromSymbol: string,
  toSymbol: string
): 'A2B' | 'B2A' | null {
  if (!pool) return null;
  const a = pool.tokenA.symbol.toUpperCase();
  const b = pool.tokenB.symbol.toUpperCase();
  const from = fromSymbol.toUpperCase();
  const to = toSymbol.toUpperCase();
  if (from === a && to === b) return 'A2B';
  if (from === b && to === a) return 'B2A';
  return null;
}

async function prepareLoopTransfer(
  provider: any,
  params: {
    recipient: string;
    amount: string;
    instrument: InstrumentRef;
    memo?: string;
    requestedAt?: string;
    executeBefore?: string;
  }
): Promise<PreparedTransfer> {
  if (!provider) {
    throw new Error('Loop provider is not connected');
  }
  const authToken = provider.auth_token ?? provider.authToken;
  const connection = provider.connection ?? provider?.sdk?.connection;
  const prepareTransfer = connection?.prepareTransfer;
  if (!authToken || typeof prepareTransfer !== 'function') {
    throw new Error('Loop provider does not expose transfer preparation');
  }
  const payload = {
    recipient: params.recipient,
    amount: params.amount,
    instrument: {
      instrument_admin: params.instrument.instrumentAdmin,
      instrument_id: params.instrument.instrumentId,
    },
    requested_at: params.requestedAt ?? new Date().toISOString(),
    execute_before: params.executeBefore,
    memo: params.memo,
  };
  return prepareTransfer.call(connection, authToken, payload);
}

function extractPreparedCommands(prepared: PreparedTransfer): any[] {
  const commands =
    prepared?.commands ??
    prepared?.payload?.commands ??
    prepared?.commandPayload?.commands ??
    prepared?.command_payload?.commands ??
    [];
  return Array.isArray(commands) ? commands : [];
}

function extractPreparedTransferInstructionCid(prepared: PreparedTransfer): string | undefined {
  return (
    prepared?.transferInstructionCid ??
    prepared?.transfer_instruction_cid ??
    prepared?.transferInstructionId ??
    prepared?.transfer_instruction_id ??
    prepared?.payload?.transferInstructionCid ??
    prepared?.payload?.transfer_instruction_cid ??
    prepared?.payload?.transferInstructionId ??
    prepared?.payload?.transfer_instruction_id
  );
}

function extractExerciseContractId(commands: any[]): string | undefined {
  for (const command of commands) {
    const exercise =
      command?.ExerciseCommand ??
      command?.exerciseCommand ??
      command?.exercise_command;
    const contractId = exercise?.contractId ?? exercise?.contract_id;
    if (contractId) {
      return contractId;
    }
  }
  return undefined;
}

function extractPreparedDisclosedContracts(prepared: PreparedTransfer): any[] | undefined {
  const disclosed =
    prepared?.disclosedContracts ??
    prepared?.disclosed_contracts ??
    prepared?.extraArgs?.disclosedContracts ??
    prepared?.extra_args?.disclosed_contracts ??
    [];
  return Array.isArray(disclosed) && disclosed.length > 0 ? disclosed : undefined;
}

function extractPreparedPackagePreference(prepared: PreparedTransfer): string[] | undefined {
  const pref =
    prepared?.packageIdSelectionPreference ??
    prepared?.package_id_selection_preference ??
    prepared?.packageIdSelection ??
    prepared?.package_id_selection ??
    undefined;
  return Array.isArray(pref) && pref.length > 0 ? pref : undefined;
}

function extractPreparedSynchronizerId(prepared: PreparedTransfer): string | undefined {
  return prepared?.synchronizerId ?? prepared?.synchronizer_id ?? undefined;
}

function extractPreparedActAs(prepared: PreparedTransfer, fallback: string): string | string[] {
  const actAs = prepared?.actAs ?? prepared?.act_as ?? prepared?.actAsParties ?? prepared?.act_as_parties;
  return actAs || fallback;
}

function extractPreparedReadAs(prepared: PreparedTransfer, fallback: string): string | string[] | undefined {
  const readAs = prepared?.readAs ?? prepared?.read_as ?? prepared?.readAsParties ?? prepared?.read_as_parties;
  return readAs || fallback;
}

function decimalAddStrings(a: string, b: string): string {
  const sa = a ?? '0';
  const sb = b ?? '0';
  const [ia, fa = ''] = sa.split('.');
  const [ib, fb = ''] = sb.split('.');
  const fracLen = Math.max(fa.length, fb.length);
  const normFa = (fa + '0'.repeat(fracLen)).slice(0, fracLen);
  const normFb = (fb + '0'.repeat(fracLen)).slice(0, fracLen);
  const intA = BigInt(ia || '0');
  const intB = BigInt(ib || '0');
  const fracA = BigInt(normFa || '0');
  const fracB = BigInt(normFb || '0');
  const fracSum = fracA + fracB;
  const baseStr = '1' + '0'.repeat(fracLen || 0);
  const base = BigInt(baseStr);
  const carry = fracSum / base;
  const fracRes = fracSum % base;
  const intRes = intA + intB + carry;
  const fracStr = fracLen > 0 ? fracRes.toString().padStart(fracLen, '0').replace(/0+$/, '') : '';
  return fracStr.length > 0 ? `${intRes.toString()}.${fracStr}` : intRes.toString();
}

export default SwapInterface;