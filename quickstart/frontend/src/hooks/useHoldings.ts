import { useEffect, useState, useCallback } from "react";
import { apiGet } from "../api/client";

export type HoldingSummary = {
  symbol: string;
  quantity: string;
  decimals: number;
  displayName?: string;
};

type HoldingsResponse = Array<Record<string, unknown>>;

export function useHoldings(partyId: string | null) {
  const [holdings, setHoldings] = useState<HoldingSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  const load = useCallback(async () => {
    if (!partyId) {
      setHoldings([]);
      setError(null);
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);
    try {
      const data = await apiGet<HoldingsResponse>(`/api/holdings/${encodeURIComponent(partyId)}`);
      const normalized = Array.isArray(data)
        ? (data.map(normalizeHolding).filter(Boolean) as HoldingSummary[])
        : [];
      setHoldings(normalized);
    } catch (err: any) {
      console.warn("Failed to load holdings for", partyId, err);
      setHoldings([]);
      setError(err instanceof Error ? err : new Error("Failed to load holdings"));
    } finally {
      setLoading(false);
    }
  }, [partyId]);

  useEffect(() => {
    load();
  }, [load]);

  return { holdings, loading, error, reload: load };
}

function normalizeHolding(raw: Record<string, unknown>): HoldingSummary {
  const symbolCandidate =
    (raw as any)?.symbol ??
    (raw as any)?.token ??
    (raw as any)?.assetCode ??
    (raw as any)?.metadata?.symbol ??
    (raw as any)?.metadata?.code ??
    "UNKNOWN";
  const symbol = typeof symbolCandidate === "string" ? symbolCandidate.toUpperCase() : "UNKNOWN";

  const quantityCandidate =
    (raw as any)?.quantity ??
    (raw as any)?.amount ??
    (raw as any)?.balance ??
    (raw as any)?.holdingAmount ??
    "0";
  const quantity =
    typeof quantityCandidate === "string" ? quantityCandidate : String(quantityCandidate ?? "0");

  const decimalsCandidate =
    (raw as any)?.decimals ??
    (raw as any)?.precision ??
    (raw as any)?.metadata?.decimals ??
    (raw as any)?.metadata?.precision ??
    10;
  const decimals =
    typeof decimalsCandidate === "number" && Number.isFinite(decimalsCandidate)
      ? decimalsCandidate
      : parseInt(String(decimalsCandidate), 10) || 10;

  const displayName =
    (raw as any)?.displayName ??
    (raw as any)?.name ??
    (raw as any)?.metadata?.name ??
    undefined;

  return { symbol, quantity, decimals, displayName };
}
