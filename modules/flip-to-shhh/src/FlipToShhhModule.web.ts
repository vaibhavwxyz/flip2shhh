import { registerWebModule, NativeModule } from 'expo';

import { FlipToShhhModuleEvents } from './FlipToShhh.types';

// Flip to Shhh is Android-only. This no-op keeps imports type-safe on web.
class FlipToShhhModule extends NativeModule<FlipToShhhModuleEvents> {
  isServiceRunning(): boolean {
    return false;
  }
  isShushing(): boolean {
    return false;
  }
  async startService(): Promise<void> {}
  async stopService(): Promise<void> {}
  isDndPermissionGranted(): boolean {
    return false;
  }
  openDndSettings(): void {}
}

export default registerWebModule(FlipToShhhModule, 'FlipToShhhModule');
