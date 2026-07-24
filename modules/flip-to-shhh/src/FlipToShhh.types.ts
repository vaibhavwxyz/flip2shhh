/** Snapshot of the background service state. */
export type FlipStatus = {
  /** Whether the foreground sensor service is currently running. */
  isRunning: boolean;
  /** Whether DND is currently forced on because the phone is face down. */
  isShushing: boolean;
};

/** Native events emitted by the FlipToShhh module. */
export type FlipToShhhModuleEvents = {
  onStatusChange: (status: FlipStatus) => void;
};
