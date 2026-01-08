import React, { useState, useCallback } from 'react';
import { LoopWalletConnector } from '../wallet/LoopWalletConnector';
import { backendApi } from '../services/backendApi';

// CBTC network admin party on DevNet
const CBTC_INSTRUMENT_ADMIN = 'cbtc-network::12202a83c6f4082217c175e29bc53da5f2703ba2675778ab99217a5a881a949203ff';
const CBTC_INSTRUMENT_ID = 'CBTC';

// ClearportX operator party
const CLEARPORTX_OPERATOR = 'ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37';

interface AcceptResult {
  success: boolean;
  updateId?: string;
  holdingCid?: string;
  amount?: string;
  error?: string;
  elapsedMs?: number;
}

interface CbtcHolding {
  contractId: string;
  amount: string;
  owner: string;
}

/**
 * DevNet-only UI component for accepting incoming CBTC offers.
 *
 * Option A Flow:
 * 1. User pastes TransferOffer contractId
 * 2. Loop SDK accepts the offer (Loop has TransferInstruction visibility)
 * 3. Acceptance creates CBTC Holding owned by ClearportX
 * 4. Backend polls for the new holding using HoldingSelectorService
 */
export default function DevNetCbtcAccept() {
  const [transferOfferCid, setTransferOfferCid] = useState('');
  const [isAccepting, setIsAccepting] = useState(false);
  const [isPolling, setIsPolling] = useState(false);
  const [result, setResult] = useState<AcceptResult | null>(null);
  const [existingHoldings, setExistingHoldings] = useState<CbtcHolding[]>([]);
  const [isLoadingHoldings, setIsLoadingHoldings] = useState(false);

  // Load existing CBTC holdings for ClearportX
  const loadExistingHoldings = useCallback(async () => {
    setIsLoadingHoldings(true);
    try {
      const holdings = await backendApi.getCbtcHoldings(CLEARPORTX_OPERATOR);
      setExistingHoldings(holdings.map(h => ({
        contractId: h.contractId,
        amount: h.amount,
        owner: h.owner,
      })));
    } catch (err) {
      console.error('Failed to load existing holdings:', err);
    } finally {
      setIsLoadingHoldings(false);
    }
  }, []);

  // Accept CBTC offer via Loop SDK
  const handleAccept = useCallback(async () => {
    if (!transferOfferCid.trim()) {
      setResult({ success: false, error: 'Please enter a TransferOffer contract ID' });
      return;
    }

    setIsAccepting(true);
    setResult(null);

    try {
      // Step 1: Accept via Loop SDK
      const connector = new LoopWalletConnector();
      await connector.connect();

      console.log('[DevNetCbtcAccept] Accepting CBTC offer:', transferOfferCid);

      const acceptResult = await connector.acceptIncomingCbtcOffer({
        transferOfferCid: transferOfferCid.trim(),
        receiverParty: CLEARPORTX_OPERATOR,
      });

      if (!acceptResult.success) {
        setResult({
          success: false,
          error: acceptResult.error || 'Loop SDK acceptance failed',
        });
        setIsAccepting(false);
        return;
      }

      console.log('[DevNetCbtcAccept] Loop acceptance result:', acceptResult);

      // Step 2: Poll backend for the new holding
      setIsAccepting(false);
      setIsPolling(true);

      const holdingResult = await backendApi.selectHolding({
        ownerParty: CLEARPORTX_OPERATOR,
        instrumentAdmin: CBTC_INSTRUMENT_ADMIN,
        instrumentId: CBTC_INSTRUMENT_ID,
        minAmount: '0',
        timeoutSeconds: 30,
        pollIntervalMs: 2000,
      });

      setIsPolling(false);

      if (holdingResult.found) {
        setResult({
          success: true,
          updateId: acceptResult.updateId,
          holdingCid: holdingResult.holdingCid || undefined,
          amount: holdingResult.amount || undefined,
          elapsedMs: holdingResult.elapsedMs,
        });

        // Refresh holdings list
        await loadExistingHoldings();
      } else {
        setResult({
          success: false,
          updateId: acceptResult.updateId,
          error: holdingResult.error || 'Holding not found after acceptance',
          elapsedMs: holdingResult.elapsedMs,
        });
      }
    } catch (err: any) {
      console.error('[DevNetCbtcAccept] Error:', err);
      setResult({
        success: false,
        error: err?.message || String(err),
      });
      setIsAccepting(false);
      setIsPolling(false);
    }
  }, [transferOfferCid, loadExistingHoldings]);

  // Truncate contract ID for display
  const truncateCid = (cid: string) => {
    if (!cid || cid.length <= 24) return cid;
    return `${cid.substring(0, 12)}...${cid.substring(cid.length - 8)}`;
  };

  return (
    <div className="bg-white dark:bg-dark-800 rounded-2xl shadow-lg p-6 border border-gray-200 dark:border-dark-700">
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-xl font-bold text-gray-900 dark:text-white">
          CBTC Accept (DevNet)
        </h2>
        <span className="px-2 py-1 text-xs font-medium bg-orange-100 text-orange-800 dark:bg-orange-900 dark:text-orange-200 rounded-full">
          Option A
        </span>
      </div>

      <p className="text-sm text-gray-600 dark:text-gray-400 mb-4">
        Accept incoming CBTC TransferOffers via Loop SDK. The acceptance creates a
        CBTC Holding owned by ClearportX operator.
      </p>

      {/* Input Section */}
      <div className="space-y-4">
        <div>
          <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
            TransferOffer Contract ID
          </label>
          <input
            type="text"
            value={transferOfferCid}
            onChange={(e) => setTransferOfferCid(e.target.value)}
            placeholder="Paste the TransferOffer contractId here..."
            className="w-full px-4 py-3 bg-gray-50 dark:bg-dark-700 border border-gray-200 dark:border-dark-600 rounded-xl text-sm text-gray-900 dark:text-white placeholder-gray-400 dark:placeholder-gray-500 focus:ring-2 focus:ring-blue-500 focus:border-transparent transition-all"
            disabled={isAccepting || isPolling}
          />
        </div>

        <button
          onClick={handleAccept}
          disabled={isAccepting || isPolling || !transferOfferCid.trim()}
          className={`w-full py-3 px-4 rounded-xl font-semibold text-white transition-all ${
            isAccepting || isPolling || !transferOfferCid.trim()
              ? 'bg-gray-400 cursor-not-allowed'
              : 'bg-gradient-to-r from-blue-500 to-purple-600 hover:from-blue-600 hover:to-purple-700 shadow-lg hover:shadow-xl'
          }`}
        >
          {isAccepting ? (
            <span className="flex items-center justify-center">
              <svg className="animate-spin -ml-1 mr-3 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              Accepting via Loop...
            </span>
          ) : isPolling ? (
            <span className="flex items-center justify-center">
              <svg className="animate-spin -ml-1 mr-3 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              Polling for Holding...
            </span>
          ) : (
            'Accept CBTC Offer'
          )}
        </button>
      </div>

      {/* Result Section */}
      {result && (
        <div className={`mt-6 p-4 rounded-xl ${
          result.success
            ? 'bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800'
            : 'bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800'
        }`}>
          <h3 className={`font-semibold mb-2 ${
            result.success ? 'text-green-800 dark:text-green-200' : 'text-red-800 dark:text-red-200'
          }`}>
            {result.success ? 'Success!' : 'Failed'}
          </h3>

          {result.success ? (
            <div className="space-y-2 text-sm text-green-700 dark:text-green-300">
              {result.holdingCid && (
                <div>
                  <span className="font-medium">Holding CID:</span>{' '}
                  <code className="bg-green-100 dark:bg-green-800 px-2 py-0.5 rounded text-xs">
                    {truncateCid(result.holdingCid)}
                  </code>
                </div>
              )}
              {result.amount && (
                <div>
                  <span className="font-medium">Amount:</span> {result.amount} CBTC
                </div>
              )}
              {result.elapsedMs !== undefined && (
                <div>
                  <span className="font-medium">Time:</span> {result.elapsedMs}ms
                </div>
              )}
            </div>
          ) : (
            <p className="text-sm text-red-700 dark:text-red-300">
              {result.error}
            </p>
          )}
        </div>
      )}

      {/* Existing Holdings Section */}
      <div className="mt-6 pt-6 border-t border-gray-200 dark:border-dark-700">
        <div className="flex items-center justify-between mb-4">
          <h3 className="font-semibold text-gray-900 dark:text-white">
            ClearportX CBTC Holdings
          </h3>
          <button
            onClick={loadExistingHoldings}
            disabled={isLoadingHoldings}
            className="text-sm text-blue-600 dark:text-blue-400 hover:underline disabled:opacity-50"
          >
            {isLoadingHoldings ? 'Loading...' : 'Refresh'}
          </button>
        </div>

        {existingHoldings.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">
            No CBTC holdings found. Click Refresh to load.
          </p>
        ) : (
          <div className="space-y-2">
            {existingHoldings.map((holding, idx) => (
              <div
                key={holding.contractId || idx}
                className="flex items-center justify-between p-3 bg-gray-50 dark:bg-dark-700 rounded-lg"
              >
                <div className="text-sm">
                  <code className="text-xs text-gray-600 dark:text-gray-400">
                    {truncateCid(holding.contractId)}
                  </code>
                </div>
                <div className="font-semibold text-gray-900 dark:text-white">
                  {holding.amount} CBTC
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Info Section */}
      <div className="mt-6 p-4 bg-blue-50 dark:bg-blue-900/20 rounded-xl border border-blue-200 dark:border-blue-800">
        <h4 className="font-medium text-blue-800 dark:text-blue-200 mb-2">How it works</h4>
        <ol className="list-decimal list-inside text-sm text-blue-700 dark:text-blue-300 space-y-1">
          <li>Paste the TransferOffer contractId from the sender</li>
          <li>Loop SDK accepts the offer (has TI visibility)</li>
          <li>Acceptance creates a CBTC Holding owned by ClearportX</li>
          <li>Backend polls for the new holding using UTXO selection</li>
        </ol>
      </div>
    </div>
  );
}
