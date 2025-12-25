import { IWalletConnector } from "./IWalletConnector";
import { DevWalletConnector } from "./DevWalletConnector";
import { LoopWalletConnector } from "./LoopWalletConnector";
import { ZoroWalletConnector } from "./ZoroWalletConnector";

class WalletManager {
  initLoopSdk(): void {
    LoopWalletConnector.initOnce();
  }

  getOrCreateLoopConnector(): LoopWalletConnector {
    if (!this.loopConnector) {
      this.loopConnector = new LoopWalletConnector();
    }
    return this.loopConnector;
  }

  private activeConnector: IWalletConnector | null = null;
  private loopConnector: LoopWalletConnector | null = null;
  private devConnector: DevWalletConnector | null = null;
  private zoroConnector: ZoroWalletConnector | null = null;

  async connectLoop(): Promise<IWalletConnector> {
    const connector = this.getOrCreateLoopConnector();
    if (typeof connector.connect === "function") {
      await connector.connect();
    }
    this.activeConnector = connector;
    return this.activeConnector;
  }

  async connectDev(): Promise<IWalletConnector> {
    if (!this.devConnector) {
      this.devConnector = new DevWalletConnector();
    }
    this.activeConnector = this.devConnector;
    return this.activeConnector;
  }

  async connectZoro(): Promise<IWalletConnector> {
    if (!this.zoroConnector) {
      this.zoroConnector = new ZoroWalletConnector();
    }
    this.activeConnector = this.zoroConnector;
    return this.activeConnector;
  }

  getConnector(): IWalletConnector | null {
    return this.activeConnector;
  }

  getLoopConnector(): LoopWalletConnector | null {
    return this.loopConnector ?? null;
  }
}

export const walletManager = new WalletManager();
