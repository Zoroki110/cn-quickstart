import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, X, Clock, CheckCircle2, Loader2, Copy, AlertTriangle } from 'lucide-react';
import toast from 'react-hot-toast';
import { backendApi } from '../services/backendApi';
import type { TransactionHistoryEntry, TransactionTimelineItem } from '../types/canton';

const numberFormatter = new Intl.NumberFormat('en', {
  notation: 'compact',
  compactDisplay: 'short',
  maximumFractionDigits: 2,
});

const dateTimeFormatter = new Intl.DateTimeFormat('en-US', {
  month: 'short',
  day: 'numeric',
  year: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});

const formatCompact = (value?: string) => {
  if (!value) return '—';
  const num = Number(value);
  if (!Number.isFinite(num)) return value;
  return numberFormatter.format(num);
};

const formatDateTime = (iso?: string) => {
  if (!iso) return '—';
  try {
    return dateTimeFormatter.format(new Date(iso));
  } catch {
    return iso;
  }
};

const formatRelativeTime = (iso?: string) => {
  if (!iso) return '—';
  const created = new Date(iso).getTime();
  if (Number.isNaN(created)) return iso;
  const diffMs = Date.now() - created;
  if (diffMs < 60_000) return 'Just now';
  const minutes = Math.floor(diffMs / 60_000);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
};

const statusStyles: Record<TransactionHistoryEntry['status'], string> = {
  settled: 'bg-success-500/10 text-success-600 dark:text-success-400 border-success-500/30',
  pending: 'bg-warning-500/10 text-warning-600 dark:text-warning-400 border-warning-500/30',
  failed: 'bg-error-500/10 text-error-600 dark:text-error-400 border-error-500/30',
};

const statusLabels: Record<TransactionHistoryEntry['status'], string> = {
  settled: 'Settled',
  pending: 'Pending',
  failed: 'Failed',
};

const timelineIcon = (status: TransactionTimelineItem['status']) => {
  switch (status) {
    case 'completed':
      return <CheckCircle2 className="h-5 w-5 text-success-500" />;
    case 'pending':
      return <Clock className="h-5 w-5 text-warning-500" />;
    case 'failed':
      return <AlertTriangle className="h-5 w-5 text-error-500" />;
    default:
      return <Clock className="h-5 w-5 text-gray-400" />;
  }
};

