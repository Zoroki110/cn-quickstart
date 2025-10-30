import React, { useState, useEffect } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAppStore } from '../stores';
import { isAuthenticated, login, logout, getUsername } from '../services/auth';

const Header: React.FC = () => {
  const location = useLocation();
  const { theme, setTheme } = useAppStore();
  const [authenticated, setAuthenticated] = useState(isAuthenticated());
  const [username, setUsername] = useState(getUsername());

  // Check auth state periodically
  useEffect(() => {
    const interval = setInterval(() => {
      setAuthenticated(isAuthenticated());
      setUsername(getUsername());
    }, 1000);
    return () => clearInterval(interval);
  }, []);

  const navigation = [
    { name: 'Swap', href: '/swap' },
    { name: 'Pools', href: '/pools' },
    { name: 'Liquidity', href: '/liquidity' },
    { name: 'History', href: '/history' },
  ];

  const toggleTheme = () => {
    setTheme(theme === 'light' ? 'dark' : 'light');
  };

  return (
    <header className="sticky top-0 z-50 glass-strong border-b border-gray-200/20 dark:border-gray-700/20">
      <div className="container-app">
        <div className="flex items-center justify-between h-16">
          {/* Logo */}
          <Link to="/" className="flex items-center space-x-3">
            <div className="h-10 w-10 rounded-lg bg-white dark:bg-white p-1 flex items-center justify-center">
              <img
                src="/clearportx-logo.svg"
                alt="ClearportX Logo"
                className="h-full w-full"
              />
            </div>
            <div className="hidden sm:block">
              <h1 className="text-xl font-bold text-gradient-clearportx">ClearportX</h1>
              <p className="text-xs text-gray-500 dark:text-gray-400">Institutional Digital Asset Trading</p>
            </div>
          </Link>

          {/* Navigation */}
          <nav className="hidden md:flex items-center space-x-1">
            {navigation.map((item) => {
              const isActive = location.pathname === item.href || 
                (location.pathname === '/' && item.href === '/swap');
              
              return (
                <Link
                  key={item.name}
                  to={item.href}
                  className={`px-4 py-2 rounded-lg font-medium transition-all duration-200 ${
                    isActive 
                      ? 'text-accent-600 dark:text-accent-500 bg-accent-50 dark:bg-accent-900/20' 
                      : 'text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100 hover:bg-gray-100 dark:hover:bg-dark-800'
                  }`}
                >
                  {item.name}
                </Link>
              );
            })}
          </nav>

          {/* Actions */}
          <div className="flex items-center space-x-3">
            {/* OAuth Login/Logout */}
            {!authenticated ? (
              <button
                onClick={() => login()}
                className="px-4 py-2 rounded-xl font-medium transition-all duration-200 bg-primary-600 text-white hover:bg-primary-700 hover:shadow-lg"
              >
                Connect Wallet
              </button>
            ) : (
              <div className="flex items-center space-x-2">
                <div className="px-3 py-2 rounded-lg bg-success-100 dark:bg-success-900/20">
                  <span className="text-sm font-medium text-success-700 dark:text-success-400">
                    {username || 'Connected'}
                  </span>
                </div>
                <button
                  onClick={() => logout()}
                  className="px-3 py-2 rounded-lg font-medium transition-all duration-200 border border-gray-300 dark:border-dark-700 text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-dark-800"
                >
                  Disconnect
                </button>
              </div>
            )}

            <button
              onClick={toggleTheme}
              className="p-2 rounded-lg bg-gray-100 dark:bg-dark-800 text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100 transition-colors duration-200"
              title={theme === 'light' ? 'Switch to dark mode' : 'Switch to light mode'}
            >
              {theme === 'light' ? (
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" />
                </svg>
              ) : (
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z" />
                </svg>
              )}
            </button>
          </div>
        </div>
      </div>
    </header>
  );
};

export default Header;