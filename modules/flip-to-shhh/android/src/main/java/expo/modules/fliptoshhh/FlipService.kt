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
import kotlin.math.abs

/**
 * Long-lived foreground service that implements "Flip to Shhh".
 *
 * Power strategy (mirrors Pixel's implementation) — layered sensor gating so
 * the CPU can sleep in the steady state:
 *   1. PROXIMITY (on-change, wake-up) is the cheap always-on gatekeeper. It
 *      fires only when something covers/uncovers the sensor. The accelerometer
 *      is *not* registered until proximity is covered.
 *   2. ACCELEROMETER (~5 Hz) runs only during the brief evaluation window while
 *      covered, to confirm a flat, face-down pose. It is bounded by
 *      EVAL_MAX_SAMPLES so a pocket can't keep it (and the wake lock) alive.
 *   3. Once silenced, the accelerometer is UNREGISTERED and the wake lock is
 *      released. While the phone sits face-down, only the on-change proximity
 *      sensor is armed — near-zero cost. The flip back is detected when
 *      proximity goes FAR, which triggers restoring the interruption filter.
 */
class FlipService : Service(), SensorEventListener {

  companion object {
    const val ACTION_START = "expo.modules.fliptoshhh.action.START"
    const val ACTION_STOP = "expo.modules.fliptoshhh.action.STOP"

    private const val CHANNEL_ID = "flip_to_shhh"
    private const val NOTIFICATION_ID = 0xF11D

    // Accelerometer sampling: 5 Hz => 200_000 microseconds between samples.
    private const val ACCEL_SAMPLING_US = 200_000

    // Gravity thresholds (m/s^2). Lying flat & face-down on a surface puts
    // almost all of gravity on -Z (~ -9.8) with near-zero X/Y tilt. A phone in
    // a pocket is tilted and/or moving, so it won't sustain this pose.
    private const val FACE_DOWN_ENTER_Z = -9.2f   // near-flat, screen straight down
    private const val FLAT_XY_MAX = 3.0f           // max allowed side/end tilt

    // Require the flat-face-down pose to hold for several consecutive samples
    // (~0.8s at 5 Hz) before silencing, so pocketing the phone — which briefly
    // passes through many orientations — doesn't trigger it.
    private const val REQUIRED_FACE_DOWN_SAMPLES = 4

    // If the sensor stays covered but never settles flat (e.g. in a pocket),
    // stop the high-rate sampling after ~15s (75 samples @ 5 Hz) and release
    // the wake lock. We resume only on the next proximity change.
    private const val EVAL_MAX_SAMPLES = 75

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

  // Consecutive flat-face-down samples seen so far (debounce, see companion).
  private var faceDownSamples = 0

  // Total accelerometer samples since it was (re)registered — bounds the
  // evaluation window so we don't sample forever while covered (see companion).
  private var evalSamples = 0

  // Interruption filter to restore when we leave Shhh mode.
  private var savedInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL

  override fun onCreate() {
    super.onCreate()
    sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    // Held ONLY during the accelerometer evaluation window, so it can sample
    // with the screen off. Released as soon as we silence, give up, or uncover.
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
    faceDownSamples = 0
    evalSamples = 0
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
      faceDownSamples = 0
      // Uncovering means it was picked up / removed from the surface.
      exitShush()
    }
  }

  private fun handleAccelerometer(event: SensorEvent) {
    // The accelerometer only runs during the short evaluation window (proximity
    // NEAR, not yet shushing). Once we silence, it is unregistered — the flip
    // back is detected by the on-change proximity sensor, not by polling here.
    if (isShushing) return

    val x = event.values[0]
    val y = event.values[1]
    val z = event.values[2]

    // Lying flat AND face down: strong -Z gravity with little X/Y tilt.
    // The tilt check is what rejects a phone sitting angled in a pocket.
    val flatFaceDown =
      z <= FACE_DOWN_ENTER_Z && abs(x) <= FLAT_XY_MAX && abs(y) <= FLAT_XY_MAX

    if (flatFaceDown) {
      faceDownSamples++
      if (faceDownSamples >= REQUIRED_FACE_DOWN_SAMPLES) {
        enterShush()
        return
      }
    } else {
      // Any wobble/tilt resets the debounce — a pocket rarely holds the pose.
      faceDownSamples = 0
    }

    // Give up sampling if it stays covered but never settles flat, so we don't
    // hold the CPU awake indefinitely (e.g. a phone riding in a pocket).
    if (++evalSamples >= EVAL_MAX_SAMPLES) {
      unregisterAccelerometer()
      releaseWakeLock()
    }
  }

  // --- Accelerometer registration (only while covered) -----------------------

  private fun registerAccelerometer() {
    if (accelRegistered) return
    accelerometer?.let {
      sensorManager.registerListener(this, it, ACCEL_SAMPLING_US)
      accelRegistered = true
      // Fresh evaluation window each time we start sampling.
      faceDownSamples = 0
      evalSamples = 0
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
    // ALARMS = silence everything (calls, notifications) except alarms.
    // PRIORITY would let calls from starred contacts / repeat callers ring,
    // which defeats the point of "Shhh".
    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)

    vibratePulse()
    isShushing = true

    // Battery: we're silenced and the phone will sit flat for a long time, so
    // stop the high-rate accelerometer and drop the wake lock. Only the
    // on-change proximity sensor stays armed; it fires (FAR) when the phone is
    // flipped or lifted, which is what drives exitShush().
    unregisterAccelerometer()
    releaseWakeLock()

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
    // Never let a wake-lock issue crash the sensor thread — the feature still
    // works while the screen is on even if the lock can't be acquired.
    try {
      wakeLock?.let { if (!it.isHeld) it.acquire(10 * 60 * 1000L /* 10 min safety cap */) }
    } catch (_: Throwable) {
    }
  }

  private fun releaseWakeLock() {
    try {
      wakeLock?.let { if (it.isHeld) it.release() }
    } catch (_: Throwable) {
    }
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
