import { useCallback, useEffect, useState } from "react";
import { walletManager } from "./WalletManager";
import type { IWalletConnector } from "./IWalletConnector";
import { apiPostJson, clearWalletSession, loadWalletSession, persistWalletSession, setAuthToken } from "../api/client";

type ChallengeResponse = {
  challengeId: string;
  challenge: string;
  expiresAt: string;
};

type VerifyResponse = {
  token: string;
  partyId: string;
};

export interface WalletAuthState {
  token: string | null;
  partyId: string | null;
  walletType: "loop" | "zoro" | "dev" | "unknown" | null;
  loading: boolean;
  error: string | null;
  authenticateWithLoop: () => Promise<VerifyResponse>;
  authenticateWithDev: () => Promise<VerifyResponse>;
  authenticateWithZoro: () => Promise<VerifyResponse>;
  disconnect: () => void;
}

type AuthInternalState = {
  token: string | null;
  partyId: string | null;
  walletType: "loop" | "zoro" | "dev" | "unknown" | null;
  loading: boolean;
  error: string | null;
};

const initialAuthState: AuthInternalState = {
  token: null,
  partyId: null,
  walletType: null,
  loading: false,
  error: null,
};

type AuthListener = (state: AuthInternalState) => void;

let authState: AuthInternalState = initialAuthState;
const authListeners = new Set<AuthListener>();
let authHydratedFromSession = false;

export function useWalletAuth(): WalletAuthState {
  hydrateAuthStateFromSession();

  const [state, setState] = useState<AuthInternalState>(authState);
  const [loopRehydrateTried, setLoopRehydrateTried] = useState(false);

  useEffect(() => {
    const listener: AuthListener = (next) => setState(next);
    authListeners.add(listener);
    return () => {
      authListeners.delete(listener);
    };
  }, []);

  // Rehydrate Loop provider (SDK can reuse cached session without QR).
  useEffect(() => {
    if (state.walletType === "loop" && !loopRehydrateTried) {
      setLoopRehydrateTried(true);
      walletManager
        .connectLoop()
        .catch((err) => console.warn("Loop rehydrate failed", err));
    }
  }, [state.walletType, loopRehydrateTried]);

  const runAuthFlow = useCallback(async (connect: () => Promise<IWalletConnector>) => {
    updateAuthState({ loading: true, error: null });

    try {
      const connector = await connect();
      if (typeof connector.connect === "function") {
        await connector.connect();
      }
      const walletParty = await connector.getParty();
      const challenge = await requestChallenge(walletParty);
      const signature = await connector.signMessage(challenge.challenge);

      const verification = await verifyChallenge(connector, {
        challengeId: challenge.challengeId,
        partyId: walletParty,
        signature,
      });

      const connectorType = normalizeWalletType(connector.getType());
      setAuthToken(verification.token);
      persistWalletSession({
        token: verification.token,
        partyId: verification.partyId,
        walletType: connectorType,
      });
      updateAuthState({
        token: verification.token,
        partyId: verification.partyId,
        walletType: connectorType,
      });

      if (typeof window !== "undefined") {
        window.dispatchEvent(
          new CustomEvent("clearportx:wallet:connected", {
            detail: { partyId: verification.partyId, walletType: connectorType },
          })
        );
      }

      return verification;
    } catch (err) {
      const message = err instanceof Error ? err.message : "Wallet authentication failed";
      updateAuthState({ error: message });
      throw err;
    } finally {
      updateAuthState({ loading: false });
    }
  }, []);

  const authenticateWithLoop = useCallback(
    () => runAuthFlow(() => walletManager.connectLoop()),
    [runAuthFlow]
  );

  const authenticateWithDev = useCallback(
    () => runAuthFlow(() => walletManager.connectDev()),
    [runAuthFlow]
  );

  const authenticateWithZoro = useCallback(
    () => runAuthFlow(() => walletManager.connectZoro()),
    [runAuthFlow]
  );

  const disconnect = useCallback(() => {
    clearWalletSession();
    setAuthToken(null);
    updateAuthState({
      token: null,
      partyId: null,
      walletType: null,
      error: null,
    });
  }, []);

  return {
    token: state.token,
    partyId: state.partyId,
    walletType: state.walletType,
    loading: state.loading,
    error: state.error,
    authenticateWithLoop,
    authenticateWithDev,
    authenticateWithZoro,
    disconnect,
  };
}

async function requestChallenge(partyId: string): Promise<ChallengeResponse> {
  return apiPostJson<ChallengeResponse>("/api/auth/challenge", { partyId });
}

async function verifyChallenge(
  connector: IWalletConnector,
  params: { challengeId: string; partyId: string; signature: string }
): Promise<VerifyResponse> {
  return apiPostJson<VerifyResponse>("/api/auth/verify", {
    ...params,
    walletType: connector.getType(),
  });
}

function normalizeWalletType(value: string | null | undefined): "loop" | "zoro" | "dev" | "unknown" {
  if (value === "loop" || value === "zoro" || value === "dev" || value === "unknown") {
    return value;
  }
  return "unknown";
}

function hydrateAuthStateFromSession() {
  if (authHydratedFromSession) {
    return;
  }
  authHydratedFromSession = true;
  const session = loadWalletSession();
  if (session?.token && session.partyId) {
    setAuthToken(session.token);
    authState = {
      ...authState,
      token: session.token,
      partyId: session.partyId,
      walletType: normalizeWalletType(session.walletType),
    };
  }
}

function updateAuthState(patch: Partial<AuthInternalState>) {
  authState = { ...authState, ...patch };
  authListeners.forEach((listener) => listener(authState));
}
