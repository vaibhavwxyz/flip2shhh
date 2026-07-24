import { NativeModule, requireNativeModule } from 'expo';

import { FlipToShhhModuleEvents } from './FlipToShhh.types';

declare class FlipToShhhModule extends NativeModule<FlipToShhhModuleEvents> {
  /** Synchronously returns whether the foreground service is alive. */
  isServiceRunning(): boolean;
  /** Synchronously returns whether DND is currently forced on. */
  isShushing(): boolean;
  /** Starts the foreground sensor service. */
  startService(): Promise<void>;
  /** Stops the service and restores the previous sound profile. */
  stopService(): Promise<void>;
  /** Whether the app has "Do Not Disturb access" granted. */
  isDndPermissionGranted(): boolean;
  /** Deep-links to the system "Do Not Disturb access" settings screen. */
  openDndSettings(): void;
}

// Resolves to the native module registered as "FlipToShhh".
export default requireNativeModule<FlipToShhhModule>('FlipToShhh');
