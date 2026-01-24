export type LoopProvider = {
  submitTransaction?: (command: any, opts?: any) => Promise<any>;
  executeAndWait?: (command: any, opts?: any) => Promise<any>;
  executeAndWaitForTransaction?: (command: any, opts?: any) => Promise<any>;
};

let activeProvider: LoopProvider | null = null;

export function setLoopProvider(provider: LoopProvider | null) {
  activeProvider = provider;
}

export function getLoopProvider(): LoopProvider | null {
  return activeProvider;
}

