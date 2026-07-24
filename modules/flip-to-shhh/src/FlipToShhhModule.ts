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
  /** Whether the app is exempt from battery optimization / Doze. */
  isIgnoringBatteryOptimizations(): boolean;
  /** Shows the system dialog asking to exempt the app from battery optimization. */
  requestIgnoreBatteryOptimizations(): void;
  /** Device manufacturer (e.g. "Xiaomi", "samsung", "Google"). */
  getManufacturer(): string;
  /** Whether this device exposes a known OEM auto-start / protected-app screen. */
  hasAutoStartSettings(): boolean;
  /** Opens the OEM auto-start screen (or App Info fallback). Returns true if OEM-specific. */
  openAutoStartSettings(): boolean;
}

// Resolves to the native module registered as "FlipToShhh".
export default requireNativeModule<FlipToShhhModule>('FlipToShhh');
