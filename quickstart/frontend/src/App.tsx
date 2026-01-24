import React, { useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import { Toaster } from 'react-hot-toast';
import { initializeCantonApi } from './services';
import { useAppStore } from './stores';
import { useCantonHealth } from './hooks';
import { BUILD_INFO } from './config/build-info';

// Components
import {
  Header,
  SwapInterface,
  PoolsInterface,
  LiquidityInterface,
  TransactionHistory,
  ConnectionStatus,
  LoadingScreen,
  DevNetCbtcAccept,
  DevNetLoopTxTester,
} from './components';
import AuthCallback from './components/AuthCallback';

// Configuration Canton pour CleaPortX
const CANTON_CONFIG = {
  ledgerApiUrl: 'http://localhost:8080',
  adminApiUrl: 'http://localhost:8080',
  participantId: 'app_user_participant',
};

function App() {
  const { setConnected, theme } = useAppStore();
  const { data: isHealthy, isLoading: healthLoading } = useCantonHealth();
  useEffect(() => {
    // Initialiser l'API Canton
    try {
      initializeCantonApi(CANTON_CONFIG);
      console.log('Canton API initialized successfully for ClearPortX');
      console.log('Build version:', BUILD_INFO.version, 'Backend:', BUILD_INFO.features.backendUrl);
      console.log('Using backend API only:', BUILD_INFO.features.backendApiOnly);
      console.log('TypeScript errors fixed:', BUILD_INFO.features.typeScriptErrorFixed);
    } catch (error) {
      console.error('Failed to initialize Canton API:', error);
    }
  }, []);

  useEffect(() => {
    // Mettre à jour le statut de connexion basé sur la santé de Canton
    if (isHealthy !== undefined) {
      setConnected(isHealthy);
    }
  }, [isHealthy, setConnected]);

  useEffect(() => {
    // Appliquer le thème
    document.documentElement.classList.toggle('dark', theme === 'dark');
  }, [theme]);

  if (healthLoading) {
    return <LoadingScreen />;
  }

  return (
    <Router>
      <div className={`min-h-screen bg-gradient-to-br from-gray-50 via-white to-gray-100 dark:from-dark-950 dark:via-dark-900 dark:to-dark-800 transition-colors duration-300`}>
        {/* Background Pattern */}
        <div className="fixed inset-0 bg-mesh opacity-20 pointer-events-none"></div>

        {/* DevNet Badge - Only show in development */}
        {process.env.NODE_ENV === 'development' && (
          <div className="fixed bottom-2 right-2 z-50 rounded-md bg-gray-100 dark:bg-gray-800 border border-gray-300 dark:border-gray-700 text-gray-600 dark:text-gray-400 px-2 py-1 text-xs shadow-sm backdrop-blur-sm opacity-50">
            DevNet
          </div>
        )}

        <Header />

        {/* Status de connexion */}
        <ConnectionStatus />
        
        {/* Contenu principal */}
        <main className="relative container-app py-8 space-y-6">
          <Routes>
            <Route path="/" element={<SwapInterface />} />
            <Route path="/swap" element={<SwapInterface />} />
            <Route path="/pools" element={<PoolsInterface />} />
            <Route path="/liquidity" element={<LiquidityInterface />} />
            <Route path="/history" element={<TransactionHistory />} />
            <Route path="/devnet/cbtc" element={<DevNetCbtcAccept />} />
            <Route path="/devnet/loop-tx" element={<DevNetLoopTxTester />} />
            <Route path="/auth/callback" element={<AuthCallback />} />
          </Routes>
        </main>
        
        {/* Footer */}
        <footer className="relative mt-auto py-8 text-center border-t border-gray-200/20 dark:border-gray-700/20">
          <div className="container-app">
            <div className="flex flex-col sm:flex-row items-center justify-between space-y-4 sm:space-y-0">
              <div className="flex items-center space-x-2">
                <div className="h-6 w-6 rounded bg-white dark:bg-white p-0.5 flex items-center justify-center">
                  <img
                    src="/clearportx-logo.svg"
                    alt="ClearportX Logo"
                    className="h-full w-full"
                  />
                </div>
                <span className="text-gradient-clearportx font-semibold">ClearportX</span>
              </div>

              <p className="body-small">
                Powered by{' '}
                <span className="text-gradient font-semibold">Canton Network</span>
                {' '}• Advanced DeFi Trading Platform
              </p>
              
              <div className="flex items-center space-x-4 text-xs text-gray-500 dark:text-gray-400">
                <span>Privacy-First</span>
                <span>•</span>
                <span>Institutional Grade</span>
                <span>•</span>
                <span>Secure</span>
              </div>
            </div>
          </div>
        </footer>

        {/* Toast Notifications */}
        <Toaster
          position="top-right"
          toastOptions={{
            duration: 4000,
            style: {
              background: theme === 'dark' ? '#1f2937' : '#ffffff',
              color: theme === 'dark' ? '#f3f4f6' : '#111827',
              border: `1px solid ${theme === 'dark' ? '#374151' : '#e5e7eb'}`,
              borderRadius: '12px',
              boxShadow: '0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05)',
            },
            success: {
              iconTheme: {
                primary: '#22c55e',
                secondary: '#ffffff',
              },
            },
            error: {
              iconTheme: {
                primary: '#ef4444',
                secondary: '#ffffff',
              },
            },
          }}
        />
      </div>
    </Router>
  );
}

export default App;