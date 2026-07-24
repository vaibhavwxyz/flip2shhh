package expo.modules.fliptoshhh

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
  }
}
