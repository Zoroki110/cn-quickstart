export interface IWalletConnector {
  /**
   * Resolve the active Canton party identifier exposed by the wallet.
   */
  getParty(): Promise<string>;

  /**
   * Ask the wallet to sign an arbitrary message (UTF-8) for backend proof-of-possession.
   */
  signMessage(message: string): Promise<string>;

  /**
   * Identify the connector type so the backend can capture wallet metadata.
   */
  getType(): "loop" | "zoro" | "dev" | "unknown";
}

