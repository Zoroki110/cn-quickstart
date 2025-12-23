import { IWalletConnector } from "./IWalletConnector";

type LoopProvider = {
  party_id?: string;
  partyId?: string;
  party?: string;
  connection?: { partyId?: string; party?: string };
  authToken?: string;
  signMessage?: (message: string) => Promise<string>;
  getHolding?: () => Promise<Array<Record<string, unknown>>>;
  getActiveContracts?: (args: { interfaceId?: string; templateId?: string }) => Promise<any[]>;
};

type LoopApi = {
  init?: (args: {
    appName: string;
    network: string;
    options?: { openMode?: "popup" | "tab"; redirectUrl?: string; requestSigningMode?: "popup" | "tab" };
    onAccept?: (provider: LoopProvider) => void;
    onReject?: () => void;
  }) => void;
  connect?: () => Promise<unknown>;
};

export class LoopWalletConnector implements IWalletConnector {
  private static readonly DEBUG = false;

  private loopApi: LoopApi | null = null;
  private provider: LoopProvider | null = null;
  private initPromise?: Promise<void>;
  private connectWaiters: Array<{ resolve: (p: LoopProvider | null) => void; reject: (err: any) => void }> = [];

  async connect(): Promise<void> {
    await this.ensureInitialized();
    if (this.provider) return;
    const api = this.loopApi;
    const connectFn = api?.connect;
    if (!api || typeof connectFn !== "function") {
      throw new Error("Loop SDK connect() is not available.");
    }
    if (LoopWalletConnector.DEBUG) console.debug("[Loop] connect start");
    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => {
        reject(new Error("Loop connect timeout"));
      }, 120000);
      this.connectWaiters.push({
        resolve: (p) => {
          clearTimeout(timeout);
          if (!p) {
            reject(new Error("Loop provider not returned"));
          } else {
            resolve();
          }
        },
        reject: (err) => {
          clearTimeout(timeout);
          reject(err);
        },
      });
      try {
        const res = connectFn();
        if (res && typeof (res as any).then === "function") {
          (res as Promise<any>)
            .then((maybeProvider) => {
              if (maybeProvider && typeof maybeProvider === "object") {
                this.provider = maybeProvider as LoopProvider;
                this.connectWaiters.forEach((p) => p.resolve(this.provider));
                this.connectWaiters = [];
              }
            })
            .catch((err) => {
              clearTimeout(timeout);
              reject(err);
            });
        } else if (res && typeof res === "object") {
          this.provider = res as LoopProvider;
          this.connectWaiters.forEach((p) => p.resolve(this.provider));
          this.connectWaiters = [];
          clearTimeout(timeout);
          resolve();
          return;
        }
        if (res && typeof (res as Promise<unknown>).catch === "function") {
          (res as Promise<unknown>).catch((err) => {
            clearTimeout(timeout);
            reject(err);
          });
        }
      } catch (err) {
        clearTimeout(timeout);
        reject(err);
      }
    });
  }

  async getParty(): Promise<string> {
    await this.ensureConnected();
    const prov: any = this.provider;
    const party =
      prov?.party_id ??
      prov?.partyId ??
      prov?.party ??
      prov?.connection?.partyId ??
      prov?.connection?.party;
    if (!party) {
      if (LoopWalletConnector.DEBUG) console.debug("[Loop] provider missing partyId", prov);
      throw new Error("Loop connect succeeded but partyId is missing (provider.connection undefined). Check popup blocked or SDK API mismatch.");
    }
    return party;
  }

  async signMessage(message: string): Promise<string> {
    await this.ensureConnected();
    const signer = this.provider?.signMessage;
    if (!signer) {
      throw new Error("Loop wallet does not expose signMessage()");
    }
    const signature = await signer(message);
    if (!signature) {
      throw new Error("Loop wallet returned an empty signature");
    }
    return signature;
  }

  getProvider(): LoopProvider | null {
    return this.provider;
  }

  getType(): "loop" | "zoro" | "dev" | "unknown" {
    return "loop";
  }

  private async ensureConnected() {
    await this.ensureInitialized();
    if (this.provider) return;
    await this.connect();
    if (!this.provider) {
      console.warn("Loop provider missing after connect()");
      throw new Error("Loop wallet did not provide a provider after connect()");
    }
  }

  private async ensureInitialized(): Promise<void> {
    if (this.initPromise) {
      return this.initPromise;
    }
    this.initPromise = this.loadAndInitSdk();
    return this.initPromise;
  }

  private async loadAndInitSdk(): Promise<void> {
    const sdkModule = await import("@fivenorth/loop-sdk");
    const loopApi: LoopApi =
      (sdkModule as any).loop ??
      (sdkModule as any).Loop ??
      (sdkModule as any).default ??
      sdkModule;

    this.loopApi = loopApi;

    if (!loopApi || typeof loopApi.init !== "function" || typeof loopApi.connect !== "function") {
      throw new Error("Loop SDK is unavailable (missing init/connect)");
    }

    const capture = (provider: LoopProvider) => {
      this.provider = provider;
      this.connectWaiters.forEach((p) => p.resolve(provider));
      this.connectWaiters = [];
      if (LoopWalletConnector.DEBUG) console.debug("[Loop] onAccept provider", provider);
    };

    loopApi.init({
      appName: "ClearportX",
      network: "devnet",
      options: {
        openMode: "popup",
        requestSigningMode: "popup",
      },
      onAccept: capture,
      onReject: () => {
        this.provider = null;
        this.connectWaiters.forEach((p) => p.reject(new Error("Loop connection rejected by user")));
        this.connectWaiters = [];
        if (LoopWalletConnector.DEBUG) console.debug("[Loop] onReject");
      },
    });
  }
}


// Netlify redeploy marker: do not remove. This forces a fresh build.
