import { LpPositionInfo, PoolInfo } from '../types/canton';

export type LedgerSnapshot = {
  balancesKey?: string;
  poolKey?: string;
  lpKey?: string;
};

type RefreshLedgerStateInput = {
  label?: string;
  maxWaitMs?: number;
  intervalMs?: number;
  getSnapshot: () => LedgerSnapshot | null;
  fetchSnapshot: () => Promise<LedgerSnapshot | null>;
  onStatus?: (status: string | null) => void;
};

const DEFAULT_MAX_WAIT_MS = 15000;
const DEFAULT_INTERVAL_MS = 1000;

export function balancesKeyFromMap(
  balances: Record<string, { amount: string; decimals: number }> | null | undefined
): string {
  if (!balances) return '';
  const keys = Object.keys(balances).sort();
  return keys.map(key => `${key}:${balances[key]?.amount ?? ''}:${balances[key]?.decimals ?? ''}`).join('|');
}

export function poolKeyFromPools(pools: PoolInfo[] | null | undefined, poolCid?: string | null): string {
  if (!pools || pools.length === 0) return '';
  const pool = poolCid
    ? pools.find(p => p.contractId === poolCid || p.poolId === poolCid)
    : null;
  const target = pool ?? pools[0];
  if (!target) return '';
  return `${target.contractId || target.poolId}:${target.reserveA}:${target.reserveB}:${target.totalLiquidity}`;
}

export function lpKeyFromPositions(positions: LpPositionInfo[] | null | undefined, poolCid?: string | null): string {
  if (!positions || positions.length === 0) return '';
  const filtered = poolCid ? positions.filter(p => p.poolCid === poolCid) : positions;
  const sorted = [...filtered].sort((a, b) => a.poolCid.localeCompare(b.poolCid));
  return sorted.map(pos => `${pos.poolCid}:${pos.lpBalance}:${pos.shareBps ?? ''}`).join('|');
}

export async function refreshLedgerState({
  label = 'ledger',
  maxWaitMs = DEFAULT_MAX_WAIT_MS,
  intervalMs = DEFAULT_INTERVAL_MS,
  getSnapshot,
  fetchSnapshot,
  onStatus,
}: RefreshLedgerStateInput): Promise<void> {
  const start = Date.now();
  const initial = getSnapshot();
  let attempt = 0;

  while (true) {
    attempt += 1;
    onStatus?.(`Updating... (${label})`);
    const next = await fetchSnapshot();

    if (!initial || !snapshotsEqual(initial, next)) {
      onStatus?.(null);
      return;
    }
    if (Date.now() - start >= maxWaitMs) {
      onStatus?.(null);
      return;
    }
    await sleep(intervalMs);
  }
}

function snapshotsEqual(a: LedgerSnapshot | null, b: LedgerSnapshot | null): boolean {
  if (!a || !b) return false;
  return normalizeSnapshot(a) === normalizeSnapshot(b);
}

function normalizeSnapshot(snapshot: LedgerSnapshot): string {
  return JSON.stringify({
    balancesKey: snapshot.balancesKey ?? '',
    poolKey: snapshot.poolKey ?? '',
    lpKey: snapshot.lpKey ?? '',
  });
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}
export type LedgerSnapshot = {
  balancesKey?: string;
  poolsKey?: string;
  lpKey?: string;
};

export type RefreshLedgerStateOptions = {
  label?: string;
  getSnapshot: () => LedgerSnapshot;
  refresh: () => Promise<void>;
  intervalMs?: number;
  timeoutMs?: number;
};

export type RefreshLedgerStateResult = {
  changed: boolean;
  timedOut: boolean;
  attempts: number;
};

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function hasBaseline(snapshot: LedgerSnapshot): boolean {
  return Boolean(snapshot.balancesKey || snapshot.poolsKey || snapshot.lpKey);
}

function snapshotChanged(before: LedgerSnapshot, after: LedgerSnapshot): boolean {
  return (
    (before.balancesKey ?? '') !== (after.balancesKey ?? '') ||
    (before.poolsKey ?? '') !== (after.poolsKey ?? '') ||
    (before.lpKey ?? '') !== (after.lpKey ?? '')
  );
}

export async function refreshLedgerState(
  options: RefreshLedgerStateOptions
): Promise<RefreshLedgerStateResult> {
  const {
    label = 'ledger-refresh',
    getSnapshot,
    refresh,
    intervalMs = 1000,
    timeoutMs = 15000,
  } = options;

  const before = getSnapshot();
  const baselineExists = hasBaseline(before);
  const deadline = Date.now() + Math.max(timeoutMs, 0);
  let attempts = 0;

  while (true) {
    attempts += 1;
    await refresh();
    const after = getSnapshot();
    const changed = snapshotChanged(before, after);
    if (!baselineExists || changed) {
      if (attempts > 1) {
        console.log(`[${label}] refreshed after ${attempts} attempts (changed=${changed})`);
      }
      return { changed, timedOut: false, attempts };
    }
    if (Date.now() >= deadline) {
      console.warn(`[${label}] refresh timed out after ${attempts} attempts`);
      return { changed: false, timedOut: true, attempts };
    }
    await sleep(intervalMs);
  }
}

