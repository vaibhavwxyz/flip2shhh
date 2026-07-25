package expo.modules.fliptoshhh

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * JS-facing bridge for the Flip to Shhh feature.
 *
 * All the heavy lifting (sensor listening, DND toggling, haptics) lives in
 * [FlipService] so that it keeps running when the screen is off and the JS
 * runtime is suspended. This module only starts/stops that service and reports
 * status back to JS via the `onStatusChange` event.
 */
class FlipToShhhModule : Module() {

  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val notificationManager: NotificationManager
    get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  // Known OEM "Auto-start / protected app" screens. These proprietary
  // restrictions are NOT covered by the standard battery-optimization API and
  // cannot be queried — the user must toggle them manually, so we can only
  // deep-link them to the right screen.
  private val autoStartComponents = listOf(
    // Xiaomi / Redmi / POCO (MIUI)
    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    // Oppo / Realme (ColorOS)
    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
    ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    // Vivo / iQOO (FuntouchOS)
    ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
    ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
    // Huawei / Honor
    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
    // OnePlus (OxygenOS)
    ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
    // Letv
    ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
    // Asus
    ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
    // Samsung (One UI) – routes to device-care battery screen
    ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
  )

  @Suppress("DEPRECATION")
  private fun resolves(intent: Intent): Boolean =
    context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null

  override fun definition() = ModuleDefinition {
    Name("FlipToShhh")

    // Emitted whenever the service starts/stops or enters/leaves "Shhh" mode.
    // Payload: { isRunning: boolean, isShushing: boolean }
    Events("onStatusChange")

    // --- Lifecycle: wire the service -> JS bridge only while JS is listening ---

    OnStartObserving {
      FlipService.statusListener = { isRunning, isShushing ->
        sendEvent(
          "onStatusChange",
          Bundle().apply {
            putBoolean("isRunning", isRunning)
            putBoolean("isShushing", isShushing)
          }
        )
      }
    }

    OnStopObserving {
      FlipService.statusListener = null
    }

    // --- Service control -----------------------------------------------------

    Function("isServiceRunning") {
      FlipService.isRunning
    }

    Function("isShushing") {
      FlipService.isShushing
    }

    AsyncFunction("startService") {
      val intent = Intent(context, FlipService::class.java).apply {
        action = FlipService.ACTION_START
      }
      // Must use startForegroundService so the service can promote itself to
      // the foreground within the 5s window on Android 8+.
      ContextCompat.startForegroundService(context, intent)
    }

    AsyncFunction("stopService") {
      val intent = Intent(context, FlipService::class.java).apply {
        action = FlipService.ACTION_STOP
      }
      context.startService(intent)
      // startService() returns a ComponentName which Expo can't serialize;
      // end on Unit so this function's return type is Unit.
      Unit
    }

    // --- Do Not Disturb permission ------------------------------------------

    Function("isDndPermissionGranted") {
      notificationManager.isNotificationPolicyAccessGranted
    }

    // Deep-link the user to the exact "Do Not Disturb access" system screen.
    Function("openDndSettings") {
      val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }

    // --- Battery optimization (survival after swipe-away on OEM skins) -------

    // True if the app is exempt from Doze / battery optimization. Without this,
    // many OEM battery managers kill the foreground service when the app is
    // removed from recents.
    Function("isIgnoringBatteryOptimizations") {
      val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
      pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // Shows the system dialog asking the user to exempt this app.
    Function("requestIgnoreBatteryOptimizations") {
      val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }

    // --- OEM auto-start / protected-app screen (undetectable, manual) --------

    Function("getManufacturer") {
      Build.MANUFACTURER ?: ""
    }

    // True only if this device exposes a known OEM auto-start screen, so the UI
    // can show the extra manual step only where it's actually relevant.
    Function("hasAutoStartSettings") {
      autoStartComponents.any { resolves(Intent().setComponent(it)) }
    }

    // Opens the OEM's auto-start screen if we can find it; otherwise falls back
    // to this app's system App Info page. Returns true if an OEM-specific
    // screen was opened.
    Function("openAutoStartSettings") {
      val target = autoStartComponents.firstOrNull { resolves(Intent().setComponent(it)) }
      if (target != null) {
        try {
          context.startActivity(
            Intent().setComponent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          )
          return@Function true
        } catch (_: Throwable) {
          // Fall through to App Info.
        }
      }
      context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
          data = Uri.parse("package:${context.packageName}")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      )
      false
    }
  }
}
