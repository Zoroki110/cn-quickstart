import { IWalletConnector } from "./IWalletConnector";

type LoopClient = {
  connect?: () => Promise<unknown>;
  init?: () => Promise<unknown>;
  getParty?: () => Promise<string>;
  signMessage?: (message: string) => Promise<string>;
};

export class LoopWalletConnector implements IWalletConnector {
  private clientPromise?: Promise<LoopClient>;

  private ensureClient(): Promise<LoopClient> {
    if (!this.clientPromise) {
      this.clientPromise = this.loadClient();
    }
    return this.clientPromise;
  }

  private async loadClient(): Promise<LoopClient> {
    const sdkModule = await import("@fivenorth/loop-sdk");
    const LoopCtor = (sdkModule as any).Loop ?? (sdkModule as any).loop ?? sdkModule;

    if (typeof LoopCtor === "function") {
      const instance: LoopClient = new LoopCtor();
      if (typeof instance.connect === "function") {
        await instance.connect();
      } else if (typeof instance.init === "function") {
        await instance.init();
      }
      return instance;
    }

    const instance: LoopClient = LoopCtor as LoopClient;
    if (typeof instance.connect === "function") {
      await instance.connect();
    } else if (typeof instance.init === "function") {
      await instance.init();
    }
    return instance;
  }

  async getParty(): Promise<string> {
    const client = await this.ensureClient();
    if (!client.getParty) {
      throw new Error("Loop wallet does not expose getParty()");
    }
    const party = await client.getParty();
    if (!party) {
      throw new Error("Loop wallet returned an empty party identifier");
    }
    return party;
  }

  async signMessage(message: string): Promise<string> {
    const client = await this.ensureClient();
    if (!client.signMessage) {
      throw new Error("Loop wallet does not expose signMessage()");
    }
    const signature = await client.signMessage(message);
    if (!signature) {
      throw new Error("Loop wallet returned an empty signature");
    }
    return signature;
  }

  getType(): "loop" | "zoro" | "dev" | "unknown" {
    return "loop";
  }
}
