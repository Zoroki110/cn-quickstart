import type { PriceResponse } from '../types/canton';
import { BUILD_INFO } from '../config/build-info';

const runtimeBackendUrl = (typeof window !== 'undefined' && (window as any).__BACKEND_URL__) || undefined;
const BACKEND_URL =
  process.env.REACT_APP_BACKEND_API_URL ||
  BUILD_INFO?.features?.backendUrl ||
  runtimeBackendUrl ||
  'http://localhost:8080';

export async function fetchPrices(symbols: string[]): Promise<PriceResponse> {
  const query = symbols.length ? `?symbols=${encodeURIComponent(symbols.join(','))}` : '';
  const res = await fetch(`${BACKEND_URL}/api/prices${query}`, {
    method: 'GET',
    headers: {
      'Content-Type': 'application/json',
      'ngrok-skip-browser-warning': 'true',
    },
  });
  if (!res.ok) {
    throw new Error(`Price API error: ${res.status}`);
  }
  return res.json();
}

