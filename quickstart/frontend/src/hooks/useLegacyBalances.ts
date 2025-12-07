import { useCallback, useEffect, useState } from "react";
import { apiGet } from "../api/client";

export type LegacyBalance = {
  amount: string;
  decimals: number;
};

export type LegacyBalanceMap = Record<string, LegacyBalance>;

type LegacyBalanceDto = {
  symbol: string;
  amount: string;
  decimals: number;
  instrumentId?: string | null;
};

type UseLegacyBalancesResult = {
  balances: LegacyBalanceMap;
  loading: boolean;
  error: Error | null;
  reload: () => Promise<void>;
};

export function useLegacyBalances(partyId: string | null | undefined): UseLegacyBalancesResult {
  const [balances, setBalances] = useState<LegacyBalanceMap>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  const fetchBalances = useCallback(async () => {
    if (!partyId) {
      setBalances({});
      setError(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const response = await apiGet<LegacyBalanceDto[]>(`/api/wallet/balances/${encodeURIComponent(partyId)}`);
      const map = response.reduce<LegacyBalanceMap>((acc, entry) => {
        if (!entry?.symbol) {
          return acc;
        }
        const symbol = entry.symbol.toUpperCase();
        acc[symbol] = {
          amount: entry.amount,
          decimals: entry.decimals,
        };
        return acc;
      }, {});
      setBalances(map);
      setError(null);
    } catch (err) {
      console.error("Failed to load legacy balances", err);
      setError(err instanceof Error ? err : new Error("Failed to load balances"));
      setBalances({});
    } finally {
      setLoading(false);
    }
  }, [partyId]);

  useEffect(() => {
    fetchBalances();
  }, [fetchBalances]);

  return {
    balances,
    loading,
    error,
    reload: fetchBalances,
  };
}
