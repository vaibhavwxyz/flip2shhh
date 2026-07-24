import { type EventSubscription } from 'expo-modules-core';

import FlipToShhh from './src/FlipToShhhModule';
import { type FlipStatus } from './src/FlipToShhh.types';

export * from './src/FlipToShhh.types';

/** Whether the background sensor service is currently running. */
export function isServiceRunning(): boolean {
  return FlipToShhh.isServiceRunning();
}

/** Whether DND is currently forced on because the phone is face down. */
export function isShushing(): boolean {
  return FlipToShhh.isShushing();
}

/** Start the foreground sensor service. */
export function startService(): Promise<void> {
  return FlipToShhh.startService();
}

/** Stop the service and restore the previous sound profile. */
export function stopService(): Promise<void> {
  return FlipToShhh.stopService();
}

/** Whether the app has been granted "Do Not Disturb access". */
export function isDndPermissionGranted(): boolean {
  return FlipToShhh.isDndPermissionGranted();
}

/** Deep-link to the system "Do Not Disturb access" settings screen. */
export function openDndSettings(): void {
  FlipToShhh.openDndSettings();
}

/** Whether the app is exempt from battery optimization / Doze. */
export function isIgnoringBatteryOptimizations(): boolean {
  return FlipToShhh.isIgnoringBatteryOptimizations();
}

/** Show the system dialog to exempt the app from battery optimization. */
export function requestIgnoreBatteryOptimizations(): void {
  FlipToShhh.requestIgnoreBatteryOptimizations();
}

/** Device manufacturer (e.g. "Xiaomi", "samsung", "Google"). */
export function getManufacturer(): string {
  return FlipToShhh.getManufacturer();
}

/** Whether this device exposes a known OEM auto-start / protected-app screen. */
export function hasAutoStartSettings(): boolean {
  return FlipToShhh.hasAutoStartSettings();
}

/** Open the OEM auto-start screen (App Info fallback). Returns true if OEM-specific. */
export function openAutoStartSettings(): boolean {
  return FlipToShhh.openAutoStartSettings();
}

/**
 * Subscribe to service status changes (start/stop and enter/leave Shhh mode).
 * Remember to call `.remove()` on the returned subscription on cleanup.
 */
export function addStatusListener(
  listener: (status: FlipStatus) => void
): EventSubscription {
  return FlipToShhh.addListener('onStatusChange', listener);
}

export default FlipToShhh;
