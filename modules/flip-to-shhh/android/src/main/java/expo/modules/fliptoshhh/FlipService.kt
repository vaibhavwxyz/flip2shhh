package expo.modules.fliptoshhh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

/**
 * Long-lived foreground service that implements "Flip to Shhh".
 *
 * Power strategy (mirrors Pixel's implementation):
 *   1. PROXIMITY (on-change, wake-up) is the cheap gatekeeper. It fires only
 *      when something covers/uncovers the sensor. The accelerometer is *not*
 *      registered until proximity is covered.
 *   2. ACCELEROMETER (low frequency, ~5 Hz) is registered only while covered,
 *      to confirm the phone is actually face-down (Z gravity strongly negative)
 *      rather than, say, in a pocket.
 *
 * When both conditions hold we enable Do Not Disturb + a short haptic pulse.
 * When the phone is flipped back (or uncovered) we restore the previous
 * interruption filter.
 */
class FlipService : Service(), SensorEventListener {

  companion object {
    const val ACTION_START = "expo.modules.fliptoshhh.action.START"
    const val ACTION_STOP = "expo.modules.fliptoshhh.action.STOP"

    private const val CHANNEL_ID = "flip_to_shhh"
    private const val NOTIFICATION_ID = 0xF11D

    // Accelerometer sampling: 5 Hz => 200_000 microseconds between samples.
    private const val ACCEL_SAMPLING_US = 200_000

    // Z-axis gravity thresholds (m/s^2). Face-down => gravity points into the
    // screen => Z ~ -9.8. Hysteresis prevents flip-flapping near the boundary.
    private const val FACE_DOWN_ENTER_Z = -8.5f
    private const val FACE_DOWN_EXIT_Z = -6.5f

    /** True while the service is alive. Read from JS via the module. */
    @Volatile
    var isRunning: Boolean = false
      private set

    /** True while DND is currently forced on by this service. */
    @Volatile
    var isShushing: Boolean = false
      private set

    /**
     * Bridge to the JS runtime. Set by [FlipToShhhModule] while JS is observing
     * and null otherwise. The service works fine with no listener attached
     * (e.g. screen off / app killed) — this is purely for live UI updates.
     * Signature: (isRunning, isShushing) -> Unit
     */
    @Volatile
    var statusListener: ((Boolean, Boolean) -> Unit)? = null
  }

  private lateinit var sensorManager: SensorManager
  private lateinit var notificationManager: NotificationManager
  private var proximitySensor: Sensor? = null
  private var accelerometer: Sensor? = null
  private var wakeLock: PowerManager.WakeLock? = null

  private var proximityCovered = false
  private var accelRegistered = false

  // Interruption filter to restore when we leave Shhh mode.
  private var savedInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL

