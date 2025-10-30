import React, { useState } from 'react';
import { motion } from 'framer-motion';
import { useAppStore } from '../stores/useAppStore';

interface SlippageSettingsProps {
  onClose?: () => void;
}

const SlippageSettings: React.FC<SlippageSettingsProps> = ({ onClose }) => {
  const { slippage, setSlippage } = useAppStore();
  const [customSlippage, setCustomSlippage] = useState('');
  const [deadline, setDeadline] = useState(20);

  const presetSlippages = [0.1, 0.5, 1.0];

  const handleSlippageChange = (value: number) => {
    setSlippage(value);
    setCustomSlippage('');
  };

  const handleCustomSlippageChange = (value: string) => {
    setCustomSlippage(value);
    const numValue = parseFloat(value);
    if (!isNaN(numValue) && numValue >= 0 && numValue <= 50) {
      setSlippage(numValue);
    }
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: -10 }}
      animate={{ opacity: 1, y: 0 }}
      className="p-4 bg-gray-50 dark:bg-dark-800 rounded-xl space-y-4 border border-gray-200 dark:border-gray-700"
    >
      <div className="flex items-center justify-between mb-2">
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">
          Transaction Settings
        </h3>
        {onClose && (
          <button
            onClick={onClose}
            className="text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200"
          >
            ✕
          </button>
        )}
      </div>

      <div>
        <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-3">
          Slippage Tolerance
        </label>
        <div className="flex space-x-2">
          {presetSlippages.map((preset) => (
            <button
              key={preset}
              onClick={() => handleSlippageChange(preset)}
              className={`px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
                slippage === preset && !customSlippage
                  ? 'bg-primary-100 dark:bg-primary-900/30 text-primary-700 dark:text-primary-400 border border-primary-300 dark:border-primary-700'
                  : 'bg-white dark:bg-dark-900 text-gray-700 dark:text-gray-300 border border-gray-300 dark:border-gray-600 hover:bg-gray-50 dark:hover:bg-dark-700'
              }`}
            >
              {preset}%
            </button>
          ))}
          <div className="relative">
            <input
              type="number"
              placeholder="Custom"
              value={customSlippage}
              onChange={(e) => handleCustomSlippageChange(e.target.value)}
              className="w-20 px-3 py-2 text-sm border border-gray-300 dark:border-gray-600 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500 bg-white dark:bg-dark-900 text-gray-900 dark:text-gray-100"
              min="0"
              max="50"
              step="0.1"
            />
            {customSlippage && (
              <span className="absolute right-8 top-1/2 transform -translate-y-1/2 text-xs text-gray-500 dark:text-gray-400">
                %
              </span>
            )}
          </div>
        </div>
        {slippage > 5 && (
          <p className="text-sm text-yellow-600 dark:text-yellow-500 mt-2">
            ⚠️ High slippage tolerance may result in unfavorable trades.
          </p>
        )}
      </div>

      <div>
        <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-3">
          Transaction Deadline
        </label>
        <div className="flex items-center space-x-2">
          <input
            type="number"
            value={deadline}
            onChange={(e) => setDeadline(parseInt(e.target.value) || 20)}
            className="w-20 px-3 py-2 text-sm border border-gray-300 dark:border-gray-600 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500 bg-white dark:bg-dark-900 text-gray-900 dark:text-gray-100"
            min="1"
            max="4320"
          />
          <span className="text-sm text-gray-600 dark:text-gray-400">minutes</span>
        </div>
        <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
          Your transaction will revert if it is pending for more than this long.
        </p>
      </div>
    </motion.div>
  );
};

export default SlippageSettings;

