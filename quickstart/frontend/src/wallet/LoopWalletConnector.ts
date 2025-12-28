import * as loopModule from "@fivenorth/loop-sdk";
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
const CONNECT_TIMEOUT_MS = 120000;

const sdkModule: any = loopModule as any;
const resolvedLoopApi: LoopApi = sdkModule.loop ?? sdkModule.Loop ?? sdkModule.default ?? sdkModule;

let loopApi: LoopApi | null = resolvedLoopApi;
let initDone = false;
let activeConnector: LoopWalletConnector | null = null;

export class LoopWalletConnector implements IWalletConnector {
  private provider: LoopProvider | null = null;
  private connectPromise: Promise<void> | null = null;
  private pendingResolve: (() => void) | null = null;
  private pendingReject: ((e: any) => void) | null = null;
  private pendingTimer: ReturnType<typeof setTimeout> | null = null;

  static initOnce(): void {
    if (initDone) return;
    if (DEBUG) console.debug("[Loop] init called");
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
    initDone = true;
  }

  async connect(): Promise<void> {
    if (this.provider) return;
    if (this.connectPromise) {
      await this.connectPromise;
      return;
    }
    activeConnector = this;
    if (!initDone) {
      LoopWalletConnector.initOnce();
    }
    this.ensureConnectPromise(false);
    await this.connectPromise;
  }

  /**
   * Should be called directly from the click handler (no async/await before calling).
   */
  connectFromClick(): Promise<void> {
    activeConnector = this;
    if (!initDone) {
      LoopWalletConnector.initOnce();
    }
    if (DEBUG) console.debug("[Loop] connect called in click stack at", Date.now());
    this.ensureConnectPromise(true);
    return this.connectPromise as Promise<void>;
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
      console.debug("[Loop] signMessage result type", typeof raw, "len", typeof normalized === "string" ? normalized.length : 0);
    return normalized;
  }

  private normalizeSignature(res: any): string {
    if (typeof res === "string") return res;
    if (res && typeof res.signature === "string") return res.signature;
    if (res && typeof res.sig === "string") return res.sig;
    if (res && typeof res.signedMessage === "string") return res.signedMessage;
    if (res && typeof res.signed_message === "string") return res.signed_message;
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

  async cleanupAfterFailure(): Promise<void> {
    await this.cleanupAndDisconnect(true);
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
    this.provider = provider;
    const partyId = provider.party_id ?? provider.partyId ?? provider.party;
    if (DEBUG) console.debug("[Loop] approved provider keys", Object.keys(provider || {}), "partyId", partyId);
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
    if (this.pendingResolve) this.pendingResolve();
    this.clearPending();
  }

  private handleReject(err: any) {
    if (this.pendingReject) {
      this.pendingReject(err);
    }
    void this.cleanupAndDisconnect(true);
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

  hasProvider(): boolean {
    return !!this.provider;
  }

  /**
   * Attempt to resume a session if the SDK has already stored loop_connect.
   * This should be called when the user returns focus from the Loop tab or when storage changes.
   */
  async tryRehydrateFromStorage(): Promise<boolean> {
    if (this.provider) return true;
    if (this.connectPromise) return true;
    if (typeof window === "undefined") return false;
    const stored = window.localStorage.getItem("loop_connect");
    if (!stored) return false;
    let parsed: any = null;
    try {
      parsed = JSON.parse(stored);
    } catch {
      return false;
    }
    const hasSession = !!(parsed && (parsed.partyId || parsed.party_id || parsed.party) && parsed.authToken);
    if (!hasSession) return false;
    if (DEBUG) console.debug("[Loop] tryRehydrateFromStorage found session, calling connect()");
    try {
      this.ensureConnectPromise(false);
      return true;
    } catch (err) {
      if (DEBUG) console.debug("[Loop] rehydrate failed", err);
      return false;
    }
  }

  private ensureConnectPromise(fromClick: boolean) {
    if (this.provider) return;
    if (this.connectPromise) return;
    const startTs = Date.now();
    this.connectPromise = new Promise<void>((resolve, reject) => {
      this.pendingResolve = resolve;
      this.pendingReject = reject;
      this.pendingTimer = setTimeout(() => {
        if (DEBUG) console.debug("[Loop] connect timeout");
        this.clearPending();
        void this.cleanupAndDisconnect(true);
        reject(new Error(`Loop connect timeout. ${POPUP_HINT}`));
      }, CONNECT_TIMEOUT_MS);
      try {
        if (DEBUG) console.debug("[Loop] calling loop.connect NOW", startTs, "fromClick", fromClick);
        loopApi?.connect?.();
      } catch (err) {
        this.clearPending();
        void this.cleanupAndDisconnect(true);
        reject(err);
      }
    });
  }

  private async cleanupAndDisconnect(clearSession = false) {
    this.provider = null;
    this.clearLoopStorage(clearSession);
    try {
      await loopApi?.disconnect?.();
    } catch {
      // ignore
    }
  }

  private clearLoopStorage(clearSession: boolean) {
    try {
      if (typeof window !== "undefined") {
        window.localStorage.removeItem("loop_connect");
        if (clearSession) {
          window.localStorage.removeItem("clearportx.wallet.session");
        }
      }
    } catch {
      // ignore storage errors
    }
  }
}

// Netlify redeploy marker: do not remove. This forces a fresh build.
