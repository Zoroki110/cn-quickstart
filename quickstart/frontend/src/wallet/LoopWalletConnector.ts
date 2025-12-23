import { IWalletConnector } from "./IWalletConnector";

type LoopProvider = {
  party_id?: string;
  partyId?: string;
  party?: string;
  public_key?: string;
  publicKey?: string;
  signMessage?: (message: string) => Promise<string>;
  getHolding?: () => Promise<any>;
  getAccount?: () => Promise<any>;
};

type LoopApi = {
  init?: (args: {
    appName: string;
    network: string;
    options?: { openMode?: "popup" | "tab"; redirectUrl?: string; requestSigningMode?: "popup" | "tab" };
    onAccept?: (provider: LoopProvider) => void;
    onReject?: () => void;
  }) => void;
  connect?: () => void;
};

const DEBUG = typeof window !== "undefined" && localStorage.getItem("clearportx.debug.loop") === "1";
const POPUP_HINT = "Allow popups for this site.";

let loopApi: LoopApi | null = null;
let initPromise: Promise<void> | null = null;
let activeConnector: LoopWalletConnector | null = null;

export class LoopWalletConnector implements IWalletConnector {
  private provider: LoopProvider | null = null;
  private connectPromise: Promise<void> | null = null;
  private pendingResolve: (() => void) | null = null;
  private pendingReject: ((e: any) => void) | null = null;
  private pendingTimer: ReturnType<typeof setTimeout> | null = null;

  private static async ensureInit(): Promise<void> {
    if (initPromise) return initPromise;
    initPromise = (async () => {
      if (DEBUG) console.debug("[Loop] init called");
      const sdkModule: any = await import("@fivenorth/loop-sdk");
      loopApi =
        (sdkModule as any).loop ??
        (sdkModule as any).Loop ??
        (sdkModule as any).default ??
        sdkModule;
      if (!loopApi || typeof loopApi.init !== "function" || typeof loopApi.connect !== "function") {
        throw new Error("Loop SDK is unavailable (missing init/connect)");
      }
      loopApi.init({
        appName: "ClearportX",
        network: "devnet",
        options: {
          openMode: "popup",
          requestSigningMode: "popup",
          redirectUrl: typeof window !== "undefined" ? window.location.origin : undefined,
        },
        onAccept: (provider: LoopProvider) => {
          if (DEBUG) console.debug("[Loop] onAccept keys", Object.keys(provider || {}));
          activeConnector?.handleAccept(provider);
        },
        onReject: () => {
          if (DEBUG) console.debug("[Loop] onReject");
          activeConnector?.handleReject(new Error("Loop connection rejected"));
        },
      });
    })();
    return initPromise;
  }

  async connect(): Promise<void> {
    if (this.provider) return;
    if (this.connectPromise) {
      await this.connectPromise;
      return;
    }

    activeConnector = this;
    await LoopWalletConnector.ensureInit();

    if (DEBUG) console.debug("[Loop] connect called");

    this.connectPromise = new Promise<void>((resolve, reject) => {
      this.pendingResolve = resolve;
      this.pendingReject = reject;
      this.pendingTimer = setTimeout(() => {
        if (DEBUG) console.debug("[Loop] connect timeout");
        this.clearPending();
        reject(new Error(`Loop connect timeout. ${POPUP_HINT}`));
      }, 90000);

      try {
        loopApi?.connect?.();
      } catch (err) {
        this.clearPending();
        reject(err);
      }
    });

    await this.connectPromise;
  }

  async getParty(): Promise<string> {
    await this.connect();
    const party = this.provider?.party_id ?? this.provider?.partyId ?? this.provider?.party;
    if (!party) {
      throw new Error("Loop connect succeeded but partyId is missing. Allow popups and retry.");
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

  async getHoldings(): Promise<any> {
    await this.ensureConnected();
    const gh = this.provider?.getHolding;
    if (typeof gh !== "function") {
      throw new Error("Loop provider does not expose getHolding()");
    }
    return gh.call(this.provider);
  }

  getProvider(): LoopProvider | null {
    return this.provider;
  }

  getType(): "loop" | "zoro" | "dev" | "unknown" {
    return "loop";
  }

  private async ensureConnected() {
    if (this.provider) return;
    await this.connect();
    if (!this.provider) {
      console.warn("Loop provider missing after connect()");
      throw new Error("Loop wallet did not provide a provider after connect()");
    }
  }

  private async handleAccept(provider: LoopProvider) {
    this.provider = provider; // store raw provider

    const checker = provider.getAccount ?? provider.getHolding;
    if (typeof checker === "function") {
      try {
        await checker.call(provider);
        if (DEBUG) console.debug("[Loop] sanity check passed");
      } catch (err) {
        if (DEBUG) console.debug("[Loop] sanity check failed", err);
        this.provider = null;
        this.handleReject(err);
        return;
      }
    }

    if (DEBUG) {
      const hasSign = typeof provider.signMessage === "function";
      const hasHold = typeof provider.getHolding === "function";
      console.debug("[Loop] provider has signMessage:", hasSign, "getHolding:", hasHold);
    }

    if (this.pendingResolve) {
      this.pendingResolve();
    }
    this.clearPending();
  }

  private handleReject(err: any) {
    if (this.pendingReject) {
      this.pendingReject(err);
    }
    this.clearPending();
  }

  private clearPending() {
    if (this.pendingTimer) {
      clearTimeout(this.pendingTimer);
    }
    this.pendingTimer = null;
    this.pendingResolve = null;
    this.pendingReject = null;
    this.connectPromise = null;
  }
}

// Netlify redeploy marker: do not remove. This forces a fresh build.
