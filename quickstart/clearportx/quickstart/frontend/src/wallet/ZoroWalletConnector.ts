import { IWalletConnector } from "./IWalletConnector";

type ZoroWalletClient = {
  connect?: () => Promise<void>;
  init?: () => Promise<void>;
  getParty?: () => Promise<string>;
  getPartyId?: () => Promise<string>;
  signMessage?: (message: string) => Promise<string>;
};

/**
 * Light wrapper around the (future) Zoro wallet API.
 * Falls back to `window.zoroWallet` or `window.zoro` if present.
 */
export class ZoroWalletConnector implements IWalletConnector {
  private clientPromise?: Promise<ZoroWalletClient>;

  private ensureClient(): Promise<ZoroWalletClient> {
    if (this.clientPromise) {
      return this.clientPromise;
    }

    this.clientPromise = new Promise(async (resolve, reject) => {
      if (typeof window === "undefined") {
        reject(new Error("Zoro wallet can only run inside a browser context."));
        return;
      }

      const provider = (window as any).zoroWallet ?? (window as any).zoro;
      if (!provider) {
        reject(
          new Error("Zoro wallet extension not detected. Please install or enable it.")
        );
        return;
      }

      try {
        if (typeof provider.connect === "function") {
          await provider.connect();
        } else if (typeof provider.init === "function") {
          await provider.init();
        }
        resolve(provider as ZoroWalletClient);
      } catch (err) {
        reject(
          new Error(
            err instanceof Error
              ? err.message
              : "Unable to initialize the Zoro wallet connector."
          )
        );
      }
    });

    return this.clientPromise;
  }

  async getParty(): Promise<string> {
    const provider = await this.ensureClient();
    const party =
      (await provider.getPartyId?.()) ?? (await provider.getParty?.());
    if (!party) {
      throw new Error("Zoro wallet did not return a Canton party identifier.");
    }
    return party.trim();
  }

  async signMessage(message: string): Promise<string> {
    const provider = await this.ensureClient();
    if (!provider.signMessage) {
      throw new Error("Zoro wallet does not expose a signMessage API.");
    }
    const signature = await provider.signMessage(message);
    if (!signature) {
      throw new Error("Zoro wallet returned an empty signature.");
    }
    return signature;
  }

  getType(): "zoro" {
    return "zoro";
  }
}