const TransactionHistory: React.FC = () => {
  const [transactions, setTransactions] = useState<TransactionHistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dismissedIds, setDismissedIds] = useState<Set<string>>(new Set());
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const fetchHistory = useCallback(async () => {
    try {
      setError(null);
      const entries = await backendApi.getTransactionHistory();
      setTransactions(entries);
    } catch (err) {
      console.error('Failed to load transaction history:', err);
      setError('Impossible de charger l’historique. Réessayez dans un instant.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    fetchHistory();
  }, [fetchHistory]);

  const visibleTransactions = useMemo(
    () => transactions.filter((tx) => !dismissedIds.has(tx.id)),
    [transactions, dismissedIds]
  );

  useEffect(() => {
    if (!selectedId && visibleTransactions.length > 0) {
      setSelectedId(visibleTransactions[0].id);
    } else if (selectedId && !visibleTransactions.find(tx => tx.id === selectedId)) {
      setSelectedId(visibleTransactions[0]?.id || null);
    }
  }, [visibleTransactions, selectedId]);

  const selectedTransaction = useMemo(
    () => visibleTransactions.find((tx) => tx.id === selectedId) || null,
    [visibleTransactions, selectedId]
  );

  const handleRefresh = async () => {
    setRefreshing(true);
    await fetchHistory();
  };

  const handleDismiss = (id: string) => {
    setDismissedIds((prev) => {
      const next = new Set(prev);
      next.add(id);
      return next;
    });
  };

  const handleCopyContractId = async (contractId: string) => {
    try {
      await navigator.clipboard.writeText(contractId);
      toast.success('Contract ID copied to clipboard');
    } catch {
      toast.error('Unable to copy Contract ID');
    }
  };

  const formatTypeLabel = (type: TransactionHistoryEntry['type']) => {
    switch (type) {
      case 'ADD_LIQUIDITY':
        return 'Add Liquidity';
      case 'SWAP':
        return 'Swap';
      case 'POOL_CREATION':
        return 'Pool Creation';
      default:
        return type.replace('_', ' ');
    }
  };

  const summarizeAmount = (tx: TransactionHistoryEntry) => {
    if (tx.type === 'SWAP') {
      return `${formatCompact(tx.amountADesired)} ${tx.tokenA} → ${formatCompact(tx.amountBDesired)} ${tx.tokenB}`;
    }
    return `${formatCompact(tx.amountADesired)} ${tx.tokenA} / ${formatCompact(tx.amountBDesired)} ${tx.tokenB}`;
  };

  const renderDetails = (tx: TransactionHistoryEntry) => [
    { label: 'Token A', value: tx.tokenA || '—' },
    { label: 'Amount A Desired', value: `${formatCompact(tx.amountADesired)} ${tx.tokenA}` },
    { label: 'Token B', value: tx.tokenB || '—' },
    { label: 'Amount B Desired', value: `${formatCompact(tx.amountBDesired)} ${tx.tokenB}` },
    { label: 'Min LP Amount', value: tx.minLpAmount ? `${formatCompact(tx.minLpAmount)} ${tx.lpTokenSymbol || 'LP'}` : '—' },
    { label: 'Expires At', value: formatDateTime(tx.expiresAt) },
    { label: 'Status', value: statusLabels[tx.status] },
  ];

  if (loading) {
    return (
      <div className="max-w-5xl mx-auto">
        <div className="card">
          <div className="flex items-center justify-between mb-8">
            <h2 className="heading-2">Transaction History</h2>
            <div className="h-10 w-10 rounded-full border border-gray-200 dark:border-gray-700 flex items-center justify-center">
              <Loader2 className="h-5 w-5 animate-spin text-accent-600" />
            </div>
          </div>
          <div className="space-y-4">
            <div className="h-5 bg-gray-200/60 dark:bg-dark-800/60 rounded-lg animate-pulse" />
            <div className="h-40 bg-gray-200/30 dark:bg-dark-800/30 rounded-2xl animate-pulse" />
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-5xl mx-auto space-y-8">
      <div className="card">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between mb-6">
          <div>
            <p className="text-sm uppercase tracking-widest text-gray-500 dark:text-gray-400">Vue de détail</p>
            <h2 className="heading-2">Transaction History</h2>
          </div>
          <button
            onClick={handleRefresh}
            disabled={refreshing}
            className="btn-secondary flex items-center justify-center gap-2 px-4 py-2"
          >
            <RefreshCw className={`h-4 w-4 ${refreshing ? 'animate-spin' : ''}`} />
            Actualiser
          </button>
        </div>
        {error && (
          <div className="mb-6 rounded-xl border border-warning-500/40 bg-warning-500/10 px-4 py-3 text-warning-700 dark:text-warning-100">
            {error}
          </div>
        )}
        {visibleTransactions.length === 0 ? (
          <div className="text-center py-20">
            <p className="text-gray-500 dark:text-gray-400 mb-2">Aucune transaction disponible pour le moment.</p>
            <p className="text-sm text-gray-400">Exécutez un swap ou un ajout de liquidité pour voir l'historique.</p>
          </div>
        ) : (
          <div className="space-y-8">
            <div className="overflow-hidden rounded-2xl border border-gray-200/60 dark:border-gray-800/60 bg-white/70 dark:bg-dark-900/70">
              <div className="grid grid-cols-5 gap-4 px-4 py-3 text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
                <span>Time</span>
                <span>Type</span>
                <span>Pool</span>
                <span>Amount</span>
                <span>Status</span>
              </div>
              <div className="divide-y divide-gray-200/60 dark:divide-gray-800/60">
                {visibleTransactions.map((tx) => (
                  <button
                    key={tx.id}
                    onClick={() => setSelectedId(tx.id)}
                    className={`w-full text-left px-4 py-3 transition duration-200 ${
                      selectedId === tx.id
                        ? 'bg-white dark:bg-dark-800'
                        : 'hover:bg-gray-50 dark:hover:bg-dark-800/40'
                    }`}
                  >
                    <div className="grid grid-cols-5 gap-4 items-center">
                      <span className="text-sm text-gray-600 dark:text-gray-300">{formatRelativeTime(tx.createdAt)}</span>
                      <span className="text-sm font-medium text-gray-900 dark:text-gray-100">{formatTypeLabel(tx.type)}</span>
                      <span className="text-sm text-gray-700 dark:text-gray-200">{tx.poolId || '—'}</span>
                      <span className="text-sm text-gray-700 dark:text-gray-200">{summarizeAmount(tx)}</span>
                      <span className={`justify-self-start px-3 py-1 rounded-full text-xs font-semibold border ${statusStyles[tx.status]}`}>
                        {statusLabels[tx.status]}
                      </span>
                    </div>
                  </button>
                ))}
              </div>
            </div>

            {selectedTransaction && (
              <article className="rounded-2xl border border-gray-200/60 dark:border-gray-800/60 bg-white/80 dark:bg-dark-900/80 p-6 shadow-lg">
                <header className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between border-b border-gray-200/60 dark:border-gray-800/60 pb-5 mb-6">
                  <div>
                    <p className="text-sm text-accent-600 font-semibold uppercase tracking-wide">{selectedTransaction.title}</p>
                    <h3 className="text-responsive-lg font-semibold mt-1 text-gray-900 dark:text-gray-100">
                      {selectedTransaction.tokenA}/{selectedTransaction.tokenB}
                    </h3>
                    <p className="text-sm text-gray-500 dark:text-gray-400">
                      Created {formatDateTime(selectedTransaction.createdAt)}
                    </p>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className={`px-4 py-1.5 rounded-full text-sm font-medium border ${statusStyles[selectedTransaction.status]}`}>
                      {statusLabels[selectedTransaction.status]}
                    </span>
                    <button
                      onClick={() => handleDismiss(selectedTransaction.id)}
                      className="h-10 w-10 rounded-full border border-gray-200 dark:border-gray-700 flex items-center justify-center hover:bg-gray-50 dark:hover:bg-dark-800 transition-colors"
                      title="Fermer cette fiche"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  </div>
                </header>

                <section className="mb-8">
                  <h4 className="text-lg font-semibold text-gray-900 dark:text-gray-100 mb-4">Transaction Details</h4>
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    {renderDetails(selectedTransaction).map((detail) => (
                      <div key={`${selectedTransaction.id}-${detail.label}`} className="rounded-xl border border-gray-100 dark:border-gray-800 bg-white/60 dark:bg-dark-900/60 p-4">
                        <p className="text-sm text-gray-500 dark:text-gray-400">{detail.label}</p>
                        <p className="text-lg font-semibold text-gray-900 dark:text-gray-100">{detail.value}</p>
                      </div>
                    ))}
                  </div>
                </section>

                <section className="mb-8">
                  <h4 className="text-lg font-semibold text-gray-900 dark:text-gray-100 mb-4">Event Timeline</h4>
                  <ol className="relative border-l border-gray-200 dark:border-gray-700 pl-6 space-y-6">
                    {selectedTransaction.eventTimeline.map((event) => (
                      <li key={event.id} className="relative">
                        <span className="absolute -left-3 top-0 inline-flex h-6 w-6 items-center justify-center rounded-full bg-white dark:bg-dark-900 border border-gray-200 dark:border-gray-700 shadow">
                          {timelineIcon(event.status)}
                        </span>
                        <div className="rounded-xl border border-gray-100 dark:border-gray-800 bg-white/80 dark:bg-dark-900/70 p-4">
                          <div className="flex items-center justify-between mb-1">
                            <p className="font-semibold text-gray-900 dark:text-gray-100">{event.title}</p>
                            {event.timestamp && (
                              <span className="text-xs text-gray-500 dark:text-gray-400">{formatDateTime(event.timestamp)}</span>
                            )}
                          </div>
                          <p className="text-sm text-gray-600 dark:text-gray-300">{event.description}</p>
                        </div>
                      </li>
                    ))}
                  </ol>
                </section>

                <section>
                  <h4 className="text-lg font-semibold text-gray-900 dark:text-gray-100 mb-3">Contract ID</h4>
                  <div className="flex flex-col md:flex-row md:items-center gap-4">
                    <code className="flex-1 text-xs md:text-sm break-all rounded-xl bg-gray-900/90 text-white px-4 py-3 shadow-inner">
                      {selectedTransaction.contractId || 'N/A'}
                    </code>
                    <button
                      onClick={() => handleCopyContractId(selectedTransaction.contractId)}
                      className="btn-secondary flex items-center justify-center gap-2 px-4 py-2"
                    >
                      <Copy className="h-4 w-4" />
                      Copier
                    </button>
                  </div>
                </section>
              </article>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default TransactionHistory;