/* Minimal vendored Zoro SDK based on provided docs and analyzed demo bundle. */

export enum SignRequestResponseType {
  SIGN_REQUEST_APPROVED = "sign_request_approved",
  SIGN_REQUEST_REJECTED = "sign_request_rejected",
  SIGN_REQUEST_ERROR = "sign_request_error",
}

export type SignRequestResponse = {
  type: SignRequestResponseType;
  data: {
    signature?: string;
    reason?: string;
    error?: string;
    updateId?: string;
  };
};

export type ZoroInitOptions = {
  appName: string;
  iconUrl?: string;
  network?: "mainnet" | "local";
  walletUrl?: string;
  apiUrl?: string;
  onAccept?: (wallet: any) => void;
  onReject?: () => void;
  onDisconnect?: () => void;
};

type StoredSession = {
  sessionId?: string;
  ticketId?: string;
  authToken?: string;
  partyId?: string;
  publicKey?: string;
};

type RequestCallback = (resp: SignRequestResponse) => void;

class ZoroWallet {
  private authToken: string;
  private partyId: string;
  private publicKey: string;
  private apiUrl: string;
  private ws: WebSocket | null;
  private callbacks: Map<string, RequestCallback>;

  constructor(params: { authToken: string; partyId: string; publicKey: string; apiUrl: string; ws: WebSocket | null }) {
    this.authToken = params.authToken;
    this.partyId = params.partyId;
    this.publicKey = params.publicKey;
    this.apiUrl = params.apiUrl;
    this.ws = params.ws;
    this.callbacks = new Map();
  }

  getPartyId(): string {
    return this.partyId;
  }

  getPublicKey(): string {
    return this.publicKey;
  }

