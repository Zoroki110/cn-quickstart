import { IWalletConnector } from "./IWalletConnector";
import { DevWalletConnector } from "./DevWalletConnector";
import { LoopWalletConnector } from "./LoopWalletConnector";
import { ZoroWalletConnector } from "./ZoroWalletConnector";

class WalletManager {
  private activeConnector: IWalletConnector | null = null;
  private loopConnector: LoopWalletConnector | null = null;
  private devConnector: DevWalletConnector | null = null;
  private zoroConnector: ZoroWalletConnector | null = null;

  async connectLoop(): Promise<IWalletConnector> {
    if (!this.loopConnector) {
      this.loopConnector = new LoopWalletConnector();
    }
    this.activeConnector = this.loopConnector;
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
}

export const walletManager = new WalletManager();

