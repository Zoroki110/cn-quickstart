export const formatUsd = (value?: number | null) => {
  if (value == null || !Number.isFinite(value)) return '—';
  const abs = Math.abs(value);
  if (abs >= 1_000_000_000) return `$${(value / 1_000_000_000).toFixed(2)}B`;
  if (abs >= 1_000_000) return `$${(value / 1_000_000).toFixed(2)}M`;
  if (abs >= 1_000) return `$${(value / 1_000).toFixed(2)}K`;
  return `$${value.toFixed(2)}`;
};

export const formatUsdFull = (value?: number | null) => {
  if (value == null || !Number.isFinite(value)) return '—';
  return `$${value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
};

export const parseUsdNumber = (raw?: string | number | null) => {
  if (raw == null) return null;
  const num = typeof raw === 'number' ? raw : Number(raw);
  return Number.isFinite(num) ? num : null;
};

export const buildPriceTooltip = (reason?: string | null, source?: string | null) => {
  if (!reason && !source) return undefined;
  const parts = [];
  if (reason) {
    parts.push(reason === 'NO_RELIABLE_SOURCE' ? 'No reliable USD price' : reason.replace(/_/g, ' '));
  }
  if (source) {
    parts.push(`source: ${source}`);
  }
  return parts.join(' · ');
};

