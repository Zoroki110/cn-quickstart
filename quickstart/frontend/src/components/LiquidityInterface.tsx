import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAppStore } from '../stores';
import { backendApi } from '../services/backendApi';
import { TokenInfo, PoolInfo, LpTokenInfo } from '../types/canton';
import toast from 'react-hot-toast';
import { useWalletAuth, walletManager } from '../wallet';
import { useLoopBalances, useUtxoBalances } from '../hooks';
import TokenSelector from './TokenSelector';
import { submitTx } from '../loop/submitTx';
import { getLoopProvider } from '../loop/loopProvider';

const AMULET_ADMIN = 'DSO::1220be58c29e65de40bf273be1dc2b266d43a9a002ea5b18955aeef7aac881bb471a';
const AMULET_ID = 'Amulet';
const CBTC_ADMIN = 'cbtc-network::12202a83c6f4082217c175e29bc53da5f2703ba2675778ab99217a5a881a949203ff';
const CBTC_ID = 'CBTC';
const OPERATOR_PARTY =
  process.env.REACT_APP_OPERATOR_PARTY ||
  process.env.REACT_APP_OPERATOR_PARTY_ID ||
  'ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37';

const LiquidityInterface: React.FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { pools, setPools } = useAppStore();
  const { partyId, walletType } = useWalletAuth();
  const partyForBackend = walletType === 'loop' ? null : partyId || null;
  const { balances: loopBalances, reload: reloadLoopBalances } = useLoopBalances(partyId || null, walletType, {
    refreshIntervalMs: 15000,
  });
  const { balances: utxoBalances, reload: reloadUtxoBalances } = useUtxoBalances(partyForBackend, {
    ownerOnly: true,
    refreshIntervalMs: 15000,
  });

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
  const [liquidityResult, setLiquidityResult] = useState<any>(null);
  const [liquidityStatus, setLiquidityStatus] = useState<string | null>(null);
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

  const instrumentIdToSymbol = (instrumentId?: string) => {
    const raw = instrumentId || '';
    const upper = raw.toUpperCase();
    if (upper === 'AMULET') return 'CC';
    return upper;
  };

  const decimalAddStrings = (a: string, b: string): string => {
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
  };

  const tokenBOptions = useMemo(
    () => tokens.filter(token => token.symbol !== selectedTokenA?.symbol),
    [tokens, selectedTokenA]
  );

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

  const reloadBalances = useCallback(async () => {
    if (walletType === 'loop') {
      await reloadLoopBalances();
      return;
    }
    await reloadUtxoBalances();
  }, [walletType, reloadLoopBalances, reloadUtxoBalances]);

  useEffect(() => {
    reloadBalances();
  }, [reloadBalances, partyId, walletType]);

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
      setLiquidityResult(null);
      setLiquidityStatus(null);

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

      const poolCid =
        pool.contractId && (pool.poolId === pool.contractId || pool.contractId.startsWith('00'))
          ? pool.contractId
          : await backendApi.resolvePoolCid(targetPoolId);
      if (!poolCid) {
        toast.error('Unable to resolve pool CID. Refresh pools and try again.');
        return;
      }

      if (walletType !== 'loop') {
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
      } else {
        const connector = walletManager.getOrCreateLoopConnector();
        const status = await connector.ensureConnected(`liquidity-${Date.now()}`);
        if (!status.connected || !getLoopProvider()) {
          const message = status.error || 'Loop not connected';
          setLiquidityResult({ ok: false, error: { code: 'CONNECT', message } });
          toast.error(message);
          return;
        }

        const instrumentA = resolveInstrumentForSymbol(pool.tokenA.symbol);
        const instrumentB = resolveInstrumentForSymbol(pool.tokenB.symbol);
        if (!instrumentA || !instrumentB) {
          toast.error('Unsupported token pair for liquidity');
          return;
        }

        const poolSymbolA = pool.tokenA.symbol;
        const isSelectedAForPoolA = selectedTokenA.symbol === poolSymbolA;
        const amountForA = isSelectedAForPoolA ? amountANum : amountBNum;
        const amountForB = isSelectedAForPoolA ? amountBNum : amountANum;

        const requestId = `liq-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
        const deadline = new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString();
        const memoPayload = {
          v: 1,
          requestId,
          poolCid,
          receiverParty: partyId,
          deadline,
        };
        const memo = JSON.stringify(memoPayload);

        setLiquidityStatus('Submitting inbound transfer A');
        const preparedA = await prepareLoopTransfer(getLoopProvider(), {
          recipient: OPERATOR_PARTY,
          amount: amountForA.toFixed(10),
          instrument: instrumentA,
          memo,
          requestedAt: new Date().toISOString(),
          executeBefore: deadline,
        });
        const commandsA = extractPreparedCommands(preparedA);
        if (commandsA.length === 0) {
          setLiquidityResult({ ok: false, error: { code: 'PREPARE_A', message: 'Transfer preparation returned no commands', details: preparedA } });
          toast.error('Transfer A preparation failed');
          return;
        }
        const transferA = await submitTx({
          commands: commandsA,
          actAs: extractPreparedActAs(preparedA, partyId),
          readAs: extractPreparedReadAs(preparedA, partyId),
          deduplicationKey: `${requestId}-a`,
          memo,
          mode: 'LEGACY',
          disclosedContracts: extractPreparedDisclosedContracts(preparedA),
          packageIdSelectionPreference: extractPreparedPackagePreference(preparedA),
          synchronizerId: extractPreparedSynchronizerId(preparedA),
        });

        if (!transferA.ok || transferA.value?.txStatus !== 'SUCCEEDED') {
          setLiquidityResult({ transferA, transferB: null, consume: null });
          toast.error('Inbound transfer A failed');
          return;
        }

        setLiquidityStatus('Inbound transfer A submitted. Opening transfer B');
        await new Promise((resolve) => setTimeout(resolve, 400));
        setLiquidityStatus('Submitting inbound transfer B');
        const preparedB = await prepareLoopTransfer(getLoopProvider(), {
          recipient: OPERATOR_PARTY,
          amount: amountForB.toFixed(10),
          instrument: instrumentB,
          memo,
          requestedAt: new Date().toISOString(),
          executeBefore: deadline,
        });
        const commandsB = extractPreparedCommands(preparedB);
        if (commandsB.length === 0) {
          setLiquidityResult({ ok: false, error: { code: 'PREPARE_B', message: 'Transfer preparation returned no commands', details: preparedB } });
          toast.error('Transfer B preparation failed');
          return;
        }
        const transferB = await submitTx({
          commands: commandsB,
          actAs: extractPreparedActAs(preparedB, partyId),
          readAs: extractPreparedReadAs(preparedB, partyId),
          deduplicationKey: `${requestId}-b`,
          memo,
          mode: 'LEGACY',
          disclosedContracts: extractPreparedDisclosedContracts(preparedB),
          packageIdSelectionPreference: extractPreparedPackagePreference(preparedB),
          synchronizerId: extractPreparedSynchronizerId(preparedB),
        });

        let consumeResult: any = null;
        if (transferB.ok && transferB.value?.txStatus === 'SUCCEEDED') {
          setLiquidityStatus('Consuming liquidity (backend)');
          consumeResult = await backendApi.consumeDevnetLiquidity({ requestId, poolCid });
          if (consumeResult?.ok) {
            setLiquidityStatus('Liquidity added. LP minted.');
            toast.success('Liquidity added successfully');
            if (typeof window !== 'undefined') {
              window.dispatchEvent(new CustomEvent('clearportx:transactions:refresh', {
                detail: { source: 'liquidity:add', requestId },
              }));
            }
          } else {
            toast.error(consumeResult?.error?.message || 'Liquidity consume failed');
          }
        } else {
          toast.error('Inbound transfer B failed');
        }

        setLiquidityResult({
          transferA,
          transferB,
          consume: consumeResult,
        });
      }

      const updatedPools = await backendApi.getPools();
      setRawPools(updatedPools);
      await refreshWalletState();

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
                  Balance: {formatBalanceDisplay(selectedTokenA?.symbol)}
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
                  Balance: {formatBalanceDisplay(selectedTokenB?.symbol)}
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

            {liquidityStatus && (
              <div className="mt-4 text-sm text-gray-600 dark:text-gray-300">
                {liquidityStatus}
              </div>
            )}

            {liquidityResult?.consume?.ok && (
              <div className="mt-4 glass-subtle rounded-xl p-4 text-sm">
                <div className="font-semibold text-gray-900 dark:text-gray-100">
                  Liquidity added successfully.
                </div>
                <div className="mt-2 space-y-1">
                  <div className="flex justify-between body-small">
                    <span className="text-gray-600 dark:text-gray-400">LP Minted:</span>
                    <span className="font-semibold text-gray-900 dark:text-gray-100">
                      {liquidityResult.consume.result?.lpMinted}
                    </span>
                  </div>
                  <div className="flex justify-between body-small">
                    <span className="text-gray-600 dark:text-gray-400">New Reserve A:</span>
                    <span className="font-semibold text-gray-900 dark:text-gray-100">
                      {liquidityResult.consume.result?.newReserveA}
                    </span>
                  </div>
                  <div className="flex justify-between body-small">
                    <span className="text-gray-600 dark:text-gray-400">New Reserve B:</span>
                    <span className="font-semibold text-gray-900 dark:text-gray-100">
                      {liquidityResult.consume.result?.newReserveB}
                    </span>
                  </div>
                </div>
              </div>
            )}

            {liquidityResult?.consume && !liquidityResult.consume.ok && (
              <div className="mt-4 text-sm text-error-600 dark:text-error-400">
                {liquidityResult.consume.error?.message || 'Liquidity consume failed.'}
              </div>
            )}
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
        balances={balancesBySymbol}
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
        balances={balancesBySymbol}
        onSelect={(token) => {
          setSelectedTokenB(token);
          setShowTokenBSelector(false);
        }}
      />
    </div>
  );
};

type PreparedTransfer = Record<string, any>;

type InstrumentRef = {
  instrumentAdmin: string;
  instrumentId: string;
};

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

function extractPreparedDisclosedContracts(prepared: PreparedTransfer): any[] | undefined {
  const disclosed =
    prepared?.disclosedContracts ??
    prepared?.disclosed_contracts ??
    prepared?.extraArgs?.disclosedContracts ??
    prepared?.extra_args?.disclosed_contracts ??
    prepared?.payload?.disclosedContracts ??
    prepared?.payload?.disclosed_contracts;
  return Array.isArray(disclosed) ? disclosed : undefined;
}

function extractPreparedPackagePreference(prepared: PreparedTransfer): string[] | undefined {
  const pref =
    prepared?.packageIdSelectionPreference ??
    prepared?.package_id_selection_preference ??
    prepared?.packageIdSelection ??
    prepared?.package_id_selection ??
    prepared?.payload?.packageIdSelectionPreference ??
    prepared?.payload?.package_id_selection_preference;
  return Array.isArray(pref) ? pref : undefined;
}

function extractPreparedSynchronizerId(prepared: PreparedTransfer): string | undefined {
  return prepared?.synchronizerId ?? prepared?.synchronizer_id ?? undefined;
}

function extractPreparedActAs(prepared: PreparedTransfer, fallback: string): string | string[] {
  const actAs = prepared?.actAs ?? prepared?.act_as ?? prepared?.actAsParties ?? prepared?.act_as_parties;
  if (Array.isArray(actAs) && actAs.length > 0) {
    return actAs;
  }
  if (typeof actAs === 'string' && actAs.length > 0) {
    return actAs;
  }
  return fallback;
}

function extractPreparedReadAs(prepared: PreparedTransfer, fallback: string): string | string[] | undefined {
  const readAs = prepared?.readAs ?? prepared?.read_as ?? prepared?.readAsParties ?? prepared?.read_as_parties;
  if (Array.isArray(readAs) && readAs.length > 0) {
    return readAs;
  }
  if (typeof readAs === 'string' && readAs.length > 0) {
    return readAs;
  }
  return fallback ? [fallback] : undefined;
}

export default LiquidityInterface;