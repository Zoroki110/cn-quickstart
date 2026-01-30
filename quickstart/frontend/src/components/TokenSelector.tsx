import React, { useState } from 'react';
import { createPortal } from 'react-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { X, Search } from 'lucide-react';
import { TokenInfo } from '../types/canton';
type BalanceEntry = {
  amount: string;
  decimals: number;
};

interface TokenSelectorProps {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (token: TokenInfo) => void;
  tokens: TokenInfo[];
  selectedToken: TokenInfo | null;
  type: 'from' | 'to';
  balances?: Record<string, BalanceEntry>;
}

const TokenSelector: React.FC<TokenSelectorProps> = ({
  isOpen,
  onClose,
  onSelect,
  tokens,
  selectedToken,
  type,
  balances,
}) => {
  const [searchQuery, setSearchQuery] = useState('');

  const filteredTokens = tokens.filter((token) =>
    token.symbol.toLowerCase().includes(searchQuery.toLowerCase()) ||
    token.name.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const popularTokens = ['USDC', 'ETH', 'BTC', 'USDT', 'CBTC', 'CC'];

  const formatBalance = (symbol: string) => {
    const entry = balances?.[symbol.toUpperCase()];
    if (!entry) {
      return null;
    }
    const numeric = Number(entry.amount);
    return Number.isFinite(numeric) ? numeric.toFixed(4) : entry.amount;
  };

  const renderTokenIcon = (token: TokenInfo, size: string = 'w-10 h-10') => (
    <div
      className={`${size} rounded-full overflow-hidden border border-gray-200 dark:border-dark-700 flex items-center justify-center ${
        token.logoUrl ? 'bg-white dark:bg-dark-700' : 'bg-gray-100 dark:bg-dark-800'
      }`}
    >
      {token.logoUrl ? (
        <img
          src={token.logoUrl}
          alt={`${token.symbol} logo`}
          className="w-full h-full object-cover"
          loading="lazy"
        />
      ) : (
        <span className="text-sm font-semibold text-gray-900 dark:text-gray-100">
          {token.symbol.charAt(0)}
        </span>
      )}
    </div>
  );

  const content = (
    <AnimatePresence>
      {isOpen && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 z-[9999] flex items-center justify-center p-4 bg-black/60 backdrop-blur-md"
          onClick={onClose}
        >
          <motion.div
            initial={{ scale: 0.9, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            exit={{ scale: 0.9, opacity: 0 }}
            className="w-full max-w-md bg-white dark:bg-dark-900 rounded-2xl shadow-xl overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Header */}
            <div className="flex items-center justify-between p-6 border-b border-gray-200 dark:border-gray-700">
              <div>
                <h3 className="text-lg font-semibold text-gray-900 dark:text-gray-100">
                  Select a token
                </h3>
              </div>
              <button
                onClick={onClose}
                className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-dark-800 transition-colors"
              >
                <X className="w-5 h-5 text-gray-500 dark:text-gray-400" />
              </button>
            </div>

            {/* Search */}
            <div className="p-4">
              <div className="relative">
                <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-5 h-5 text-gray-400" />
                <input
                  type="text"
                  placeholder="Search tokens..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full pl-10 pr-4 py-3 bg-gray-50 dark:bg-dark-800 border-0 rounded-xl focus:ring-2 focus:ring-primary-500 text-gray-900 dark:text-gray-100 placeholder-gray-500 dark:placeholder-gray-400"
                />
              </div>
            </div>

            {/* Popular Tokens */}
            {!searchQuery && (
              <div className="px-4 pb-4">
                <p className="text-sm font-medium text-gray-700 dark:text-gray-300 mb-3">Popular tokens</p>
                <div className="flex flex-wrap gap-2">
                  {popularTokens.map((symbol) => {
                    const token = tokens.find((t) => t.symbol === symbol);
                    if (!token) return null;

                    return (
                      <button
                        key={symbol}
                        onClick={() => onSelect(token)}
                        className="px-3 py-2 bg-gray-100 dark:bg-dark-800 hover:bg-gray-200 dark:hover:bg-dark-700 rounded-lg text-sm font-medium transition-colors text-gray-900 dark:text-gray-100 flex items-center space-x-2"
                      >
                        {renderTokenIcon(token, 'w-6 h-6')}
                        <span>{symbol}</span>
                      </button>
                    );
                  })}
                </div>
              </div>
            )}

            {/* Token List */}
            <div className="max-h-96 overflow-y-auto scrollbar-thin scrollbar-thumb-gray-300 dark:scrollbar-thumb-gray-600">
              {filteredTokens.length === 0 ? (
                <div className="p-8 text-center text-gray-500 dark:text-gray-400">
                  <p>No tokens found</p>
                </div>
              ) : (
                <div className="space-y-1 p-2">
                  {filteredTokens.map((token) => (
                    <motion.button
                      key={token.symbol}
                      whileHover={{ scale: 1.02 }}
                      whileTap={{ scale: 0.98 }}
                      onClick={() => onSelect(token)}
                      disabled={selectedToken?.symbol === token.symbol}
                      className={`w-full p-4 rounded-xl text-left transition-colors flex items-center justify-between ${
                        selectedToken?.symbol === token.symbol
                          ? 'bg-primary-50 dark:bg-primary-900/20 border border-primary-200 dark:border-primary-700'
                          : 'hover:bg-gray-50 dark:hover:bg-dark-800'
                      }`}
                    >
                      <div className="flex items-center space-x-3">
                        {renderTokenIcon(token)}
                        <div>
                          <p className="font-semibold text-gray-900 dark:text-gray-100">{token.symbol}</p>
                          <p className="text-sm text-gray-500 dark:text-gray-400">{token.name}</p>
                        </div>
                      </div>

                      <div className="text-right">
                        <p className="font-medium text-gray-900 dark:text-gray-100">
                          {formatBalance(token.symbol) ?? '0.0000'}
                        </p>
                        <p className="text-sm text-gray-500 dark:text-gray-400">Balance</p>
                      </div>
                    </motion.button>
                  ))}
                </div>
              )}
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );

  if (typeof document === 'undefined') {
    return content;
  }
  return createPortal(content, document.body);
};

export default TokenSelector;

