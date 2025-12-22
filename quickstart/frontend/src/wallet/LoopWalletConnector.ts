import { IWalletConnector } from "./IWalletConnector";

type LoopProvider = {
  party_id?: string;
  authToken?: string;
  signMessage?: (message: string) => Promise<string>;
  getHolding?: () => Promise<Array<Record<string, unknown>>>;
  getActiveContracts?: (args: { interfaceId?: string; templateId?: string }) => Promise<any[]>;
};

type LoopApi = {
  init?: (args: {
    appName: string;
    network: string;
    options?: { openMode?: "popup" | "tab"; redirectUrl?: string };
    onAccept?: (provider: LoopProvider) => void;
    onReject?: () => void;
  }) => void;
  connect?: () => Promise<unknown>;
};

export class LoopWalletConnector implements IWalletConnector {
  private loopApi: LoopApi | null = null;
  private provider: LoopProvider | null = null;
  private initPromise?: Promise<void>;
  private connectWaiters: Array<(p: LoopProvider) => void> = [];

  async connect(): Promise<void> {
    await this.ensureInitialized();
    if (this.provider) return;
    const api = this.loopApi;
    const connectFn = api?.connect;
    if (!api || typeof connectFn !== "function") {
      throw new Error("Loop SDK connect() is not available.");
    }
    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => {
        reject(new Error("Loop connect timeout"));
      }, 120000);
      this.connectWaiters.push((p) => {
        clearTimeout(timeout);
        resolve();
      });
      try {
        const res = connectFn();
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
    const party = this.provider?.party_id;
    if (!party) {
      throw new Error("Loop wallet returned an empty party identifier");
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

    const capture = (provider: LoopProvider) => {
      this.provider = provider;
      this.connectWaiters.forEach((fn) => fn(provider));
      this.connectWaiters = [];
    };

    if (typeof loopApi.init === "function") {
      loopApi.init({
        appName: "ClearportX",
        network: "devnet",
        options: {
          openMode: "popup",
        },
        onAccept: capture,
        onReject: () => {
          this.connectWaiters.forEach((fn) =>
            fn({
              party_id: undefined,
            })
          );
          this.connectWaiters = [];
        },
      });
    }
  }
}
