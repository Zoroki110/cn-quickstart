// Canton Provider - Forces use of backend API only
import React, { createContext, useContext, useEffect, useState } from 'react';
import { backendApi } from '../services/backendApi';
import { cantonApi } from '../services/cantonApi';

interface CantonContextType {
  isInitialized: boolean;
  api: typeof cantonApi;
}

const CantonContext = createContext<CantonContextType>({
  isInitialized: false,
  api: cantonApi
});

export const useCanton = () => useContext(CantonContext);

export const CantonProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [isInitialized, setIsInitialized] = useState(false);

  useEffect(() => {
    // Initialize with backend API configuration
    const config = {
      ledgerApiUrl: process.env.REACT_APP_BACKEND_API_URL || 'https://nonexplicable-lacily-leesa.ngrok-free.dev',
      adminApiUrl: process.env.REACT_APP_BACKEND_API_URL || 'https://nonexplicable-lacily-leesa.ngrok-free.dev',
      participantId: 'app_user_participant'
    };

    cantonApi.initialize(config);
    setIsInitialized(true);
    
    console.log('Canton Provider: Using backend API at', config.ledgerApiUrl);
  }, []);

  return (
    <CantonContext.Provider value={{ isInitialized, api: cantonApi }}>
      {children}
    </CantonContext.Provider>
  );
};
