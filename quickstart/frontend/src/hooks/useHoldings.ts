import { useEffect, useState, useCallback } from "react";
import { apiGet } from "../api/client";
import { walletManager } from "../wallet";

export type HoldingSummary = {
  symbol: string;
  quantity: string;
  decimals: number;
  displayName?: string;
};

type HoldingsResponse = Array<Record<string, unknown>>;

export function useHoldings(params: { partyId?: string | null; walletType?: string | null }) {
  const { partyId, walletType } = params;
  const [holdings, setHoldings] = useState<HoldingSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [loopRetry, setLoopRetry] = useState(0);

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
      // Loop path: use connector wrapper methods to preserve provider binding.
      if (walletType === "loop") {
        const connector = walletManager.getLoopConnector();
        if (!connector) {
          if (loopRetry < 3) {
            setLoopRetry((r) => r + 1);
            setTimeout(() => load(), 500);
          }
          setLoading(false);
          return;
        }
        try {
          const loopHoldings = await (connector as any).getHoldings();
          const normalized = Array.isArray(loopHoldings)
            ? (loopHoldings.map(normalizeLoopHolding).filter(Boolean) as HoldingSummary[])
            : [];
          setHoldings(normalized);
          setLoading(false);
          return;
        } catch (err) {
          console.warn("[Loop] getHoldings failed, fallback to backend", err);
        }
      }

      // Default path: backend API.
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
  }, [partyId, walletType, loopRetry]);

  useEffect(() => {
    load();
  }, [load]);

  return { holdings, loading, error };
}

function normalizeHolding(raw: Record<string, unknown>): HoldingSummary {
  const instrument = (raw as any)?.instrument;
  const metadata = (raw as any)?.metadata ?? instrument?.metadata;
  const rawInstrumentId = instrument?.instrumentId?.id ?? (raw as any)?.instrumentId ?? null;

  const symbolCandidate =
    instrument?.instrumentId?.id ??
    instrument?.symbol ??
    instrument?.assetCode ??
    metadata?.symbol ??
    metadata?.code ??
    (raw as any)?.symbol ??
    (raw as any)?.token ??
    (raw as any)?.assetCode ??
    (raw as any)?.currencyCode ??
    rawInstrumentId ??
    "UNKNOWN";

  let symbol = String(symbolCandidate ?? "UNKNOWN").toUpperCase();
  let displayName =
    (raw as any)?.name ??
    (raw as any)?.metadata?.name ??
    metadata?.name ??
    instrument?.name ??
    (symbol === "CC" ? "Canton Coin" : undefined);

  if (rawInstrumentId === "Amulet" || symbol === "AMULET") {
    symbol = "CC";
    displayName = "Canton Coin";
  }

  const quantityCandidate =
    (raw as any)?.amount ??
    (raw as any)?.balance ??
    (raw as any)?.holdingAmount ??
    (raw as any)?.quantity ??
    "0";
  const quantity =
    typeof quantityCandidate === "string" ? quantityCandidate : String(quantityCandidate ?? "0");

  const decimalsCandidate = (raw as any)?.decimals ?? (raw as any)?.precision ?? 10;
  const decimals =
    typeof decimalsCandidate === "number" && Number.isFinite(decimalsCandidate)
      ? decimalsCandidate
      : parseInt(String(decimalsCandidate), 10) || 10;

  return { symbol, quantity, decimals, displayName };
}

function normalizeLoopHolding(raw: Record<string, unknown>): HoldingSummary | null {
  const instrument = (raw as any)?.instrument_id ?? (raw as any)?.instrumentId ?? (raw as any)?.instrument;
  const instrumentId =
    instrument?.id ??
    instrument?.instrumentId?.id ??
    instrument?.assetCode ??
    instrument?.symbol ??
    instrument?.code ??
    "UNKNOWN";

  let symbol = String(instrumentId ?? "UNKNOWN").toUpperCase();
  let displayName: string | undefined =
    (raw as any)?.name ??
    instrument?.name ??
    (symbol === "CC" ? "Canton Coin" : undefined);

  if (instrumentId === "Amulet" || symbol === "AMULET") {
    symbol = "CC";
    displayName = "Canton Coin";
  }
  if (instrumentId === "CBTC" || symbol === "CBTC") {
    symbol = "CBTC";
  }

  const quantityCandidate =
    (raw as any)?.amount ??
    (raw as any)?.quantity ??
    (raw as any)?.balance ??
    "0";
  const quantity =
    typeof quantityCandidate === "string" ? quantityCandidate : String(quantityCandidate ?? "0");

  const decimalsCandidate = (raw as any)?.decimals ?? (raw as any)?.precision ?? 10;
  const decimals =
    typeof decimalsCandidate === "number" && Number.isFinite(decimalsCandidate)
      ? decimalsCandidate
      : parseInt(String(decimalsCandidate), 10) || 10;

  return { symbol, quantity, decimals, displayName };
}
