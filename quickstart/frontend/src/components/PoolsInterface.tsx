import React, { useEffect } from 'react';
import { useAppStore } from '../stores';
import { backendApi } from '../services/backendApi';
import { useNavigate } from 'react-router-dom';
import { TokenInfo } from '../types/canton';

const PoolsInterface: React.FC = () => {
  const { pools, setPools } = useAppStore();
  const navigate = useNavigate();
  const [loading, setLoading] = React.useState(true);

  useEffect(() => {
    const loadPools = async () => {
      try {
        setLoading(true);
        const poolsData = await backendApi.getPools();
        setPools(poolsData);
      } catch (error) {
        console.error('Error loading pools:', error);
      } finally {
        setLoading(false);
      }
    };

    loadPools();
  }, [setPools]);

  // Calculer les statistiques globales
  const totalTVL = pools.reduce((sum, pool) => {
    // Approximation: TVL = reserveA + reserveB (en assumant que les tokens ont des valeurs similaires)
    return sum + pool.reserveA + pool.reserveB;
  }, 0);

  const totalVolume24h = pools.reduce((sum, pool) => sum + pool.volume24h, 0);
  const averageAPR = pools.length > 0
    ? pools.reduce((sum, pool) => sum + pool.apr, 0) / pools.length
    : 0;

  const renderPoolToken = (token: TokenInfo, extraClasses = '') => (
    <div
      className={`w-10 h-10 rounded-full overflow-hidden border border-white/50 shadow-md flex items-center justify-center ${
        token.logoUrl ? 'bg-white dark:bg-dark-700' : 'bg-primary-50 dark:bg-dark-800'
      } ${extraClasses}`}
    >
      {token.logoUrl ? (
        <img
          src={token.logoUrl}
          alt={`${token.symbol} logo`}
          className="w-full h-full object-cover"
          loading="lazy"
        />
      ) : (
        <span className="text-base font-bold text-gray-900 dark:text-gray-100">
          {token.symbol.charAt(0)}
        </span>
      )}
    </div>
  );

  const formatNumber = (num: number) => {
    if (num >= 1_000_000) {
      return `$${(num / 1_000_000).toFixed(2)}M`;
    } else if (num >= 1_000) {
      return `$${(num / 1_000).toFixed(2)}K`;
    }
    return `$${num.toFixed(2)}`;
  };

  const formatToken = (amount: number, decimals: number = 2) => {
    if (amount >= 1_000_000) {
      return `${(amount / 1_000_000).toFixed(decimals)}M`;
    } else if (amount >= 1_000) {
      return `${(amount / 1_000).toFixed(decimals)}K`;
    }
    return amount.toFixed(decimals);
  };

  if (loading) {
    return (
      <section className="relative min-h-[calc(100vh-220px)] flex items-center justify-center px-4 py-10">
        <div
          className="pointer-events-none absolute inset-0 bg-gradient-to-b from-white/0 via-white/70 to-white dark:from-dark-900/0 dark:via-dark-900/60 dark:to-dark-950"
          aria-hidden="true"
        />
        <div className="relative w-full max-w-6xl">
          <div className="card">
            <div className="flex items-center justify-center py-12">
              <div className="spinner w-12 h-12"></div>
            </div>
          </div>
        </div>
      </section>
    );
  }


  return (
    <div className="min-h-[calc(100vh-220px)] px-4 py-10">
      <div className="relative max-w-6xl mx-auto space-y-6">
      {/* Header avec Stats Globales */}
      <div className="glass-strong rounded-2xl p-6">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="heading-2">Liquidity Pools</h1>
            <p className="body-small mt-1">Provide liquidity and earn trading fees</p>
          </div>
          <button
            onClick={() => navigate('/liquidity')}
            className="btn-primary px-6 py-3"
          >
            + Add Liquidity
          </button>
        </div>

        {/* Stats Cards */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="glass-subtle rounded-xl p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="body-small mb-1">Total Value Locked</p>
                <p className="text-2xl font-bold text-gradient">{formatNumber(totalTVL)}</p>
              </div>
              <div className="w-12 h-12 rounded-xl bg-primary-100 dark:bg-primary-900/20 flex items-center justify-center">
                <svg className="w-6 h-6 text-primary-600 dark:text-primary-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              </div>
            </div>
          </div>

          <div className="glass-subtle rounded-xl p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="body-small mb-1">24h Volume</p>
                <p className="text-2xl font-bold text-gradient-cleaportx">{formatNumber(totalVolume24h)}</p>
              </div>
              <div className="w-12 h-12 rounded-xl bg-success-100 dark:bg-success-900/20 flex items-center justify-center">
                <svg className="w-6 h-6 text-success-600 dark:text-success-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 12l3-3 3 3 4-4M8 21l4-4 4 4M3 4h18M4 4h16v12a1 1 0 01-1 1H5a1 1 0 01-1-1V4z" />
                </svg>
              </div>
            </div>
          </div>

          <div className="glass-subtle rounded-xl p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="body-small mb-1">Average APR</p>
                <p className="text-2xl font-bold text-success-600 dark:text-success-400">
                  {averageAPR.toFixed(2)}%
                </p>
              </div>
              <div className="w-12 h-12 rounded-xl bg-warning-100 dark:bg-warning-900/20 flex items-center justify-center">
                <svg className="w-6 h-6 text-warning-600 dark:text-warning-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                </svg>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Liste des Pools */}
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="heading-3">All Pools ({pools.length})</h2>
        </div>

        {pools.length === 0 ? (
          <div className="card text-center py-12">
            <p className="text-gray-500 dark:text-gray-400">
              No pools available yet. Be the first to create a pool!
            </p>
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-4">
            {pools.map((pool) => (
              <div
                key={pool.contractId}
                className="card-hover glass-strong rounded-2xl p-6 transition-all duration-300"
              >
                <div className="flex items-center justify-between">
                  {/* Pool Info */}
                  <div className="flex items-center space-x-4 flex-1">
                    {/* Token Pair */}
                    <div className="flex items-center">
                      {renderPoolToken(pool.tokenA)}
                      {renderPoolToken(pool.tokenB, '-ml-3')}

                      <div className="ml-3">
                        <h3 className="font-bold text-lg text-gray-900 dark:text-gray-100">
                          {pool.tokenA.symbol}/{pool.tokenB.symbol}
                        </h3>
                        <p className="body-small">
                          {pool.tokenA.name} / {pool.tokenB.name}
                        </p>
                      </div>
                    </div>

                    {/* Pool Stats */}
                    <div className="hidden lg:flex items-center space-x-8 ml-auto">
                      {/* Liquidity */}
                      <div>
                        <p className="body-small mb-1">Liquidity</p>
                        <p className="font-semibold text-gray-900 dark:text-gray-100">
                          {formatToken(pool.reserveA)} {pool.tokenA.symbol}
                        </p>
                        <p className="body-small text-gray-500">
                          {formatToken(pool.reserveB)} {pool.tokenB.symbol}
                        </p>
                      </div>

                      {/* APR */}
                      <div>
                        <p className="body-small mb-1">APR</p>
                        <p className="text-lg font-bold text-success-600 dark:text-success-400">
                          {pool.apr.toFixed(2)}%
                        </p>
                      </div>

                      {/* Volume 24h */}
                      <div>
                        <p className="body-small mb-1">24h Volume</p>
                        <p className="font-semibold text-gray-900 dark:text-gray-100">
                          {formatNumber(pool.volume24h)}
                        </p>
                      </div>

                      {/* Fee Rate */}
                      <div>
                        <p className="body-small mb-1">Fee</p>
                        <p className="font-semibold text-gray-900 dark:text-gray-100">
                          {(pool.feeRate * 100).toFixed(2)}%
                        </p>
                      </div>
                    </div>
                  </div>

                  {/* Action Button */}
                  <button
                    onClick={() => navigate('/liquidity', { state: { pool } })}
                    className="btn-primary ml-4"
                  >
                    Add Liquidity
                  </button>
                </div>

                {/* Mobile Stats */}
                <div className="lg:hidden mt-4 pt-4 border-t border-gray-200 dark:border-gray-700">
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <p className="body-small mb-1">Liquidity</p>
                      <p className="font-semibold text-sm text-gray-900 dark:text-gray-100">
                        {formatToken(pool.reserveA)} {pool.tokenA.symbol}
                      </p>
                      <p className="body-small text-gray-500">
                        {formatToken(pool.reserveB)} {pool.tokenB.symbol}
                      </p>
                    </div>
                    <div>
                      <p className="body-small mb-1">APR</p>
                      <p className="text-lg font-bold text-success-600 dark:text-success-400">
                        {pool.apr.toFixed(2)}%
                      </p>
                    </div>
                    <div>
                      <p className="body-small mb-1">24h Volume</p>
                      <p className="font-semibold text-sm text-gray-900 dark:text-gray-100">
                        {formatNumber(pool.volume24h)}
                      </p>
                    </div>
                    <div>
                      <p className="body-small mb-1">Fee</p>
                      <p className="font-semibold text-sm text-gray-900 dark:text-gray-100">
                        {(pool.feeRate * 100).toFixed(2)}%
                      </p>
                    </div>
                  </div>
                </div>

                {/* User Position (if any) */}
                {pool.userLiquidity && pool.userLiquidity > 0 && (
                  <div className="mt-4 pt-4 border-t border-gray-200 dark:border-gray-700">
                    <div className="flex items-center justify-between">
                      <div>
                        <p className="body-small mb-1">Your Position</p>
                        <p className="font-semibold text-gray-900 dark:text-gray-100">
                          {pool.userLiquidity.toFixed(4)} LP Tokens
                        </p>
                      </div>
                      <div className="text-right">
                        <p className="body-small mb-1">Pool Share</p>
                        <p className="font-semibold text-primary-600 dark:text-primary-400">
                          {pool.userShare?.toFixed(2)}%
                        </p>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Info Card */}
      <div className="glass-subtle rounded-2xl p-6">
        <div className="flex items-start space-x-3">
          <div className="text-2xl">💡</div>
          <div>
            <h3 className="font-semibold mb-2 text-gray-900 dark:text-gray-100">
              About Liquidity Pools
            </h3>
            <p className="body-small">
              Liquidity pools allow you to earn fees by providing liquidity to trading pairs.
              When you add liquidity, you receive LP (Liquidity Provider) tokens that represent
              your share of the pool. You earn a portion of trading fees proportional to your share.
            </p>
            <ul className="mt-2 space-y-1 body-small text-gray-600 dark:text-gray-400">
              <li>• Earn {((pools[0]?.feeRate || 0.003) * 100).toFixed(2)}% fee from every trade</li>
              <li>• Your share of fees increases with your pool share</li>
              <li>• Withdraw your liquidity anytime by burning LP tokens</li>
            </ul>
          </div>
        </div>
      </div>
      </div>
    </div>
  );
};

export default PoolsInterface;