  override fun onCreate() {
    super.onCreate()
    sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    // Held only while the phone is covered, so the CPU can sample the
    // accelerometer even with the screen off. Released as soon as uncovered.
    wakeLock = powerManager.newWakeLock(
      PowerManager.PARTIAL_WAKE_LOCK,
      "FlipToShhh::EvaluationWakeLock"
    ).apply { setReferenceCounted(false) }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopEverything()
        return START_NOT_STICKY
      }
      else -> startListening()
    }
    // Ask the system to recreate us if we're killed while listening.
    return START_STICKY
  }

  private fun startListening() {
    if (isRunning) return

    createNotificationChannel()
    startInForeground(shushing = false)

    // Gatekeeper: proximity as an on-change sensor.
    proximitySensor?.let {
      sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
    }

    isRunning = true
    notifyStatus()
  }

  private fun stopEverything() {
    // Always restore the user's sound profile before we disappear.
    exitShush()
    sensorManager.unregisterListener(this)
    accelRegistered = false
    proximityCovered = false
    releaseWakeLock()

    isRunning = false
    notifyStatus()

    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  // --- SensorEventListener ---------------------------------------------------

  override fun onSensorChanged(event: SensorEvent) {
    when (event.sensor.type) {
      Sensor.TYPE_PROXIMITY -> handleProximity(event)
      Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }

  private fun handleProximity(event: SensorEvent) {
    val maxRange = event.sensor.maximumRange
    // Most sensors report a large value when far, ~0 when near. Treat anything
    // below the sensor's max range as "covered".
    val covered = event.values[0] < maxRange

    if (covered == proximityCovered) return
    proximityCovered = covered

    if (covered) {
      acquireWakeLock()
      registerAccelerometer()
    } else {
      unregisterAccelerometer()
      releaseWakeLock()
      // Uncovering means it was picked up / removed from the surface.
      exitShush()
    }
  }

  private fun handleAccelerometer(event: SensorEvent) {
    val z = event.values[2]

    if (!isShushing && proximityCovered && z <= FACE_DOWN_ENTER_Z) {
      enterShush()
    } else if (isShushing && z >= FACE_DOWN_EXIT_Z) {
      // Flipped back face-up (or tilted upright) while still on the surface.
      exitShush()
    }
  }

  // --- Accelerometer registration (only while covered) -----------------------

  private fun registerAccelerometer() {
    if (accelRegistered) return
    accelerometer?.let {
      sensorManager.registerListener(this, it, ACCEL_SAMPLING_US)
      accelRegistered = true
    }
  }

  private fun unregisterAccelerometer() {
    if (!accelRegistered) return
    accelerometer?.let { sensorManager.unregisterListener(this, it) }
    accelRegistered = false
  }

  // --- Shhh mode -------------------------------------------------------------

  private fun enterShush() {
    if (isShushing) return
    if (!notificationManager.isNotificationPolicyAccessGranted) return

    savedInterruptionFilter = notificationManager.currentInterruptionFilter
    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

    vibratePulse()
    isShushing = true

    updateNotification(shushing = true)
    notifyStatus()
  }

  private fun exitShush() {
    if (!isShushing) return
    if (notificationManager.isNotificationPolicyAccessGranted) {
      val restoreTo =
        if (savedInterruptionFilter == 0) NotificationManager.INTERRUPTION_FILTER_ALL
        else savedInterruptionFilter
      notificationManager.setInterruptionFilter(restoreTo)
    }
    isShushing = false

    updateNotification(shushing = false)
    notifyStatus()
  }

  private fun vibratePulse() {
    val vibrator = getVibrator() ?: return
    if (!vibrator.hasVibrator()) return
    // A short, crisp confirmation pulse.
    vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
  }

  private fun getVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
      vm?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

  // --- Foreground notification ----------------------------------------------

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Flip to Shhh",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Keeps watching your phone's orientation to auto-silence."
        setShowBadge(false)
      }
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(shushing: Boolean): Notification {
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    val contentIntent = launchIntent?.let {
      PendingIntent.getActivity(
        this, 0, it,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )
    }

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(if (shushing) "Shhh — Do Not Disturb on" else "Flip to Shhh active")
      .setContentText(
        if (shushing) "Flip your phone back over to restore sound."
        else "Place your phone face down to silence it."
      )
      .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
      .setContentIntent(contentIntent)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setShowWhen(false)
      .build()
  }

  private fun startInForeground(shushing: Boolean) {
    val notification = buildNotification(shushing)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val type =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else 0
      startForeground(NOTIFICATION_ID, notification, type)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun updateNotification(shushing: Boolean) {
    if (!isRunning) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    notificationManager.notify(NOTIFICATION_ID, buildNotification(shushing))
  }

  // --- Wake lock -------------------------------------------------------------

  private fun acquireWakeLock() {
    wakeLock?.let { if (!it.isHeld) it.acquire(10 * 60 * 1000L /* 10 min safety cap */) }
  }

  private fun releaseWakeLock() {
    wakeLock?.let { if (it.isHeld) it.release() }
  }

  // --- Misc ------------------------------------------------------------------

  private fun notifyStatus() {
    statusListener?.invoke(isRunning, isShushing)
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // The app was swiped away from recents. Because the <service> is declared
    // with stopWithTask="false", the system keeps this foreground service
    // alive — so we deliberately do NOT stop here. Sensor listening continues.
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    // Safety net: never leave the user stuck in DND if we're torn down.
    exitShush()
    releaseWakeLock()
    sensorManager.unregisterListener(this)
    isRunning = false
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
