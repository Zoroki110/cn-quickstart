import React from 'react';

const ConnectionStatus: React.FC = () => {
  // Disabled for DevNet - backend health check handles connectivity
  // No need to show connection warnings when using ngrok/public backend
  return null;
};

export default ConnectionStatus;
