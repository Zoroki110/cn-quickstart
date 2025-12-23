import { IWalletConnector } from "./IWalletConnector";

// Types from Loop SDK (provider shape)
type LoopProvider = {
  party_id?: string;
  partyId?: string;
  party?: string;
  public_key?: string;
  publicKey?: string;
  signMessage?: (message: string) => Promise<string>;
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

// Debug toggle via localStorage: clearportx.debug.loop = "1"
const DEBUG_LOOP = typeof window !== "undefined" && localStorage.getItem("clearportx.debug.loop") === "1";
const POPUP_HINT = "Allow popups for this site.";

// Shared singleton state for the SDK
let loopApi: LoopApi | null = null;
let initPromise: Promise<void> | null = null;
let activeConnector: LoopWalletConnector | null = null;

export class LoopWalletConnector implements IWalletConnector {
  private provider: LoopProvider | null = null;
  private connectPromise: Promise<void> | null = null;
  private pendingResolve: ((v: { partyId: string; publicKey?: string }) => void) | null = null;
  private pendingReject: ((e: any) => void) | null = null;
  private pendingTimer: ReturnType<typeof setTimeout> | null = null;

  private static async ensureInit(): Promise<void> {
    if (initPromise) return initPromise;

    initPromise = (async () => {
      if (DEBUG_LOOP) console.debug("[Loop] init start");
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
          if (DEBUG_LOOP) console.debug("[Loop] onAccept provider keys:", Object.keys(provider || {}));
          activeConnector?.handleAccept(provider);
        },
        onReject: () => {
          if (DEBUG_LOOP) console.debug("[Loop] onReject");
          activeConnector?.handleReject(new Error("Loop connection rejected"));
        },
      });
      if (DEBUG_LOOP) console.debug("[Loop] init done");
    })();

    return initPromise;
  }

  async connect(): Promise<void> {
    // If already connected, return cached
    if (this.provider) {
      return;
    }

    if (this.connectPromise) {
      await this.connectPromise;
      return;
    }

    activeConnector = this;
    await LoopWalletConnector.ensureInit();

    if (DEBUG_LOOP) console.debug("[Loop] connect click");

    this.connectPromise = new Promise<void>((resolve, reject) => {
      this.pendingResolve = () => resolve();
      this.pendingReject = (e) => reject(e);
      this.pendingTimer = setTimeout(() => {
        if (DEBUG_LOOP) console.debug("[Loop] connect timeout");
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
    return;
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

  private handleAccept(provider: LoopProvider) {
    this.provider = provider;
    const partyId = provider.party_id ?? provider.partyId ?? provider.party;
    const publicKey = provider.public_key ?? (provider as any).publicKey;

    if (this.pendingResolve) {
      this.pendingResolve({ partyId: partyId || "", publicKey });
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
