import React, { useState, useEffect, useRef, useMemo } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAppStore } from '../stores';
import { useWalletAuth } from '../wallet';
import { ENABLE_MANUAL_WALLET } from '../wallet/walletConfig';
import { useLoopBalances, useUtxoBalances, usePrices } from '../hooks';
import { buildPriceTooltip, formatUsdFull, parseUsdNumber } from '../utils/formatUsd';
import toast from 'react-hot-toast';

const Header: React.FC = () => {
  const location = useLocation();
  const { theme, setTheme } = useAppStore();
  const {
    partyId,
    walletType,
    loading: walletLoading,
    error: walletError,
    authenticateWithDev,
    authenticateWithLoop,
    authenticateWithZoro,
    disconnect,
  } = useWalletAuth();
  const partyForBackend = walletType === 'loop' ? null : partyId || null;
  const { balances: loopBalances, loading: loopLoading } = useLoopBalances(partyId || null, walletType, {
    refreshIntervalMs: 15000,
  });
  const { balances: utxoBalances, loading: utxoLoading } = useUtxoBalances(partyForBackend, {
    ownerOnly: true,
    refreshIntervalMs: 15000,
  });
  const { quotes: priceQuotes } = usePrices(['CC', 'CBTC']);
  const balancesLoading = walletType === 'loop' ? loopLoading : utxoLoading;
  const balanceEntries = useMemo(() => {
    if (walletType === 'loop') {
      return Object.entries(loopBalances)
        .map(([symbol, entry]) => ({
          symbol: symbol.toUpperCase(),
          amount: entry.amount,
          decimals: entry.decimals,
        }))
        .sort((a, b) => Number(b.amount) - Number(a.amount));
    }
    const acc = new Map<string, { symbol: string; amount: string; decimals: number }>();
    Object.values(utxoBalances).forEach((entry) => {
      const symbol = instrumentIdToSymbol(entry.instrumentId);
      const existing = acc.get(symbol);
      if (!existing) {
        acc.set(symbol, { symbol, amount: entry.amount, decimals: entry.decimals });
        return;
      }
      acc.set(symbol, {
        symbol,
        amount: decimalAddStrings(existing.amount, entry.amount),
        decimals: existing.decimals ?? entry.decimals,
      });
    });
    return Array.from(acc.values()).sort((a, b) => Number(b.amount) - Number(a.amount));
  }, [walletType, loopBalances, utxoBalances]);
  const [menuOpen, setMenuOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const handler = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    };

    if (menuOpen) {
      document.addEventListener('mousedown', handler);
    }

    return () => document.removeEventListener('mousedown', handler);
  }, [menuOpen]);

  const navigation = [
    { name: 'Swap', href: '/swap' },
    { name: 'Pools', href: '/pools' },
    { name: 'Liquidity', href: '/liquidity' },
    { name: 'History', href: '/history' },
  ];

  const toggleTheme = () => {
    setTheme(theme === 'light' ? 'dark' : 'light');
  };

  const baseWalletOptions: Array<{
    key: string;
    label: string;
    description: string;
    iconSrc: string;
    iconAlt: string;
    action: () => Promise<unknown>;
  }> = [
    {
      key: 'loop',
      label: 'Loop Wallet',
      description: 'Se connecter avec le SDK Loop officiel.',
      iconSrc: '/loop.svg',
      iconAlt: 'Loop Wallet logo',
      action: authenticateWithLoop,
    },
    {
      key: 'zoro',
      label: 'Zoro Wallet',
      description: 'Utiliser la future extension Zoro.',
      iconSrc: '/zoro.svg',
      iconAlt: 'Zoro Wallet logo',
      action: authenticateWithZoro,
    },
  ];

  const walletOptions = ENABLE_MANUAL_WALLET
    ? [
        {
          key: 'party',
          label: 'Party ID (manuel)',
          description: 'Saisir un party Canton et signer le challenge via Dev Wallet.',
          iconSrc: '/clearportx-logo.svg',
          iconAlt: 'ClearportX logo',
          action: authenticateWithDev,
        },
        ...baseWalletOptions,
      ]
    : baseWalletOptions;

  // Keep synchronous so wallet connect runs in the click stack (needed for Loop popup).
  const handleConnect = (action: () => Promise<unknown> | unknown) => {
    try {
      const res = action();
      if (res && typeof (res as Promise<unknown>).catch === "function") {
        (res as Promise<unknown>).catch((err) => {
          toast.error(err instanceof Error ? err.message : "Wallet connection failed");
          console.error("Wallet connection failed", err);
        });
      }
      setMenuOpen(false);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Wallet connection failed");
      console.error("Wallet connection failed", err);
    }
  };

  const connected = Boolean(partyId);
  const priceQuoteFor = (symbol?: string) => {
    if (!symbol) return undefined;
    return priceQuotes[symbol.toUpperCase()];
  };

  const formatUsdEstimate = (symbol: string, amount: string) => {
    const quote = priceQuoteFor(symbol);
    const price = parseUsdNumber(quote?.priceUsd ?? null);
    const qty = Number(amount);
    if (!Number.isFinite(qty) || price == null || quote?.status === 'UNAVAILABLE') {
      return { value: '—', tooltip: buildPriceTooltip(quote?.reason, quote?.source), available: false };
    }
    return {
      value: `≈ ${formatUsdFull(qty * price)}`,
      tooltip: buildPriceTooltip(quote?.reason, quote?.source),
      available: true,
    };
  };

  return (
    <header className="sticky top-0 z-50 glass-strong border-b border-gray-200/20 dark:border-gray-700/20">
      <div className="container-app">
        <div className="flex items-center justify-between h-16">
          <Link to="/" className="flex items-center space-x-3">
            <div className="h-10 w-10 rounded-lg bg-white dark:bg-white p-1 flex items-center justify-center">
              <img
                src="/clearportx-logo.svg"
                alt="ClearportX Logo"
                className="h-full w-full"
              />
            </div>
            <div className="hidden sm:block">
              <h1 className="text-xl font-bold text-gradient-clearportx">ClearportX</h1>
              <p className="text-xs text-gray-500 dark:text-gray-400">Institutional Digital Asset Trading</p>
            </div>
          </Link>

          <nav className="hidden md:flex items-center space-x-1">
            {navigation.map((item) => {
              const isActive = location.pathname === item.href ||
                (location.pathname === '/' && item.href === '/swap');

              return (
                <Link
                  key={item.name}
                  to={item.href}
                  className={`px-4 py-2 rounded-lg font-medium transition-all duration-200 ${
                    isActive
                      ? 'text-accent-600 dark:text-accent-500 bg-accent-50 dark:bg-accent-900/20'
                      : 'text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100 hover:bg-gray-100 dark:hover:bg-dark-800'
                  }`}
                >
                  {item.name}
                </Link>
              );
            })}
          </nav>

          <div className="flex items-center space-x-3">
            <div className="relative" ref={dropdownRef}>
              <button
                type="button"
                onClick={() => setMenuOpen((prev) => !prev)}
                disabled={walletLoading}
                className={`flex items-center gap-3 rounded-xl border px-4 py-2 text-left shadow-sm transition duration-200 min-w-[180px] ${
                  connected
                    ? 'border-emerald-200/60 bg-emerald-50 dark:border-emerald-900/40 dark:bg-emerald-900/20 text-emerald-900 dark:text-emerald-200'
                    : 'border-primary-200/60 bg-white/80 dark:border-dark-700 dark:bg-dark-800 text-gray-800 dark:text-gray-100'
                } ${walletLoading ? 'opacity-70 cursor-not-allowed' : 'hover:shadow-lg'}`}
              >
                <div className="flex-1">
                  <p className="text-[11px] uppercase tracking-[0.2em] text-gray-400 dark:text-gray-500">Wallet</p>
                  <p className="text-sm font-semibold">
                    {connected ? formatPartyId(partyId) : 'Connect Wallet'}
                  </p>
                  {connected && (
                    <p className="text-xs text-gray-500 dark:text-gray-400">
                      {walletType ? walletType.toUpperCase() : 'Unknown'}
                    </p>
                  )}
                </div>
                <svg
                  className={`w-4 h-4 transition-transform ${menuOpen ? 'rotate-180' : ''}`}
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                </svg>
              </button>

              {menuOpen && (
                <div className="absolute right-0 mt-3 w-80 rounded-2xl border border-gray-200/80 dark:border-gray-700 bg-white dark:bg-dark-900 shadow-2xl backdrop-blur p-4 space-y-3">
                  {connected ? (
                    <>
                      <div className="rounded-xl border border-emerald-100 dark:border-emerald-900/40 bg-emerald-50/60 dark:bg-emerald-900/10 px-4 py-3">
                        <p className="text-xs uppercase tracking-wide text-emerald-600 dark:text-emerald-300">Connecté</p>
                        <p className="font-semibold text-gray-900 dark:text-white break-all">{partyId}</p>
                        <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">Wallet: {walletType ?? 'unknown'}</p>
                      </div>
                      <div className="mt-3 text-sm text-gray-700 dark:text-gray-200">
                        <div className="font-semibold mb-1">Balances</div>
                        {balancesLoading && (
                          <div className="text-xs opacity-70">Loading on-ledger balances…</div>
                        )}
                        {!balancesLoading && balanceEntries.length === 0 && (
                          <div className="text-xs opacity-70">No holdings visible</div>
                        )}
                        {!balancesLoading && balanceEntries.length > 0 &&
                          balanceEntries.slice(0, 5).map((h) => {
                            const showUsd = h.symbol === 'CC' || h.symbol === 'CBTC';
                            const usd = showUsd ? formatUsdEstimate(h.symbol, h.amount) : null;
                            return (
                              <div key={h.symbol} className="flex justify-between">
                                <span>{h.symbol}</span>
                                <span className="text-right">
                                  {formatHoldingAmount(h.symbol, h.amount, h.decimals)}
                                  {showUsd && (
                                    <span
                                      className="ml-2 text-[11px] text-gray-500 dark:text-gray-400"
                                      title={usd?.tooltip}
                                    >
                                      {usd?.value}
                                      {!usd?.available && <span className="ml-1 text-[10px] uppercase">N/A</span>}
                                    </span>
                                  )}
                                </span>
                              </div>
                            );
                          })}
                        {!balancesLoading && balanceEntries.length > 5 && (
                          <div className="text-xs opacity-70 mt-1">+ {balanceEntries.length - 5} more</div>
                        )}
                      </div>
                      <button
                        type="button"
                        onClick={() => {
                          disconnect();
                          setMenuOpen(false);
                        }}
                        className="w-full rounded-xl border border-gray-300 dark:border-gray-700 px-4 py-2 text-sm font-semibold text-gray-700 dark:text-gray-200 hover:bg-gray-50 dark:hover:bg-dark-800 transition"
                      >
                        Disconnect
                      </button>
                    </>
                  ) : (
                    <>
                      {walletOptions.map((option) => (
                        <button
                          key={option.key}
                          type="button"
                          disabled={walletLoading}
                          onClick={() => handleConnect(option.action)}
                          className={`w-full text-left rounded-2xl border border-gray-200 dark:border-gray-700 px-4 py-3 transition bg-white/90 dark:bg-dark-800/80 hover:-translate-y-0.5 hover:shadow-lg disabled:opacity-60`}
                        >
                          <div className="flex items-center justify-between gap-4">
                            <div>
                              <p className="text-sm font-semibold text-gray-900 dark:text-white">{option.label}</p>
                              <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">{option.description}</p>
                            </div>
                            <div className="flex-shrink-0 w-12 h-12 rounded-full border border-gray-200 dark:border-gray-700 bg-white dark:bg-dark-800 shadow-sm flex items-center justify-center overflow-hidden">
                              <img
                                src={option.iconSrc}
                                alt={option.iconAlt}
                                className="w-8 h-8 object-contain"
                                loading="lazy"
                              />
                            </div>
                          </div>
                        </button>
                      ))}
                      {walletError && (
                        <p className="text-sm text-rose-500 dark:text-rose-400">{walletError}</p>
                      )}
                      {walletLoading && (
                        <p className="text-xs text-gray-500 dark:text-gray-400">Connexion en cours…</p>
                      )}
                    </>
                  )}
                </div>
              )}
            </div>

            <button
              onClick={toggleTheme}
              className="p-2 rounded-lg bg-gray-100 dark:bg-dark-800 text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100 transition-colors duration-200"
              title={theme === 'light' ? 'Switch to dark mode' : 'Switch to light mode'}
            >
              {theme === 'light' ? (
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" />
                </svg>
              ) : (
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z" />
                </svg>
              )}
            </button>
          </div>
        </div>
      </div>
    </header>
  );
};

