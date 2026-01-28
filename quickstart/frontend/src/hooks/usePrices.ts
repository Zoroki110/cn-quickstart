import { useEffect, useMemo, useState } from 'react';
import { fetchPrices } from '../api/prices';
import type { PriceQuote, PriceResponse } from '../types/canton';

type PricesState = {
  quotes: Record<string, PriceQuote>;
  asOf?: string;
  loading: boolean;
  error: Error | null;
};

const CACHE_TTL_MS = 30_000;
const cache = new Map<string, { data: PriceResponse; expiresAt: number }>();

export function usePrices(symbols: string[]) {
  const key = useMemo(
    () => symbols.map((s) => s.trim().toUpperCase()).filter(Boolean).sort().join(','),
    [symbols]
  );
  const [state, setState] = useState<PricesState>({
    quotes: {},
    asOf: undefined,
    loading: true,
    error: null,
  });

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      if (!key) {
        setState({ quotes: {}, asOf: undefined, loading: false, error: null });
        return;
      }
      const cached = cache.get(key);
      if (cached && cached.expiresAt > Date.now()) {
        if (!cancelled) {
          setState({ quotes: cached.data.quotes, asOf: cached.data.asOf, loading: false, error: null });
        }
        return;
      }
      setState((prev) => ({ ...prev, loading: true }));
      try {
        const data = await fetchPrices(key.split(','));
        cache.set(key, { data, expiresAt: Date.now() + CACHE_TTL_MS });
        if (!cancelled) {
          setState({ quotes: data.quotes || {}, asOf: data.asOf, loading: false, error: null });
        }
      } catch (err) {
        if (!cancelled) {
          setState((prev) => ({
            ...prev,
            loading: false,
            error: err instanceof Error ? err : new Error('Failed to load prices'),
          }));
        }
      }
    };
    load();
    return () => {
      cancelled = true;
    };
  }, [key]);

  return state;
}

