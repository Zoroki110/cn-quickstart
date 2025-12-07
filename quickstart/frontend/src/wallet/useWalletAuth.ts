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

export function useWalletAuth(): WalletAuthState {
  const [token, setToken] = useState<string | null>(null);
  const [partyId, setPartyId] = useState<string | null>(null);
  const [walletType, setWalletType] = useState<"loop" | "zoro" | "dev" | "unknown" | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const runAuthFlow = useCallback(async (connect: () => Promise<IWalletConnector>) => {
    setLoading(true);
    setError(null);

    try {
      const connector = await connect();
      const walletParty = await connector.getParty();
      const challenge = await requestChallenge(walletParty);
      const signature = await connector.signMessage(challenge.challenge);

      const verification = await verifyChallenge(connector, {
        challengeId: challenge.challengeId,
        partyId: walletParty,
        signature,
      });

      const connectorType = normalizeWalletType(connector.getType());
      setToken(verification.token);
      setPartyId(verification.partyId);
      setAuthToken(verification.token);
      setWalletType(connectorType);
      persistWalletSession({
        token: verification.token,
        partyId: verification.partyId,
        walletType: connectorType,
      });

      return verification;
    } catch (err) {
      const message =
        err instanceof Error ? err.message : "Wallet authentication failed";
      setError(message);
      throw err;
    } finally {
      setLoading(false);
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
    setToken(null);
    setPartyId(null);
    setWalletType(null);
    setAuthToken(null);
    clearWalletSession();
    setError(null);
  }, []);

  useEffect(() => {
    const session = loadWalletSession();
    if (session?.token && session.partyId) {
      setToken(session.token);
      setPartyId(session.partyId);
      setWalletType(normalizeWalletType(session.walletType));
      setAuthToken(session.token);
    }
  }, []);

  return {
    token,
    partyId,
    walletType,
    loading,
    error,
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
