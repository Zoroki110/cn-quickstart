// ClearportX Login Modal
// DEPRECATED: Now using OAuth redirect flow with Keycloak (see Header.tsx)
// This file is kept only for backward compatibility but is not used anymore

import React from 'react';

interface LoginModalProps {
  onClose: () => void;
}

const LoginModal: React.FC<LoginModalProps> = ({ onClose }) => {
  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white dark:bg-dark-900 p-6 rounded-xl w-full max-w-sm shadow-2xl">
        <h2 className="text-2xl font-bold mb-4 text-gray-900 dark:text-gray-100">
          Login Deprecated
        </h2>
        <p className="text-sm mb-4 text-gray-600 dark:text-gray-400">
          This modal is no longer used. Please use the "Connect Wallet" button in the header
          which will redirect you to Keycloak for OAuth login.
        </p>
        <button
          className="w-full px-4 py-2 rounded-lg bg-primary-600 text-white hover:bg-primary-700"
          onClick={onClose}
        >
          Close
        </button>
      </div>
    </div>
  );
};

export default LoginModal;
