# Flip to Shhh 🤫

A Pixel-inspired Android app: place your phone **face down** on a surface and it
automatically enables **Do Not Disturb** with a short haptic pulse. Flip it back
over (or pick it up) and your sound profile is restored.

Built with **Expo SDK 57 / React Native 0.86** (New Architecture) + a custom
**Kotlin Expo Module** running a persistent **foreground service**.

## How it works

| Layer | File | Role |
| --- | --- | --- |
| UI | `src/app/index.tsx` | Status, start/stop toggle, DND-permission gating |
| TS API | `modules/flip-to-shhh/index.ts` | Typed wrappers + `onStatusChange` events |
| Native bridge | `modules/.../FlipToShhhModule.kt` | Start/stop service, DND permission, deep-link |
| Background | `modules/.../FlipService.kt` | Sensors + DND + haptics (survives screen-off) |
| Config plugin | `plugins/withFlipToShhh.js` | Injects permissions + `<service>` on prebuild |

### Low-power sensing strategy
1. **Proximity** (`TYPE_PROXIMITY`, on-change) is the cheap gatekeeper. The
   accelerometer stays unregistered until the sensor is covered.
2. **Accelerometer** (`~5 Hz`) is registered only while covered, confirming a
   true face-down pose (`Z ≤ -8.5 m/s²`) with hysteresis on exit.
3. A short `PARTIAL_WAKE_LOCK` is held only while covered so sampling continues
   with the screen off, then released immediately.

## Prerequisites (local, no Android Studio)

This machine has JDK 17 + `ANDROID_HOME` set, but the **SDK packages are not yet
installed**. Install command-line tools + the required packages once:

```bash
# 1. Command-line tools + platform-tools (adb) via Homebrew
brew install --cask android-commandlinetools

# 2. Point ANDROID_HOME at Homebrew's location (add to ~/.zshrc)
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# 3. Accept licenses and install the packages this project targets (SDK 36)
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

> Prefer your existing `~/Library/Android/sdk`? Install the same packages there
> instead and leave `ANDROID_HOME` pointing at it.

## Run on a physical device

```bash
# Connect the phone over USB, enable USB debugging, then confirm it's visible:
adb devices

# Compile the dev build and deploy directly to the device (NO emulator):
npx expo run:android --device
```

On first launch: tap **Open settings** to grant *Do Not Disturb access*, return
to the app, then **Start service**. Lay the phone face down to trigger Shhh.
