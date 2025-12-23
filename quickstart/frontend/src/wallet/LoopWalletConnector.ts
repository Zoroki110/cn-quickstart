import { IWalletConnector } from "./IWalletConnector";
import { connectOnce, getProvider, LoopProvider } from "./loopSdk";

export class LoopWalletConnector implements IWalletConnector {
  private static readonly DEBUG = false;

  private provider: LoopProvider | null = null;

  async connect(): Promise<void> {
    if (this.provider) return;
    const prov = await connectOnce();
    this.provider = prov;
    if (LoopWalletConnector.DEBUG) {
      console.debug("[Loop] connect resolved, provider keys", Object.keys(prov || {}));
    }
  }

  async getParty(): Promise<string> {
    await this.ensureConnected();
    const prov: any = this.provider || getProvider();
    const party = prov?.party_id ?? prov?.partyId ?? prov?.party;
    if (!party) {
      if (LoopWalletConnector.DEBUG) console.debug("[Loop] provider missing partyId", prov);
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
    return this.provider || getProvider();
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
}

// Netlify redeploy marker: do not remove. This forces a fresh build.
