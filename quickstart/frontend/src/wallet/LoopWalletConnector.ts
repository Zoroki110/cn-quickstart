import { IWalletConnector } from "./IWalletConnector";

type LoopProvider = {
  party_id?: string;
  partyId?: string;
  party?: string;
  public_key?: string;
  publicKey?: string;
  signMessage?: (message: string) => Promise<any>;
  getHolding?: () => Promise<any>;
  getAccount?: () => Promise<any>;
  getActiveContracts?: (args: any) => Promise<any>;
  submitTransaction?: (cmd: any, opts?: any) => Promise<any>;
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
  disconnect?: () => Promise<void> | void;
};

const DEBUG = typeof window !== "undefined" && localStorage.getItem("clearportx.debug.loop") === "1";
const POPUP_HINT = "Allow popups for this site.";
const CONNECT_TIMEOUT_MS = 30000;

let loopApi: LoopApi | null = null;
let initPromise: Promise<void> | null = null;
let activeConnector: LoopWalletConnector | null = null;

export class LoopWalletConnector implements IWalletConnector {
  private provider: LoopProvider | null = null;
  private connectPromise: Promise<void> | null = null;
  private pendingResolve: (() => void) | null = null;
  private pendingReject: ((e: any) => void) | null = null;
  private pendingTimer: ReturnType<typeof setTimeout> | null = null;

  static async initOnce(): Promise<void> {
    return this.ensureInit();
  }

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
          if (DEBUG)
            console.debug(
              "[Loop] onAccept keys",
              Object.keys(provider || {}),
              "signMessage?",
              typeof provider?.signMessage,
              "getHolding?",
              typeof provider?.getHolding
            );
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
    if (!loopApi) {
      await LoopWalletConnector.ensureInit();
    }

    if (DEBUG) console.debug("[Loop] connect called");

    await this.ensureConnectPromise();
  }

  /**
   * Must be called directly from the user click handler to keep popup in the same call stack.
   */
  async startConnectFromClick(): Promise<void> {
    activeConnector = this;
    const preflight =
      typeof window !== "undefined"
        ? window.open("", "clearportx_loop_preflight", "popup,width=420,height=720")
        : null;
    const blocked = !preflight;
    if (preflight) {
      try {
        preflight.close();
      } catch {
        // ignore
      }
    }
    if (blocked) {
      throw new Error("Popup blocked — allow popups for this site, then retry.");
    }
    if (!loopApi) {
      throw new Error("Loop SDK not initialized yet");
    }
    await this.ensureConnectPromise();
  }

  async getParty(): Promise<string> {
    await this.connect();
    if (DEBUG) console.debug("[Loop] provider ready (getParty)");
    const party = this.provider?.party_id ?? this.provider?.partyId ?? this.provider?.party;
    if (!party) {
      throw new Error("Loop connect succeeded but partyId is missing. Allow popups and retry.");
    }
    return party;
  }

  // Wrapper helpers (no destructuring, keep binding)
  async getPartyId(): Promise<string> {
    return this.getParty();
  }

  async signMessage(message: string): Promise<string> {
    await this.ensureConnected();
    if (DEBUG) console.debug("[Loop] provider ready (signMessage)");
    const signer = this.provider?.signMessage;
    if (!signer) {
      throw new Error("Loop wallet does not expose signMessage()");
    }
    const raw = await signer.call(this.provider, message);
    const normalized = this.normalizeSignature(raw);
    if (DEBUG)
      console.debug(
        "[Loop] signMessage result type",
        typeof raw,
        "len",
        typeof normalized === "string" ? normalized.length : 0
      );
    return normalized;
  }

  private normalizeSignature(res: any): string {
    if (typeof res === "string") return res;
    if (res && typeof res.signature === "string") return res.signature;
    if (res && typeof res.sig === "string") return res.sig;
    if (res && typeof res.signature?.hex === "string") return res.signature.hex;
    if (res && typeof res.signature?.value === "string") return res.signature.value;
    throw new Error("Unsupported Loop signMessage response: " + JSON.stringify(Object.keys(res || {})));
  }

  async getHoldings(): Promise<any> {
    await this.ensureConnected();
    if (DEBUG) console.debug("[Loop] provider ready (getHoldings)");
    const gh = this.provider?.getHolding;
    if (typeof gh !== "function") {
      throw new Error("Loop provider does not expose getHolding()");
    }
    return gh.call(this.provider);
  }

  async getAccount(): Promise<any> {
    await this.ensureConnected();
    if (DEBUG) console.debug("[Loop] provider ready (getAccount)");
    const ga = this.provider?.getAccount;
    if (typeof ga !== "function") {
      throw new Error("Loop provider does not expose getAccount()");
    }
    return ga.call(this.provider);
  }

  async getActiveContracts(args: any): Promise<any> {
    await this.ensureConnected();
    if (DEBUG) console.debug("[Loop] provider ready (getActiveContracts)");
    const gac = this.provider?.getActiveContracts;
    if (typeof gac !== "function") {
      throw new Error("Loop provider does not expose getActiveContracts()");
    }
    return gac.call(this.provider, args);
  }

  async submitTransaction(cmd: any, opts?: any): Promise<any> {
    await this.ensureConnected();
    if (DEBUG) console.debug("[Loop] provider ready (submitTransaction)");
    const st = this.provider?.submitTransaction;
    if (typeof st !== "function") {
      throw new Error("Loop provider does not expose submitTransaction()");
    }
    return st.call(this.provider, cmd, opts);
  }

  async disconnect(): Promise<void> {
    await this.cleanupAndDisconnect();
    this.clearPending();
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

    if (this.pendingResolve) {
      this.pendingResolve();
    }
    this.clearPending();
  }

  private handleReject(err: any) {
    if (this.pendingReject) {
      this.pendingReject(err);
    }
    void this.cleanupAndDisconnect();
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

  private async ensureConnectPromise(): Promise<void> {
    if (this.provider) return;
    if (this.connectPromise) {
      await this.connectPromise;
      return;
    }
    this.connectPromise = new Promise<void>((resolve, reject) => {
      this.pendingResolve = resolve;
      this.pendingReject = reject;
      this.pendingTimer = setTimeout(() => {
        if (DEBUG) console.debug("[Loop] connect timeout");
        this.clearPending();
        void this.cleanupAndDisconnect();
        reject(new Error(`Loop connect timeout. ${POPUP_HINT}`));
      }, CONNECT_TIMEOUT_MS);

      try {
        loopApi?.connect?.();
      } catch (err) {
        this.clearPending();
        void this.cleanupAndDisconnect();
        reject(err);
      }
    });
    await this.connectPromise;
  }

  private async cleanupAndDisconnect() {
    this.provider = null;
    this.clearLoopStorage();
    try {
      await loopApi?.disconnect?.();
    } catch {
      // ignore disconnect errors
    }
  }

  private clearLoopStorage() {
    try {
      if (typeof window !== "undefined") {
        window.localStorage.removeItem("loop_connect");
      }
    } catch {
      // ignore storage errors
    }
  }
}

// Netlify redeploy marker: do not remove. This forces a fresh build.
