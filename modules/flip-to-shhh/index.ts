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
