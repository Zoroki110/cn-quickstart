import React from 'react';
import { motion } from 'framer-motion';
import { Info, TrendingUp, TrendingDown } from 'lucide-react';
import { SwapQuote, TokenInfo } from '../types/canton';

interface SwapDetailsProps {
  quote: SwapQuote;
  fromToken: TokenInfo;
  toToken: TokenInfo;
}

const SwapDetails: React.FC<SwapDetailsProps> = ({ quote, fromToken, toToken }) => {
  const exchangeRate = quote.outputAmount / quote.inputAmount;
  const inverseRate = quote.inputAmount / quote.outputAmount;

  const getPriceImpactColor = (impact: number) => {
    if (impact < 1) return 'text-green-600';
    if (impact < 3) return 'text-yellow-600';
    return 'text-red-600';
  };

  const getPriceImpactIcon = (impact: number) => {
    if (impact < 1) return <TrendingUp className="w-4 h-4" />;
    return <TrendingDown className="w-4 h-4" />;
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      className="space-y-3"
    >
      {/* Exchange Rate */}
      <div className="flex items-center justify-between">
        <div className="flex items-center space-x-2">
          <Info className="w-4 h-4 text-gray-400" />
          <span className="text-sm text-gray-600">Exchange Rate</span>
        </div>
        <div className="text-right">
          <p className="text-sm font-medium text-gray-900">
            1 {fromToken.symbol} = {exchangeRate.toFixed(6)} {toToken.symbol}
          </p>
          <p className="text-xs text-gray-500">
            1 {toToken.symbol} = {inverseRate.toFixed(6)} {fromToken.symbol}
          </p>
        </div>
      </div>

      {/* Price Impact */}
      <div className="flex items-center justify-between">
        <div className="flex items-center space-x-2">
          {getPriceImpactIcon(quote.priceImpact)}
          <span className="text-sm text-gray-600">Price Impact</span>
        </div>
        <span className={`text-sm font-medium ${getPriceImpactColor(quote.priceImpact)}`}>
          {quote.priceImpact.toFixed(3)}%
        </span>
      </div>

      {/* Liquidity Provider Fee */}
      <div className="flex items-center justify-between">
        <span className="text-sm text-gray-600">Liquidity Provider Fee</span>
        <span className="text-sm font-medium text-gray-900">
          {quote.fee.toFixed(6)} {fromToken.symbol}
        </span>
      </div>

      {/* Route */}
      <div className="flex items-center justify-between">
        <span className="text-sm text-gray-600">Route</span>
        <div className="flex items-center space-x-2">
          {quote.route.map((token, index) => (
            <React.Fragment key={token}>
              <span className="text-sm font-medium text-gray-900">{token}</span>
              {index < quote.route.length - 1 && (
                <span className="text-gray-400">→</span>
              )}
            </React.Fragment>
          ))}
        </div>
      </div>

      {/* Minimum Received */}
      <div className="flex items-center justify-between pt-2 border-t border-gray-200">
        <span className="text-sm text-gray-600">Minimum Received</span>
        <span className="text-sm font-medium text-gray-900">
          {(quote.outputAmount * (1 - quote.slippage / 100)).toFixed(6)} {toToken.symbol}
        </span>
      </div>
    </motion.div>
  );
};

export default SwapDetails;