  async getHoldingUtxos(): Promise<any> {
    const res = await fetch(`${this.apiUrl}/api/v1/connect/wallet/holding-utxos`, {
      method: "GET",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${this.authToken}`,
      },
    });
    if (!res.ok) {
      throw new Error("Failed to get holding utxos.");
    }
    return res.json();
  }

  signMessage(message: string, cb: (resp: SignRequestResponse) => void): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      cb({
        type: SignRequestResponseType.SIGN_REQUEST_ERROR,
        data: { error: "Not connected" },
      });
      return;
    }
    const requestId = cryptoRandomId();
    this.callbacks.set(requestId, cb);
    const payload = {
      requestId,
      type: "sign_request",
      data: {
        requestType: "sign_raw_message",
        payload: { message },
      },
    };
    this.ws.send(JSON.stringify(payload));
  }

  handleResponse(msg: any) {
    const requestId = msg?.requestId;
    if (!requestId || !this.callbacks.has(requestId)) return;
    const cb = this.callbacks.get(requestId)!;
    const type = msg?.type;
    if (type === SignRequestResponseType.SIGN_REQUEST_APPROVED) {
      cb({ type, data: { signature: msg?.data?.signature, updateId: msg?.data?.updateId } });
    } else if (type === SignRequestResponseType.SIGN_REQUEST_REJECTED) {
      cb({ type, data: { reason: msg?.data?.reason, updateId: msg?.data?.updateId } });
    } else if (type === SignRequestResponseType.SIGN_REQUEST_ERROR) {
      cb({ type, data: { error: msg?.data?.error, updateId: msg?.data?.updateId } });
    }
    this.callbacks.delete(requestId);
  }

  disconnect() {
    this.callbacks.clear();
    try {
      this.ws?.close();
    } catch {
      /* ignore */
    }
  }
}

class ZoroSDK {
  private opts: ZoroInitOptions | null = null;
  private initialized = false;
  private ws: WebSocket | null = null;

  init(opts: ZoroInitOptions) {
    if (this.initialized) return;
    this.opts = opts;
    this.initialized = true;
  }

  async connect(): Promise<void> {
    if (typeof window === "undefined") {
      throw new Error("ZoroSDK.connect() can only be called in a browser environment.");
    }
    if (!this.initialized || !this.opts) {
      throw new Error("SDK not initialized. Call init() first.");
    }

    const stored = this.readStoredSession();
    if (stored && stored.authToken && stored.partyId && stored.publicKey) {
      const wallet = new ZoroWallet({
        authToken: stored.authToken,
        partyId: stored.partyId,
        publicKey: stored.publicKey,
        apiUrl: this.apiBase(),
        ws: null,
      });
      this.opts.onAccept?.(wallet);
      return;
    }

    const sessionId = stored?.sessionId || cryptoRandomId();
    const ticketId = await this.createTicket(sessionId);
    this.writeStoredSession({ sessionId, ticketId });
    await this.openHandshake(ticketId);
  }

  async disconnect(): Promise<void> {
    this.clearStorage();
    try {
      this.ws?.close();
    } catch {
      /* ignore */
    }
    this.ws = null;
    this.opts?.onDisconnect?.();
  }

  private async createTicket(sessionId: string): Promise<string> {
    const apiBase = this.apiBase();
    const res = await fetch(`${apiBase}/api/v1/connect/tickets`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        appName: this.opts?.appName,
        sessionId,
        iconUrl: this.opts?.iconUrl,
      }),
    });
    if (!res.ok) {
      if (res.status === 404) {
        throw new Error(
          `Zoro connect endpoint not found at ${apiBase}. Set REACT_APP_ZORO_API_URL / WALLET_URL to the environment provided by Zoro.`
        );
      }
      throw new Error("Failed to get ticket from server.");
    }
    const json = await res.json();
    if (!json?.ticketId) {
      throw new Error("TicketId missing in response.");
    }
    return json.ticketId;
  }

  private async openHandshake(ticketId: string): Promise<void> {
    const wsUrl = this.wsUrl(ticketId);
    await new Promise<void>((resolve, reject) => {
      let resolved = false;
      const ws = new WebSocket(wsUrl);
      this.ws = ws;

      const onMessage = (event: MessageEvent) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === "handshake_accept") {
            const { authToken, partyId, publicKey } = msg.data || {};
            if (authToken && partyId && publicKey) {
              this.writeStoredSession({
                ...(this.readStoredSession() || {}),
                authToken,
                partyId,
                publicKey,
              });
              const wallet = new ZoroWallet({
                authToken,
                partyId,
                publicKey,
                apiUrl: this.apiBase(),
                ws,
              });
              ws.onmessage = (evt) => {
                try {
                  const m = JSON.parse(evt.data);
                  if (
                    m.type === SignRequestResponseType.SIGN_REQUEST_APPROVED ||
                    m.type === SignRequestResponseType.SIGN_REQUEST_REJECTED ||
                    m.type === SignRequestResponseType.SIGN_REQUEST_ERROR
                  ) {
                    wallet.handleResponse(m);
                  }
                } catch {
                  /* ignore */
                }
              };
              resolved = true;
              this.opts?.onAccept?.(wallet);
              resolve();
            }
          } else if (msg.type === "handshake_reject") {
            this.clearStorage();
            resolved = true;
            this.opts?.onReject?.();
            reject(new Error("Zoro connection rejected"));
          } else if (msg.type === "handshake_disconnect") {
            this.clearStorage();
            resolved = true;
            this.opts?.onDisconnect?.();
            reject(new Error("Zoro connection disconnected"));
          }
        } catch {
          /* ignore */
        }
      };

      const onError = (err: any) => {
        if (!resolved) {
          reject(err instanceof Error ? err : new Error("Zoro websocket error"));
        }
      };

      const onClose = () => {
        if (!resolved) {
          reject(new Error("Zoro websocket closed"));
        }
      };

      ws.addEventListener("message", onMessage);
      ws.addEventListener("error", onError);
      ws.addEventListener("close", onClose);
    });
  }

  private apiBase(): string {
    if (this.opts?.apiUrl) return this.opts.apiUrl;
    if (this.opts?.network === "local") return "http://localhost:3001";
    return "https://api.zorowallet.com";
  }

  private wsUrl(ticketId: string): string {
    const api = this.apiBase();
    const url = new URL(api);
    const protocol = url.protocol === "http:" ? "ws:" : "wss:";
    const host = url.host;
    return `${protocol}//${host}/connect/ws?ticketId=${ticketId}`;
  }

  private readStoredSession(): StoredSession | null {
    if (typeof window === "undefined") return null;
    const raw = window.localStorage.getItem("zoro_connect");
    if (!raw) return null;
    try {
      return JSON.parse(raw);
    } catch {
      return null;
    }
  }

  private writeStoredSession(value: StoredSession) {
    if (typeof window === "undefined") return;
    try {
      window.localStorage.setItem("zoro_connect", JSON.stringify(value));
    } catch {
      /* ignore */
    }
  }

  private clearStorage() {
    if (typeof window === "undefined") return;
    try {
      window.localStorage.removeItem("zoro_connect");
    } catch {
      /* ignore */
    }
  }
}

export const zoro = new ZoroSDK();

function cryptoRandomId(): string {
  const cryptoObj: any =
    typeof window !== "undefined" ? (window.crypto || (window as any).msCrypto || undefined) : undefined;
  if (cryptoObj && typeof cryptoObj.randomUUID === "function") {
    return cryptoObj.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (cryptoObj && typeof cryptoObj.getRandomValues === "function") {
    cryptoObj.getRandomValues(bytes);
  } else {
    for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256);
  }
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return [
    hex.substring(0, 8),
    hex.substring(8, 12),
    hex.substring(12, 16),
    hex.substring(16, 20),
    hex.substring(20, 32),
  ].join("-");
}