// Sanity: formatHoldingAmount("CC", "188800", 10) => "188,800"
// Sanity: formatHoldingAmount("CBTC", "1.1", 8) => "1.1000"
function formatHoldingAmount(symbol: string, quantity: string, decimals?: number) {
  const num = Number(quantity);
  const isCc = symbol?.toUpperCase() === 'CC';
  const valid = Number.isFinite(num);
  const formatter = new Intl.NumberFormat('en-US', {
    useGrouping: true,
    minimumFractionDigits: isCc ? 0 : 0,
    maximumFractionDigits: isCc ? 0 : 4,
  });
  const formatted = formatter.format(valid ? num : 0);
  // For non-CC, drop trailing zeros after the decimal point.
  if (!isCc && formatted.includes('.')) {
    return formatted.replace(/\.0+$/, '').replace(/(\.\d*?)0+$/, '$1');
  }
  return formatted;
}

function instrumentIdToSymbol(instrumentId?: string) {
  const raw = instrumentId || '';
  const upper = raw.toUpperCase();
  if (upper === 'AMULET') {
    return 'CC';
  }
  return upper;
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

function formatPartyId(party?: string | null) {
  if (!party) return 'Not connected';
  if (party.length <= 22) {
    return party;
  }
  return `${party.slice(0, 10)}…${party.slice(-6)}`;
}

export default Header;
