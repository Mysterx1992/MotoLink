package it.motolink.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.Surface
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.graphics.PixelFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * MediaProjection -> H.264 -> in-memory H264FrameBus. P2P/Valico prefers Android software AVC; standard path remains hardware AVC.
 * Video only. V15 source-native V12-base edge-extension path: MediaProjection keeps the phone/source geometry,
 * a GPU compositor performs automatic landscape COVER / portrait FIT into the runtime T-Box
 * canvas, then optional rider-controlled edge adaptation is applied in TFT pixels.
 * No motorcycle/model/app-specific geometry is hardcoded.
 * No storage/cloud recording.
 */
class MirrorService : Service() {
    companion object {
        const val ACTION_START = "it.motolink.app.START_MIRROR"
        const val ACTION_STOP = "it.motolink.app.STOP_MIRROR"
        const val ACTION_PROX_ARM = "it.motolink.app.PROX_ARM"
        const val ACTION_PROX_RELEASE = "it.motolink.app.PROX_RELEASE"
        const val ACTION_PROX_SNAPSHOT = "it.motolink.app.PROX_SNAPSHOT"
        const val ACTION_ZOOM_UPDATE = "it.motolink.app.ZOOM_UPDATE" // legacy alias, no zoom UI; legacy alias only
        const val ACTION_ADAPTATION_UPDATE = "it.motolink.app.ADAPTATION_UPDATE"
        const val EXTRA_PROX_STAGE = "proxStage"
        const val EXTRA_PROX_GATE_ALLOWED = "proxGateAllowed"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_VALICO_SOFT_H264 = "valicoSoftH264"
        private const val CHANNEL_ID = "voge_mirror"
        private const val NOTIFICATION_ID = 1001
        private const val FALLBACK_WIDTH = 800
        private const val FALLBACK_HEIGHT = 480
        private const val FPS = 30
        private const val BITRATE = 5_000_000
        private const val VALICO_SOFT_BITRATE = 3_000_000
        private const val I_FRAME_INTERVAL_SEC = 1
        private const val REQUESTED_ENCODER_LATENCY_FRAMES = 1
        private const val TBOX_GEOMETRY_WAIT_MS = 8000L
        private const val TBOX_GEOMETRY_SETTLE_MS = 320L
        private const val PIP_PROBE_REQUIRED_SAMPLES = 3
        private const val PIP_PROBE_MIN_SAMPLE_BYTES = 1200
        private const val PIP_PROBE_VISIBLE_RATIO = 0.15
        private const val DOUBLE_VOLUME_DOWN_WINDOW_MS = 5000L
        private const val DOUBLE_VOLUME_DOWN_MIN_GAP_MS = 90L
        private const val VOLUME_GESTURE_COOLDOWN_MS = 600L
        private const val VOLUME_EVENT_DEDUP_MS = 70L
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }

    private var projection: MediaProjection? = null
    @Volatile private var projectionStartPending = false
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var coverRenderer: StaticCoverRenderer? = null
    private var autoActiveAreaCoverRenderer: AutoActiveAreaCoverRenderer? = null
    private var projectionInputSurface: Surface? = null
    @Volatile private var targetWidth = FALLBACK_WIDTH
    @Volatile private var targetHeight = FALLBACK_HEIGHT
    @Volatile private var sourceWidth = FALLBACK_WIDTH
    @Volatile private var sourceHeight = FALLBACK_HEIGHT
    @Volatile private var captureDpi = 160
    private val draining = AtomicBoolean(false)
    private var drainThread: Thread? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private val proximityDiagHandler by lazy { Handler(Looper.getMainLooper()) }
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityListenerRegistered = false
    @Volatile private var proximityNear: Boolean? = null
    @Volatile private var proximityWakeLockFrameworkEnabled: Boolean? = null
    @Volatile private var proximityGateAllowed = true
    private val lastObservedVolumes = LinkedHashMap<Int, Int>()
    private var manualVolumeReceiverRegistered = false
    private var manualUnlockFailsafeReceiverRegistered = false
    private var volumeDownFirstAt = 0L
    private var volumeGestureCooldownUntil = 0L
    private var lastAcceptedVolumeDownAt = 0L
    @Volatile private var manualDisplayBlackoutActive = false
    private var manualBlackOverlayView: View? = null
    private var manualBlackOverlayWindowManager: WindowManager? = null

    private var adaptationOverlayView: View? = null
    private var adaptationOverlayWindowManager: WindowManager? = null
    private var adaptationOverlayParams: WindowManager.LayoutParams? = null
    private var adaptationReverseMode = false
    private val manualVolumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_VOLUME_CHANGED || projection == null) return
            val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, Int.MIN_VALUE)
            val current = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, Int.MIN_VALUE)
            val previous = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, Int.MIN_VALUE)
            if (stream == Int.MIN_VALUE || current == Int.MIN_VALUE || previous == Int.MIN_VALUE) return

            lastObservedVolumes[stream] = current
            if (current < previous) {
                handleObservedVolumeDown(stream, previous, current, "broadcast")
            } else if (current > previous) {
                volumeDownFirstAt = 0L
            }
        }
    }

    private val manualUnlockFailsafeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_USER_PRESENT) return

            val keyguard = runCatching { getSystemService(KeyguardManager::class.java) }.getOrNull()
            val reallyUnlocked = keyguard?.isDeviceLocked != true && keyguard?.isKeyguardLocked != true
            if (!reallyUnlocked) {
                AppLog.add("DISPLAY MANUALE FAILSAFE: USER_PRESENT ignorato; keyguard ancora attivo")
                return
            }

            // A real user unlock is the emergency escape path. A simple Power/screen-off
            // event must NOT disable the blackout while the phone is in a pocket.
            volumeDownFirstAt = 0L
            volumeGestureCooldownUntil = 0L
            lastAcceptedVolumeDownAt = 0L
            if (manualDisplayBlackoutActive || manualBlackOverlayView != null) {
                deactivateManualDisplayBlackout("sblocco reale telefono / failsafe")
                AppLog.add("DISPLAY MANUALE FAILSAFE: sblocco reale confermato -> overlay OFF")
            }
        }
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || event.sensor.type != Sensor.TYPE_PROXIMITY || event.values.isEmpty()) return
            val distance = event.values[0]
            val maxRange = event.sensor.maximumRange
            val isNear = distance < maxRange
            if (proximityNear == isNear) return
            proximityNear = isNear
            AppLog.add(
                "PROX SENSOR SERVICE: ${if (isNear) "NEAR" else "FAR"} " +
                    "distance=$distance max=$maxRange"
            )
            val wl = proximityWakeLock
            AppLog.add(
                "PROX CONTINUOUS SENSOR: ${if (isNear) "NEAR" else "FAR"}; " +
                    "wakeLockHeld=${wl?.isHeld == true}; nessun acquire/release per evento"
            )
            scheduleProximityPowerPathSnapshots(if (isNear) "NEAR_CONTINUOUS" else "FAR_CONTINUOUS")

            // Pocket Mode: if the native proximity wake lock is not framework-effective,
            // launch the 1x1 transparent Gate so HyperOS can keep the physical panel off.
            if (isNear) {
                if (proximityWakeLockFrameworkEnabled != true) {
                    if (proximityGateAllowed) {
                        AppLog.add(
                            "PROX ZERO-PIP GATE: NEAR con framework=" +
                                when (proximityWakeLockFrameworkEnabled) {
                                    true -> "ENABLED"
                                    false -> "DISABLED"
                                    null -> "UNKNOWN"
                                } + " -> richiesta Gate Activity background"
                        )
                        launchProximityTopGate()
                    } else {
                        AppLog.add(
                            "PROX GATE DISATTIVATA dalla scelta Modalità tasca: framework non ENABLED; " +
                                "resta attivo solo il proximity nativo"
                        )
                    }
                } else {
                    AppLog.add("PROX GATE non necessario: NEAR con framework=ENABLED")
                }
            } else {
                ProximityGateActivity.finishIfActive("FAR rilevato dal foreground service")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val shuttingDown = AtomicBoolean(false)
    @Volatile private var valicoSoftH264 = false
    @Volatile private var lockPlaceholderActive = false
    @Volatile private var capturedContentVisible = true
    @Volatile private var resumeNeedsKeyFrame = false
    private val hiddenSamples = AtomicLong(0L)
    private val pipForwardedSamples = AtomicLong(0L)
    @Volatile private var pipProbeLive = false
    @Volatile private var pipProbeAwaitKeyFrame = false
    private var pipProbeCandidateStreak = 0
    private var visibleSampleSizeEwma = 0.0

    // V5 rotation handoff. Android may emit duplicate/intermediate resize callbacks while
    // rotating. We debounce them, freeze the last valid encoded frame, resize SurfaceTexture
    // before VirtualDisplay, then discard a few producer frames before committing geometry.
    private val geometryHandler by lazy { Handler(Looper.getMainLooper()) }
    private var geometrySettleRunnable: Runnable? = null
    @Volatile private var pendingGeometryWidth = 0
    @Volatile private var pendingGeometryHeight = 0
    private var geometryGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        armManualDisplayUnlockFailsafe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown(stopProjection = true)
                if (Build.VERSION.SDK_INT >= 24) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startProjection(intent)
            ACTION_PROX_ARM -> {
                proximityGateAllowed = intent?.getBooleanExtra(EXTRA_PROX_GATE_ALLOWED, true) ?: true
                AppLog.add("PROX GATE POLICY: ${if (proximityGateAllowed) "ABILITATA" else "DISABILITATA"}")
                armProximityScreenOff()
            }
            ACTION_PROX_RELEASE -> releaseProximityScreenOff()
            ACTION_PROX_SNAPSHOT -> {
                val stage = intent.getStringExtra(EXTRA_PROX_STAGE) ?: "ACTIVITY_TRANSITION"
                logProximityPowerPath(stage)
                scheduleProximityPowerPathSnapshots(stage)
            }
            ACTION_ZOOM_UPDATE, ACTION_ADAPTATION_UPDATE -> {
                applyAdaptationRuntime("impostazioni")
            }
        }
        return START_NOT_STICKY
    }

    private fun displayStateName(state: Int): String = when (state) {
        Display.STATE_OFF -> "OFF"
        Display.STATE_ON -> "ON"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        Display.STATE_VR -> "VR"
        Display.STATE_UNKNOWN -> "UNKNOWN"
        else -> "STATE_$state"
    }

    private fun processImportanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
        else -> importance.toString()
    }

    private fun logProximityPowerPath(stage: String) {
        val pm = getSystemService(PowerManager::class.java)
        val dm = getSystemService(DisplayManager::class.java)
        val defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val proc = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(proc)
        val wl = proximityWakeLock
        AppLog.add(
            "PROX POWER PATH [$stage]: " +
                "wakeLockHeld=${wl?.isHeld == true} " +
                "interactive=${pm.isInteractive} " +
                "display=${displayStateName(defaultDisplay?.state ?: Display.STATE_UNKNOWN)} " +
                "proc=${processImportanceName(proc.importance)} " +
                "sensorNear=${proximityNear == true}"
        )
    }

    private fun scheduleProximityPowerPathSnapshots(prefix: String) {
        // Diagnostic logging: one delayed checkpoint is enough to verify the resulting
        // display/wake-lock state without flooding the local log.
        val delay = 150L
        proximityDiagHandler.postDelayed({ logProximityPowerPath("$prefix+${delay}ms") }, delay)
    }

    private fun armProximityScreenOff() {
        if (projection == null) {
            AppLog.add("PROX SERVICE non armato: avvia prima il mirror")
            return
        }


        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            AppLog.add("PROX OFF non supportato da questo dispositivo")
            return
        }
        if (proximityWakeLock == null) {
            proximityWakeLock = pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "$packageName:VogeMirrorProximity"
            ).apply { setReferenceCounted(false) }

            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    proximityWakeLock?.setStateListener(mainExecutor) { enabled: Boolean ->
                        proximityWakeLockFrameworkEnabled = enabled
                        AppLog.add("PROX WAKELOCK FRAMEWORK: ${if (enabled) "ENABLED" else "DISABLED"}")
                        logProximityPowerPath("WAKELOCK_${if (enabled) "ENABLED" else "DISABLED"}")
                    }
                    AppLog.add("PROX WAKELOCK STATE LISTENER: attivo (API ${Build.VERSION.SDK_INT})")
                } catch (t: Throwable) {
                    AppLog.add("PROX WAKELOCK STATE LISTENER non disponibile: ${t.javaClass.simpleName}: ${t.message}")
                }
            } else {
                AppLog.add("PROX WAKELOCK STATE LISTENER: non disponibile su API ${Build.VERSION.SDK_INT}")
            }
        }

        if (proximityListenerRegistered) {
            AppLog.add("PROX SERVICE già armato: listener TYPE_PROXIMITY attivo")
            return
        }

        val sm = getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        sensorManager = sm
        proximitySensor = sensor

        if (sensor != null) {
            AppLog.add(
                "PROX SENSOR HW: name=${sensor.name} vendor=${sensor.vendor} " +
                    "version=${sensor.version} max=${sensor.maximumRange} reportingMode=${sensor.reportingMode}"
            )
        }

        if (sensor == null) {
            // Compatibility fallback: retain the legacy Android-managed proximity wake lock
            // if this device exposes no public TYPE_PROXIMITY sensor.
            val wl = proximityWakeLock
            if (wl != null && !wl.isHeld) wl.acquire()
            AppLog.add("PROX SENSOR SERVICE non disponibile: fallback wake lock prossimità Android")
            return
        }

        proximityNear = null
        proximityListenerRegistered = sm.registerListener(
            proximityListener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
            Handler(Looper.getMainLooper())
        )

        if (proximityListenerRegistered) {
            AppLog.add(
                "PROX SERVICE ARMATO: TYPE_PROXIMITY nel foreground service; " +
                    "resta attivo anche fuori da MotoLink (max=${sensor.maximumRange})"
            )
            acquireContinuousProximityWakeLock()
        } else {
            // If an OEM refuses explicit sensor registration, preserve the Android-managed path.
            acquireContinuousProximityWakeLock()
            AppLog.add("PROX SENSOR SERVICE registrazione fallita: wake lock prossimità continuo mantenuto")
        }
    }

    private fun acquireContinuousProximityWakeLock() {
        val wl = proximityWakeLock ?: return
        logProximityPowerPath("CONTINUOUS_BEFORE_ACQUIRE")
        if (!wl.isHeld) {
            wl.acquire()
            AppLog.add(
                "PROX CONTINUOUS HOLD: wake lock prossimità acquisito mentre MotoLink è foreground; " +
                    "resta acquisito fino a STOP/teardown"
            )
        } else {
            AppLog.add("PROX CONTINUOUS HOLD: wake lock prossimità era già acquisito")
        }
        scheduleProximityPowerPathSnapshots("CONTINUOUS_AFTER_ACQUIRE")
    }

    private fun launchProximityTopGate() {
        if (ProximityGateActivity.isActive()) {
            AppLog.add("PROX GATE REQUEST: Activity trasparente già attiva")
            return
        }
        logProximityPowerPath("GATE_REQUEST_BEFORE_START")
        AppLog.add(
            "PROX GATE REQUEST: NEAR con wake-lock framework non ENABLED -> " +
                "avvio Tiny Gate 1x1 per riprendere TOP_RESUMED (ZERO PIP)"
        )
        try {
            startActivity(Intent(this, ProximityGateActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            })
            AppLog.add("PROX GATE REQUEST: startActivity accettata dal sistema")
            scheduleProximityPowerPathSnapshots("GATE_REQUEST_AFTER_START")
        } catch (t: Throwable) {
            AppLog.add(
                "PROX GATE REQUEST FALLITA: ${t.javaClass.simpleName}: ${t.message ?: "-"}; " +
                    "Android/HyperOS ha bloccato l'avvio Activity dal contesto corrente"
            )
            scheduleProximityPowerPathSnapshots("GATE_REQUEST_FAILED")
        }
    }

    private fun releaseProximityScreenOff() {
        proximityDiagHandler.removeCallbacksAndMessages(null)
        if (proximityListenerRegistered) {
            try { sensorManager?.unregisterListener(proximityListener) } catch (_: Throwable) {}
            proximityListenerRegistered = false
            AppLog.add("PROX SENSOR SERVICE DISARMATO: listener rimosso")
        }
        ProximityGateActivity.finishIfActive("PROX teardown")
        proximityNear = null
        proximityWakeLockFrameworkEnabled = null
        proximitySensor = null
        sensorManager = null

        val wl = proximityWakeLock
        if (wl != null && wl.isHeld) {
            wl.release()
            AppLog.add("PROX OFF DISARMATO: wake lock prossimità rilasciato")
        } else {
            AppLog.add("PROX OFF già disarmato")
        }
    }

    private fun observedVolumeStreams(): IntArray = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_VOICE_CALL
    )

    private fun volumeStreamName(stream: Int): String = when (stream) {
        AudioManager.STREAM_MUSIC -> "MUSIC"
        AudioManager.STREAM_RING -> "RING"
        AudioManager.STREAM_NOTIFICATION -> "NOTIFICATION"
        AudioManager.STREAM_SYSTEM -> "SYSTEM"
        AudioManager.STREAM_ALARM -> "ALARM"
        AudioManager.STREAM_VOICE_CALL -> "VOICE_CALL"
        else -> "STREAM_$stream"
    }

    private fun armManualDisplayUnlockFailsafe() {
        if (manualUnlockFailsafeReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        runCatching {
            // ACTION_USER_PRESENT is a protected system-only broadcast. Registering the
            // system-only filter directly avoids exporting this emergency receiver.
            @Suppress("DEPRECATION")
            registerReceiver(manualUnlockFailsafeReceiver, filter)
        }.onSuccess {
            manualUnlockFailsafeReceiverRegistered = true
            AppLog.add(
                "DISPLAY MANUALE FAILSAFE: sblocco reale utente armato; " +
                    "Power/SCREEN_OFF da solo NON disattiva overlay"
            )
        }.onFailure {
            manualUnlockFailsafeReceiverRegistered = false
            AppLog.add(
                "DISPLAY MANUALE FAILSAFE: receiver USER_PRESENT non disponibile: " +
                    "${it.javaClass.simpleName}: ${it.message ?: "-"}"
            )
        }
    }

    private fun disarmManualDisplayUnlockFailsafe() {
        if (!manualUnlockFailsafeReceiverRegistered) return
        runCatching { unregisterReceiver(manualUnlockFailsafeReceiver) }
        manualUnlockFailsafeReceiverRegistered = false
    }

    private fun armManualDisplayGesture() {
        if (manualVolumeReceiverRegistered) return

        val initial = linkedMapOf<Int, Int>()
        val am = getSystemService(AudioManager::class.java)
        if (am != null) {
            for (stream in observedVolumeStreams()) {
                val value = runCatching { am.getStreamVolume(stream) }.getOrDefault(-1)
                if (value >= 0) initial[stream] = value
            }
        }
        lastObservedVolumes.clear()
        lastObservedVolumes.putAll(initial)
        volumeDownFirstAt = 0L
        volumeGestureCooldownUntil = 0L
        lastAcceptedVolumeDownAt = 0L

        val filter = IntentFilter(ACTION_VOLUME_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(manualVolumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(manualVolumeReceiver, filter)
            }
        }.onSuccess {
            manualVolumeReceiverRegistered = true
            val values = initial.entries.joinToString(",") {
                "${volumeStreamName(it.key)}=${it.value}"
            }
            AppLog.add(
                "DISPLAY MANUALE VOLUME: receiver VOLUME_CHANGED_ACTION ARMATO; " +
                    "Accessibility=OFF; polling=OFF; doppio Volume Giù entro " +
                    "${DOUBLE_VOLUME_DOWN_WINDOW_MS}ms; initial=[$values]"
            )
        }.onFailure {
            manualVolumeReceiverRegistered = false
            AppLog.add(
                "DISPLAY MANUALE VOLUME: receiver NON disponibile: " +
                    "${it.javaClass.simpleName}: ${it.message ?: "-"}; " +
                    "nessun fallback Accessibility/polling"
            )
        }
    }

    private fun disarmManualDisplayGesture(reason: String, preserveBlackoutUntilUnlock: Boolean = false) {
        if (manualVolumeReceiverRegistered) {
            runCatching { unregisterReceiver(manualVolumeReceiver) }
            manualVolumeReceiverRegistered = false
        }
        volumeDownFirstAt = 0L
        volumeGestureCooldownUntil = 0L
        lastAcceptedVolumeDownAt = 0L
        if (!preserveBlackoutUntilUnlock) {
            deactivateManualDisplayBlackout(reason)
        } else if (manualDisplayBlackoutActive || manualBlackOverlayView != null) {
            AppLog.add(
                "DISPLAY MANUALE FAILSAFE: blocco schermo rilevato; overlay mantenuto fino a sblocco reale"
            )
        }
        lastObservedVolumes.clear()
        AppLog.add("DISPLAY MANUALE VOLUME: receiver disarmato [$reason]")
    }

    private fun handleObservedVolumeDown(
        stream: Int,
        previousVolume: Int,
        currentVolume: Int,
        source: String
    ) {
        if (projection == null) return

        val now = SystemClock.elapsedRealtime()
        val sinceAccepted = now - lastAcceptedVolumeDownAt
        if (lastAcceptedVolumeDownAt > 0L && sinceAccepted in 0..VOLUME_EVENT_DEDUP_MS) {
            AppLog.add(
                "DISPLAY MANUALE VOLUME: duplicato ignorato source=$source " +
                    "stream=${volumeStreamName(stream)} $previousVolume->$currentVolume"
            )
            return
        }
        lastAcceptedVolumeDownAt = now

        if (now < volumeGestureCooldownUntil) {
            AppLog.add(
                "DISPLAY MANUALE VOLUME: Volume Giù in cooldown source=$source " +
                    "stream=${volumeStreamName(stream)} $previousVolume->$currentVolume"
            )
            return
        }

        val firstAt = volumeDownFirstAt
        if (firstAt > 0L) {
            val gap = now - firstAt
            if (gap in DOUBLE_VOLUME_DOWN_MIN_GAP_MS..DOUBLE_VOLUME_DOWN_WINDOW_MS) {
                volumeDownFirstAt = 0L
                volumeGestureCooldownUntil = now + VOLUME_GESTURE_COOLDOWN_MS
                AppLog.add(
                    "DISPLAY MANUALE VOLUME: doppio Volume Giù rilevato gap=${gap}ms source=$source " +
                        "stream=${volumeStreamName(stream)} $previousVolume->$currentVolume"
                )
                toggleManualDisplayBlackout()
                return
            }

            if (gap < DOUBLE_VOLUME_DOWN_MIN_GAP_MS) {
                AppLog.add(
                    "DISPLAY MANUALE VOLUME: repeat/alias ignorato gap=${gap}ms source=$source " +
                        "stream=${volumeStreamName(stream)} $previousVolume->$currentVolume"
                )
                return
            }
        }

        volumeDownFirstAt = now
        AppLog.add(
            "DISPLAY MANUALE VOLUME: primo Volume Giù osservato source=$source " +
                "stream=${volumeStreamName(stream)} $previousVolume->$currentVolume"
        )
    }

    private inner class TouchBlockingBlackOverlay(context: Context) : FrameLayout(context) {
        init {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> AppLog.add(
                    "DISPLAY MANUALE OVERLAY TOUCH: DOWN x=${event.x.toInt()} y=${event.y.toInt()}"
                )
                MotionEvent.ACTION_UP -> AppLog.add(
                    "DISPLAY MANUALE OVERLAY TOUCH: UP x=${event.x.toInt()} y=${event.y.toInt()}"
                )
                MotionEvent.ACTION_CANCEL -> AppLog.add(
                    "DISPLAY MANUALE OVERLAY TOUCH: CANCEL"
                )
            }
            return true
        }
    }

    private fun toggleManualDisplayBlackout() {
        if (manualDisplayBlackoutActive && manualBlackOverlayView == null) {
            manualDisplayBlackoutActive = false
        }
        if (manualDisplayBlackoutActive) {
            deactivateManualDisplayBlackout("doppio Volume Giù")
        } else {
            activateManualDisplayBlackout()
        }
    }

    private fun activateManualDisplayBlackout() {
        if (projection == null) {
            AppLog.add("DISPLAY MANUALE OVERLAY: ignorato, mirroring non attivo")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AppLog.add(
                "DISPLAY MANUALE OVERLAY: SYSTEM_ALERT_WINDOW NON consentito; " +
                    "abilitare 'Mostra sopra altre app' per MotoLink"
            )
            return
        }
        if (manualBlackOverlayView != null) return

        try {
            val wm = getSystemService(WindowManager::class.java)
                ?: throw IllegalStateException("WindowManager non disponibile")

            val black = TouchBlockingBlackOverlay(this).apply {
                systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                title = "MotoLinkLocalBlackOverlay"
            }

            wm.addView(black, params)
            manualBlackOverlayWindowManager = wm
            manualBlackOverlayView = black
            manualDisplayBlackoutActive = true
            AppLog.add(
                "DISPLAY MANUALE OVERLAY: NERO FULLSCREEN + CUSTOM TOUCH BLOCK ATTIVO; " +
                    "FrameLayout intercept/onTouch; TYPE_APPLICATION_OVERLAY; " +
                    "FLAG_SPLIT_TOUCH"
            )
        } catch (t: Throwable) {
            manualDisplayBlackoutActive = false
            manualBlackOverlayView = null
            manualBlackOverlayWindowManager = null
            AppLog.add(
                "DISPLAY MANUALE OVERLAY: avvio fallito: ${t.javaClass.simpleName}: ${t.message ?: "-"}"
            )
        }
    }

    private fun deactivateManualDisplayBlackout(reason: String) {
        val view = manualBlackOverlayView
        val wm = manualBlackOverlayWindowManager
        val wasActive = manualDisplayBlackoutActive || view != null
        manualDisplayBlackoutActive = false
        manualBlackOverlayView = null
        manualBlackOverlayWindowManager = null
        if (view != null && wm != null) {
            runCatching { wm.removeViewImmediate(view) }
                .onFailure {
                    AppLog.add(
                        "DISPLAY MANUALE OVERLAY: rimozione warning [$reason]: " +
                            "${it.javaClass.simpleName}: ${it.message ?: "-"}"
                    )
                }
        }
        if (wasActive) AppLog.add("DISPLAY MANUALE OVERLAY: DISATTIVATO [$reason]")
    }

    private data class AvcEncoderChoice(
        val codec: MediaCodec,
        val info: MediaCodecInfo?,
        val software: Boolean
    )

    /**
     * Valico/DS900X compatibility path: prefer a public Android software AVC encoder
     * with Surface input. The implementation uses only Android platform codecs.
     * The normal Trofeo/non-P2P path remains MediaCodec.createEncoderByType().
     */
    private fun createAvcEncoder(preferSoftware: Boolean): AvcEncoderChoice {
        if (!preferSoftware) {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            return AvcEncoderChoice(c, c.codecInfo, false)
        }

        val candidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } }
            .filter { info ->
                runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .colorFormats
                        .any { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface }
                }.getOrDefault(false)
            }
            .filter { info ->
                runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .videoCapabilities
                        .isSizeSupported(targetWidth, targetHeight)
                }.getOrDefault(false)
            }
            .filter { info -> info.isSoftwareOnly || (!info.isHardwareAccelerated && !info.isVendor) }
            .sortedWith(
                compareBy<MediaCodecInfo> {
                    when {
                        it.name.equals("c2.android.avc.encoder", ignoreCase = true) -> 0
                        it.name.equals("OMX.google.h264.encoder", ignoreCase = true) -> 1
                        it.name.contains("android", ignoreCase = true) -> 2
                        it.name.contains("google", ignoreCase = true) -> 3
                        else -> 4
                    }
                }.thenBy { it.name }
            )
            .toList()

        AppLog.add(
            "VALICO SOFT H264: candidati software Surface=${if (candidates.isEmpty()) "nessuno" else candidates.joinToString { it.name }}"
        )

        var lastError: Throwable? = null
        for (info in candidates) {
            try {
                val c = MediaCodec.createByCodecName(info.name)
                AppLog.add(
                    "VALICO SOFT H264: encoder selezionato=${info.name}; " +
                        "softwareOnly=${info.isSoftwareOnly}; hardware=${info.isHardwareAccelerated}; vendor=${info.isVendor}"
                )
                return AvcEncoderChoice(c, info, true)
            } catch (t: Throwable) {
                lastError = t
                AppLog.add("VALICO SOFT H264: ${info.name} non apribile (${t.javaClass.simpleName})")
            }
        }
        throw IllegalStateException(
            "Nessun encoder H264 software con input Surface disponibile",
            lastError
        )
    }

    private fun adaptiveBitrate(softMode: Boolean): Int {
        val basePixels = FALLBACK_WIDTH * FALLBACK_HEIGHT
        val targetPixels = targetWidth * targetHeight
        val base = if (softMode) VALICO_SOFT_BITRATE else BITRATE
        val scaled = (base.toLong() * targetPixels.toLong() / basePixels.toLong()).toInt()
        return if (softMode) scaled.coerceIn(3_000_000, 8_000_000) else scaled.coerceIn(4_000_000, 12_000_000)
    }

    private fun buildAvcFormat(softMode: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, adaptiveBitrate(softMode))
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            if (softMode) {
                val pixels = targetWidth * targetHeight
                setInteger(
                    MediaFormat.KEY_LEVEL,
                    if (pixels <= 1280 * 720) MediaCodecInfo.CodecProfileLevel.AVCLevel31
                    else MediaCodecInfo.CodecProfileLevel.AVCLevel4
                )
            }
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (!softMode) {
                setInteger(MediaFormat.KEY_LATENCY, REQUESTED_ENCODER_LATENCY_FRAMES)
            }
            if (Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, FPS.toFloat())
            }
        }

    private fun startProjection(intent: Intent) {
        if (projection != null || projectionStartPending) return
        projectionStartPending = true

        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MotoLink")
            .setContentText("Mirroring attivo verso la moto")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        lockPlaceholderActive = false
        valicoSoftH264 = intent.getBooleanExtra(EXTRA_VALICO_SOFT_H264, false)
        AppLog.add(
            if (valicoSoftH264)
                "VIDEO MODE V15: VALICO SOFTWARE H264 + SOURCE-NATIVE + UNIVERSAL AUTO-BASE + ADAPTATION"
            else
                "VIDEO MODE V15: SOURCE-NATIVE + UNIVERSAL AUTO-BASE + ADATTAMENTO; source crop OFF; no profili moto/app"
        )

        @Suppress("DEPRECATION")
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultData == null) {
            projectionStartPending = false
            AppLog.add("MediaProjection: token mancante")
            stopSelf()
            return
        }

        val initialTarget = AdaptiveDisplayTarget.snapshot()
        if (initialTarget.source != "fallback") {
            AppLog.add(
                "SOURCE NATIVE V15: area video già disponibile ${initialTarget.width}x${initialTarget.height} " +
                    "source=${initialTarget.source}; attendo comunque ${TBOX_GEOMETRY_SETTLE_MS}ms di stabilità"
            )
        }

        // Clean-room implementation of the useful MOTO-HUB architecture: let the dashboard
        // handshake tell us the video canvas BEFORE creating MediaCodec/VirtualDisplay. No source
        // code from MOTO-HUB is included or copied here.
        AppLog.add(
            "SOURCE NATIVE V15: attendo MEDIA_INIT/view-area live prima di creare encoder/VirtualDisplay " +
                "(timeout=${TBOX_GEOMETRY_WAIT_MS}ms)"
        )
        val tokenCopy = Intent(resultData)
        Thread({
            val deadline = SystemClock.elapsedRealtime() + TBOX_GEOMETRY_WAIT_MS
            var chosen = AdaptiveDisplayTarget.snapshot()
            var lastSignature = "${chosen.width}x${chosen.height}:${chosen.source}"
            var stableSince = 0L
            while (SystemClock.elapsedRealtime() < deadline) {
                val now = SystemClock.elapsedRealtime()
                chosen = AdaptiveDisplayTarget.snapshot()
                val signature = "${chosen.width}x${chosen.height}:${chosen.source}"
                if (chosen.source != "fallback") {
                    if (signature != lastSignature || stableSince == 0L) {
                        stableSince = now
                        lastSignature = signature
                    } else if (now - stableSince >= TBOX_GEOMETRY_SETTLE_MS) {
                        break
                    }
                }
                try { Thread.sleep(40L) } catch (_: InterruptedException) { break }
            }
            val finalTarget = chosen
            Handler(Looper.getMainLooper()).post {
                if (!projectionStartPending || projection != null) return@post
                if (finalTarget.source == "fallback") {
                    AppLog.add(
                        "SOURCE NATIVE V15: nessuna area live entro ${TBOX_GEOMETRY_WAIT_MS}ms; " +
                            "fallback ${finalTarget.width}x${finalTarget.height}"
                    )
                } else {
                    AppLog.add(
                        "SOURCE NATIVE V15: area live acquisita ${finalTarget.width}x${finalTarget.height} " +
                            "source=${finalTarget.source}; creo encoder solo ora"
                    )
                }
                beginProjectionWithTBoxCanvas(resultCode, tokenCopy)
            }
        }, "MotoLink-TBoxGeometryWait").apply { isDaemon = true }.start()
    }

    private fun beginProjectionWithTBoxCanvas(resultCode: Int, resultData: Intent) {
        if (projection != null) {
            projectionStartPending = false
            return
        }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        projection = mgr.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            projectionStartPending = false
            AppLog.add("MediaProjection: autorizzazione non valida")
            stopSelf()
            return
        }

        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (isRealScreenLockActive()) {
                    AppLog.add(
                        "BLOCCO SCHERMO REALE RILEVATO: Android ha terminato MediaProjection; " +
                            "mantengo EasyConn e mostro il placeholder lucchetto sul TFT"
                    )
                    enterLockPlaceholderMode()
                } else {
                    AppLog.add("MediaProjection terminata dal sistema/utente")
                    AppLog.markMirrorSessionStopped("MediaProjection terminata")
                    shutdown(stopProjection = false)
                    stopSelf()
                }
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                if (Build.VERSION.SDK_INT < 34) return
                if (capturedContentVisible == isVisible) return
                capturedContentVisible = isVisible
                if (!isVisible) {
                    pipProbeLive = false
                    pipProbeAwaitKeyFrame = false
                    pipProbeCandidateStreak = 0
                    resumeNeedsKeyFrame = false
                    AppLog.add("APP CAPTURE NON VISIBILE: verifico eventuale continuazione reale; fallback ultimo frame valido")
                } else {
                    pipProbeLive = false
                    pipProbeAwaitKeyFrame = false
                    pipProbeCandidateStreak = 0
                    resumeNeedsKeyFrame = true
                    AppLog.add("APP CAPTURE VISIBILE: ripresa contenuto; richiedo IDR prima di riattivare il flusso")
                    requestImmediateSyncFrame("app capture tornata visibile")
                }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                if (Build.VERSION.SDK_INT < 34 || width < 16 || height < 16) return

                if (width == sourceWidth && height == sourceHeight && pendingGeometryWidth == 0) {
                    AppLog.add("ROTATION ATOMIC V15: resize duplicato ignorato ${width}x${height}")
                    return
                }

                pendingGeometryWidth = width
                pendingGeometryHeight = height
                geometryGeneration += 1L
                val generation = geometryGeneration
                coverRenderer?.enterGeometryHold(width, height)

                geometrySettleRunnable?.let { geometryHandler.removeCallbacks(it) }
                val settle = Runnable {
                    if (generation != geometryGeneration) return@Runnable
                    val settledW = pendingGeometryWidth
                    val settledH = pendingGeometryHeight
                    if (settledW < 16 || settledH < 16) return@Runnable

                    if (settledW == sourceWidth && settledH == sourceHeight) {
                        pendingGeometryWidth = 0
                        pendingGeometryHeight = 0
                        coverRenderer?.cancelGeometryTransition("settled su geometria già attiva")
                        AppLog.add("ROTATION ATOMIC V15: debounce -> geometria invariata ${settledW}x${settledH}")
                        return@Runnable
                    }

                    val renderer = coverRenderer
                    if (renderer == null) {
                        runCatching { display?.resize(settledW, settledH, captureDpi) }
                            .onSuccess {
                                sourceWidth = settledW
                                sourceHeight = settledH
                                pendingGeometryWidth = 0
                                pendingGeometryHeight = 0
                                requestImmediateSyncFrame("rotation V15 direct-surface")
                            }
                            .onFailure { AppLog.add("ROTATION ATOMIC V15: resize direct fallito: ${it.javaClass.simpleName}") }
                        return@Runnable
                    }

                    // Keep the MediaProjection producer in the phone/source geometry. SurfaceTexture
                    // is resized first, then VirtualDisplay, then three transition frames are consumed
                    // before the new orientation can reach H264.
                    renderer.prepareSourceResize(settledW, settledH) {
                        geometryHandler.post {
                            if (generation != geometryGeneration) return@post
                            runCatching { display?.resize(settledW, settledH, captureDpi) }
                                .onSuccess {
                                    sourceWidth = settledW
                                    sourceHeight = settledH
                                    pendingGeometryWidth = 0
                                    pendingGeometryHeight = 0
                                    // V15 orientation split: switch the adaptation profile while the renderer
                                    // is still holding/dropping transition frames. LANDSCAPE settings therefore
                                    // never leak into PORTRAIT (and vice versa) on the visible TFT frame.
                                    applyAdaptationRuntime("orientation switch ${settledW}x${settledH}")
                                    renderer.notifyProducerResized(settledW, settledH)
                                    AppLog.add(
                                        "ROTATION ATOMIC V15: VirtualDisplay SOURCE=${settledW}x${settledH}; " +
                                            "encoder TFT resta ${targetWidth}x${targetHeight}; attendo frame stabili"
                                    )
                                }
                                .onFailure {
                                    renderer.cancelGeometryTransition("VirtualDisplay.resize fallito")
                                    AppLog.add("ROTATION ATOMIC V15: VirtualDisplay resize fallito: ${it.javaClass.simpleName}")
                                }
                        }
                    }
                }
                geometrySettleRunnable = settle
                geometryHandler.postDelayed(settle, 220L)
                AppLog.add(
                    "ROTATION ATOMIC V15: candidato SOURCE=${width}x${height}; debounce=220ms; " +
                        "ultimo=${sourceWidth}x${sourceHeight}; target=${targetWidth}x${targetHeight}"
                )
            }
        }, Handler(Looper.getMainLooper()))

        captureDpi = resources.configuration.densityDpi
        val target = AdaptiveDisplayTarget.snapshot()
        targetWidth = target.width
        targetHeight = target.height
        val dm = resources.displayMetrics
        sourceWidth = dm.widthPixels.coerceAtLeast(16)
        sourceHeight = dm.heightPixels.coerceAtLeast(16)
        AppLog.add(
            "SOURCE NATIVE V15 RESOLVE: TFT=${targetWidth}x${targetHeight} source=${target.source}; " +
                "captureSOURCE=${sourceWidth}x${sourceHeight}; landscape=UNIVERSAL_AUTO_BASE portrait=FIT; sourceCrop=OFF zoom=IGNORED; " +
                "androidLetterboxPrevention=SOURCE_NATIVE"
        )

        capturedContentVisible = true
        resumeNeedsKeyFrame = false
        hiddenSamples.set(0L)
        pipForwardedSamples.set(0L)
        pipProbeLive = false
        pipProbeAwaitKeyFrame = false
        pipProbeCandidateStreak = 0
        visibleSampleSizeEwma = 0.0
        H264FrameBus.resetAll()

        try {
            val choice = createAvcEncoder(valicoSoftH264)
            codec = choice.codec
            val format = buildAvcFormat(choice.software)
            codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec!!.createInputSurface()
            codec!!.start()

            autoActiveAreaCoverRenderer = null
            val renderer = runCatching {
                val r = StaticCoverRenderer(
                    encoderSurface = inputSurface!!,
                    initialSourceWidth = sourceWidth,
                    initialSourceHeight = sourceHeight,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    initialViewportWidth = targetWidth,
                    initialViewportHeight = targetHeight,
                    initialZoomPercent = 100,
                    initialOffsetX = 0,
                    initialOffsetY = 0,
                    initialMarginLeft = 0,
                    initialMarginTop = 0,
                    initialMarginRight = 0,
                    initialMarginBottom = 0,
                    onGeometryCommitted = { committedW, committedH ->
                        geometryHandler.post {
                            AppLog.add(
                                "ROTATION ATOMIC V15: COMMIT SOURCE ${committedW}x${committedH}; " +
                                    "profilo=${if (committedW >= committedH) "LANDSCAPE" else "PORTRAIT"}; richiedo IDR"
                            )
                            requestImmediateSyncFrame("rotation atomic V15 commit")
                        }
                    }
                )
                Pair(r, r.inputSurface)
            }.onFailure {
                AppLog.add(
                    "SOURCE NATIVE V15 compositor non disponibile (${it.javaClass.simpleName}); " +
                        "fallback Surface diretta"
                )
            }.getOrNull()

            coverRenderer = renderer?.first
            projectionInputSurface = renderer?.second ?: inputSurface

            // Critical source-native rule: VirtualDisplay uses the SOURCE/phone geometry, never the T-Box
            // encoder geometry. Android therefore does not pre-letterbox Maps into 800x480.
            display = projection!!.createVirtualDisplay(
                "MotoLinkSourceNativeCapture",
                sourceWidth,
                sourceHeight,
                captureDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                projectionInputSurface,
                null,
                Handler(Looper.getMainLooper())
            )

            applyAdaptationRuntime("projection ready")
            startDrain()
            AppLog.add(
                if (valicoSoftH264)
                    "SOURCE NATIVE V15 READY -> VALICO SOFT H264 ${targetWidth}x${targetHeight}@$FPS; " +
                        "VirtualDisplay=${sourceWidth}x${sourceHeight} -> GPU -> TFT; no profili"
                else
                    "SOURCE NATIVE V15 READY -> H264 ${targetWidth}x${targetHeight}@$FPS; " +
                        "VirtualDisplay=${sourceWidth}x${sourceHeight} -> GPU -> TFT; no profili"
            )
            armManualDisplayGesture()
            AppLog.add(
                "DISPLAY MANUALE: BLACK OVERLAY locale pronto; trigger=VOLUME_CHANGED_ACTION; " +
                    "Accessibility=OFF; polling=OFF"
            )
        } catch (t: Throwable) {
            AppLog.add("Encoder/proiezione errore: ${t.message ?: t.javaClass.simpleName}")
            shutdown()
            stopSelf()
        }
    }

    private fun isLandscapeSource(): Boolean = sourceWidth >= sourceHeight

    private fun activeAdaptationProfile(): MirrorAdaptationConfig.Profile =
        if (isLandscapeSource()) MirrorAdaptationConfig.Profile.LANDSCAPE else MirrorAdaptationConfig.Profile.PORTRAIT

    private fun applyAdaptationRuntime(reason: String) {
        val profile = activeAdaptationProfile()
        val config = MirrorAdaptationConfig.load(this, profile)
        coverRenderer?.updateEdgeAdaptation(
            left = config.leftPx,
            top = config.topPx,
            right = config.rightPx,
            bottom = config.bottomPx,
            enabled = config.enabled
        )
        if (config.enabled && projection != null) {
            showAdaptationOverlay()
        } else {
            hideAdaptationOverlay(reason)
        }

        val autoBase = if (config.profile == MirrorAdaptationConfig.Profile.LANDSCAPE && config.enabled) {
            MirrorAdaptationConfig.landscapeAutoFrameFor(targetWidth, targetHeight)
        } else {
            MirrorAdaptationConfig.AutoFrame(0, 0, targetWidth, targetHeight)
        }
        val effectiveX = autoBase.x - if (config.enabled) config.leftPx else 0
        val effectiveY = autoBase.y - if (config.enabled) config.bottomPx else 0
        val effectiveW = (autoBase.width + if (config.enabled) config.netHorizontalPx else 0).coerceAtLeast(16)
        val effectiveH = (autoBase.height + if (config.enabled) config.netVerticalPx else 0).coerceAtLeast(16)

        AppLog.add(
            "ADATTAMENTO V15 STATE: reason=$reason orientation=${config.profile} enabled=${config.enabled}; " +
                "autoBase=${autoBase.width}x${autoBase.height}@${autoBase.x},${autoBase.y}; " +
                "extra L=${MirrorAdaptationConfig.signed(config.leftPx)} T=${MirrorAdaptationConfig.signed(config.topPx)} " +
                "R=${MirrorAdaptationConfig.signed(config.rightPx)} B=${MirrorAdaptationConfig.signed(config.bottomPx)}; " +
                "totale_extra=${MirrorAdaptationConfig.signed(config.totalNetPx)}px; " +
                "viewport_effettivo=${effectiveW}x${effectiveH}@${effectiveX},${effectiveY}; target=${targetWidth}x${targetHeight}; " +
                "autoGeometry=${if (config.profile == MirrorAdaptationConfig.Profile.LANDSCAPE) "TBOX_RUNTIME_SCALE" else "PORTRAIT_FIT"}"
        )
    }

    private fun adjustAdaptationEdge(edge: String) {
        val delta = if (adaptationReverseMode) -MirrorAdaptationConfig.STEP_PX else MirrorAdaptationConfig.STEP_PX
        val profile = activeAdaptationProfile()
        val config = MirrorAdaptationConfig.adjustEdge(this, profile, edge, delta)
        coverRenderer?.updateEdgeAdaptation(
            config.leftPx,
            config.topPx,
            config.rightPx,
            config.bottomPx,
            config.enabled
        )

        val autoBase = if (config.profile == MirrorAdaptationConfig.Profile.LANDSCAPE && config.enabled) {
            MirrorAdaptationConfig.landscapeAutoFrameFor(targetWidth, targetHeight)
        } else {
            MirrorAdaptationConfig.AutoFrame(0, 0, targetWidth, targetHeight)
        }
        val effectiveX = autoBase.x - config.leftPx
        val effectiveY = autoBase.y - config.bottomPx
        val effectiveW = (autoBase.width + config.netHorizontalPx).coerceAtLeast(16)
        val effectiveH = (autoBase.height + config.netVerticalPx).coerceAtLeast(16)
        val arrow = when (edge.uppercase()) {
            "TOP" -> "↑"
            "BOTTOM" -> "↓"
            "LEFT" -> "←"
            "RIGHT" -> "→"
            else -> edge
        }
        AppLog.add(
            "ADATTAMENTO V15 EDGE: orientation=${config.profile} $arrow step=${MirrorAdaptationConfig.signed(delta)}px mode=${if (adaptationReverseMode) "RESTRINGI" else "ALLARGA"}; " +
                "autoBase=${autoBase.width}x${autoBase.height}@${autoBase.x},${autoBase.y}; " +
                "extra L=${MirrorAdaptationConfig.signed(config.leftPx)} T=${MirrorAdaptationConfig.signed(config.topPx)} " +
                "R=${MirrorAdaptationConfig.signed(config.rightPx)} B=${MirrorAdaptationConfig.signed(config.bottomPx)}; " +
                "totale_extra=${MirrorAdaptationConfig.signed(config.totalNetPx)}px; " +
                "viewport_effettivo=${effectiveW}x${effectiveH}@${effectiveX},${effectiveY}"
        )
    }

    private fun showAdaptationOverlay() {
        if (adaptationOverlayView != null) return
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            AppLog.add("ADATTAMENTO V15 PANEL: permesso 'Mostra sopra altre app' assente; pannello non mostrato")
            return
        }

        val wm = getSystemService(WindowManager::class.java)
        val config = MirrorAdaptationConfig.load(this, activeAdaptationProfile())
        adaptationReverseMode = false

        fun bg(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

        fun key(text: String): TextView = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 25f
            gravity = Gravity.CENTER
            background = bg(0xE61A211A.toInt(), 0xFF5BFF2D.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        fun headerKey(text: String, stroke: Int): TextView = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            background = bg(0xE61A211A.toInt(), stroke)
            setPadding(dp(2), dp(1), dp(2), dp(1))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg(0xE8070A08.toInt(), 0xFF4D5F40.toInt())
            setPadding(dp(7), dp(6), dp(7), dp(7))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val info = headerKey("ⓘ", 0xFF5BFF2D.toInt()).apply {
            contentDescription = "Informazioni Adattamento"
        }
        val handle = TextView(this).apply {
            text = "Adattamento"
            setTextColor(0xFFE5EFE5.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(3), dp(6), dp(3))
            contentDescription = "Trascina pannello Adattamento"
        }
        val close = headerKey("×", 0xFFFF6A5C.toInt()).apply {
            contentDescription = "Chiudi e disattiva Adattamento"
        }
        header.addView(info, LinearLayout.LayoutParams(dp(36), dp(32)))
        header.addView(handle, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
            marginStart = dp(5)
            marginEnd = dp(5)
        })
        header.addView(close, LinearLayout.LayoutParams(dp(36), dp(32)))
        root.addView(header, LinearLayout.LayoutParams(dp(232), dp(34)))

        val up = key("↑")
        root.addView(up, LinearLayout.LayoutParams(dp(48), dp(44)).apply { topMargin = dp(4) })

        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val left = key("←")
        val center = key("+")
        val right = key("→")
        middle.addView(left, LinearLayout.LayoutParams(dp(48), dp(44)))
        middle.addView(center, LinearLayout.LayoutParams(dp(48), dp(44)).apply {
            marginStart = dp(3); marginEnd = dp(3)
        })
        middle.addView(right, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(middle, LinearLayout.LayoutParams(dp(150), dp(44)))

        val down = key("↓")
        root.addView(down, LinearLayout.LayoutParams(dp(48), dp(44)))

        val infoText = TextView(this).apply {
            text = MirrorAdaptationConfig.USER_HELP_TEXT
            setTextColor(0xFFF1F6F1.toInt())
            textSize = 12.5f
            gravity = Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        val infoCard = ScrollView(this).apply {
            background = bg(0xF20D140D.toInt(), 0xFF5BFF2D.toInt())
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            visibility = View.GONE
            addView(infoText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(
            infoCard,
            LinearLayout.LayoutParams(dp(232), dp(190)).apply {
                topMargin = dp(7)
            }
        )

        up.setOnClickListener { adjustAdaptationEdge("TOP") }
        down.setOnClickListener { adjustAdaptationEdge("BOTTOM") }
        left.setOnClickListener { adjustAdaptationEdge("LEFT") }
        right.setOnClickListener { adjustAdaptationEdge("RIGHT") }
        center.setOnClickListener {
            adaptationReverseMode = !adaptationReverseMode
            center.text = if (adaptationReverseMode) "−" else "+"
            center.background = if (adaptationReverseMode) {
                bg(0xE64A241A.toInt(), 0xFFFFB24D.toInt())
            } else {
                bg(0xE61A211A.toInt(), 0xFF5BFF2D.toInt())
            }
            AppLog.add(
                "ADATTAMENTO V15 PANEL: centro=${if (adaptationReverseMode) "ATTIVO/RESTRINGI" else "SPENTO/ALLARGA"}; step=${MirrorAdaptationConfig.STEP_PX}px"
            )
        }
        info.setOnClickListener {
            val opening = infoCard.visibility != View.VISIBLE
            infoCard.visibility = if (opening) View.VISIBLE else View.GONE
            AppLog.add("ADATTAMENTO V19 PANEL INFO: ${if (opening) "aperta/scroll" else "chiusa"}")
        }
        close.setOnClickListener {
            MirrorAdaptationConfig.setEnabled(this, false)
            AppLog.add("ADATTAMENTO V19 PANEL: X premuta -> Adattamento disattivato nelle Impostazioni")
            applyAdaptationRuntime("X pannello")
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = config.panelX
            y = config.panelY
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = params.x
        var startY = params.y
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downRawX).toInt()
                    params.y = startY + (event.rawY - downRawY).toInt()
                    runCatching { wm.updateViewLayout(root, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    MirrorAdaptationConfig.savePanelPosition(this, params.x, params.y)
                    AppLog.add("ADATTAMENTO V15 PANEL: posizione salvata x=${params.x} y=${params.y}")
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(root, params) }
            .onSuccess {
                adaptationOverlayView = root
                adaptationOverlayWindowManager = wm
                adaptationOverlayParams = params
                AppLog.add(
                    "ADATTAMENTO V19 PANEL: mostrato; titolo=Adattamento; info=ⓘ scrollabile; close=X disabilita; " +
                        "frecce=${MirrorAdaptationConfig.STEP_PX}px; centro OFF=ALLARGA / ON=RESTRINGI"
                )
            }
            .onFailure {
                AppLog.add("ADATTAMENTO V15 PANEL: errore addView ${it.javaClass.simpleName}: ${it.message ?: "-"}")
            }
    }

    private fun hideAdaptationOverlay(reason: String) {
        val view = adaptationOverlayView ?: return
        val wm = adaptationOverlayWindowManager
        runCatching { wm?.removeViewImmediate(view) }
        adaptationOverlayView = null
        adaptationOverlayWindowManager = null
        adaptationOverlayParams = null
        adaptationReverseMode = false
        AppLog.add("ADATTAMENTO V15 PANEL: nascosto ($reason)")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun isRealScreenLockActive(): Boolean {
        return try {
            val keyguard = getSystemService(KeyguardManager::class.java)
            keyguard?.isDeviceLocked == true || keyguard?.isKeyguardLocked == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Android can invalidate MediaProjection as soon as the real keyguard is engaged.
     * We cannot and must not capture content underneath that lock. Instead, keep the
     * already-established EasyConn transport alive and replace the video with a local,
     * pre-encoded lock notice containing no captured user data.
     */
    private fun enterLockPlaceholderMode() {
        if (lockPlaceholderActive) return
        lockPlaceholderActive = true

        val lockAu = try {
            resources.openRawResource(R.raw.motolink_lock_placeholder).use { it.readBytes() }
        } catch (t: Throwable) {
            AppLog.add("LOCK PLACEHOLDER asset non disponibile: ${t.javaClass.simpleName}: ${t.message ?: "-"}")
            null
        }
        val placeholderGeometryCompatible = targetWidth == FALLBACK_WIDTH && targetHeight == FALLBACK_HEIGHT
        val placeholderReady = placeholderGeometryCompatible && lockAu != null && H264FrameBus.activateSyntheticLockPlaceholder(lockAu)
        if (!placeholderGeometryCompatible) {
            AppLog.add(
                "LOCK PLACEHOLDER disabilitato per target dinamico ${targetWidth}x${targetHeight}: " +
                    "asset legacy ${FALLBACK_WIDTH}x${FALLBACK_HEIGHT} non compatibile"
            )
        }

        // Stop only the invalid screen-capture resources. EasyConnServers and the P2P/Wi-Fi
        // transport live outside this service and intentionally remain up so the TFT can
        // retain/receive the synthetic lock frame.
        draining.set(false)
        try { drainThread?.interrupt() } catch (_: Throwable) {}
        drainThread = null
        try { display?.release() } catch (_: Throwable) {}
        display = null
        try { coverRenderer?.release() } catch (_: Throwable) {}
        coverRenderer = null
        try { autoActiveAreaCoverRenderer?.release() } catch (_: Throwable) {}
        autoActiveAreaCoverRenderer = null
        projectionInputSurface = null
        try { inputSurface?.release() } catch (_: Throwable) {}
        inputSurface = null
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        projection = null
        disarmManualDisplayGesture("blocco schermo reale", preserveBlackoutUntilUnlock = true)
        releaseProximityScreenOff()
        proximityWakeLock = null

        capturedContentVisible = false
        resumeNeedsKeyFrame = false
        pipProbeLive = false
        pipProbeAwaitKeyFrame = false
        pipProbeCandidateStreak = 0

        if (placeholderReady) {
            AppLog.add(
                "LOCK PLACEHOLDER ATTIVO: TFT mostra 'TELEFONO BLOCCATO'; " +
                    "sblocca il telefono e premi START per riprendere la cattura"
            )
        } else {
            AppLog.add(
                "LOCK PLACEHOLDER NON INVIATO: consumer H264 non ancora attivo; " +
                    "EasyConn resta in attesa fino a STOP/nuovo START"
            )
        }
    }

    private fun startDrain() {
        draining.set(true)
        drainThread = Thread({
            val info = MediaCodec.BufferInfo()
            var frames = 0L
            var keyFrames = 0L
            var pFrames = 0L
            var bytes = 0L
            var accepted = 0L
            var syncRequests = 0L
            var lastLog = System.currentTimeMillis()
            try {
                while (draining.get()) {
                    val c = codec ?: break

                    // Consumer reconnect or bounded-queue recovery asks for a fresh IDR.
                    if (H264FrameBus.consumeSyncRequest()) {
                        try {
                            val params = Bundle().apply {
                                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                            }
                            c.setParameters(params)
                            syncRequests++
                            AppLog.add("H264 sync-frame richiesto all'encoder (reconnect/resync)")
                        } catch (t: Throwable) {
                            // Periodic 1 s IDR remains the fallback if the codec ignores/refuses this request.
                            AppLog.add("H264 sync-frame request non disponibile: ${t.javaClass.simpleName}")
                        }
                    }

                    when (val idx = c.dequeueOutputBuffer(info, 20_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = c.outputFormat
                            val csd0 = copyBuffer(f.getByteBuffer("csd-0"))
                            val csd1 = copyBuffer(f.getByteBuffer("csd-1"))
                            H264FrameBus.updateCodecConfig(csd0, csd1)
                            val actualLatency = if (f.containsKey(MediaFormat.KEY_LATENCY)) {
                                try { f.getInteger(MediaFormat.KEY_LATENCY).toString() } catch (_: Throwable) { "?" }
                            } else "n/a"
                            val codecName = runCatching { c.name }.getOrDefault("?")
                            val profile = if (f.containsKey(MediaFormat.KEY_PROFILE)) runCatching { f.getInteger(MediaFormat.KEY_PROFILE).toString() }.getOrDefault("?") else "n/a"
                            val level = if (f.containsKey(MediaFormat.KEY_LEVEL)) runCatching { f.getInteger(MediaFormat.KEY_LEVEL).toString() }.getOrDefault("?") else "n/a"
                            AppLog.add("H264 codec config pronto (codec=$codecName; profile=$profile; level=$level; SPS/PPS in RAM; encoderLatency=$actualLatency frame)")
                        }
                        else -> if (idx >= 0) {
                            val buffer = c.getOutputBuffer(idx)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val sample = ByteArray(info.size)
                                buffer.get(sample)

                                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                if (isConfig) {
                                    H264FrameBus.updateCodecConfig(sample)
                                } else {
                                    frames++
                                    bytes += sample.size
                                    if (isKey) keyFrames++ else pFrames++

                                    if (!capturedContentVisible) {
                                        // Best-effort PiP detection without Accessibility or private APIs.
                                        // A black/suspended app-sharing surface usually produces no samples
                                        // or only tiny highly-compressible samples. A continuing PiP stream
                                        // should produce several non-trivial samples. We only switch to live
                                        // pass-through after that pattern is observed, then start from an IDR.
                                        val baseline = if (visibleSampleSizeEwma > 0.0) visibleSampleSizeEwma else 8000.0
                                        val threshold = max(PIP_PROBE_MIN_SAMPLE_BYTES.toDouble(), baseline * PIP_PROBE_VISIBLE_RATIO)
                                        val plausibleContent = sample.size.toDouble() >= threshold

                                        if (!pipProbeLive) {
                                            pipProbeCandidateStreak = if (plausibleContent) pipProbeCandidateStreak + 1 else 0
                                            hiddenSamples.incrementAndGet()
                                            if (pipProbeCandidateStreak >= PIP_PROBE_REQUIRED_SAMPLES) {
                                                pipProbeLive = true
                                                pipProbeAwaitKeyFrame = true
                                                pipProbeCandidateStreak = 0
                                                AppLog.add("APP CAPTURE BACKGROUND: flusso reale rilevato; richiedo IDR per continuazione live")
                                                requestImmediateSyncFrame("app capture background")
                                            }
                                        } else if (pipProbeAwaitKeyFrame) {
                                            if (isKey && plausibleContent) {
                                                pipProbeAwaitKeyFrame = false
                                                if (H264FrameBus.pushEncodedSample(sample, true)) {
                                                    accepted++
                                                    pipForwardedSamples.incrementAndGet()
                                                }
                                                AppLog.add("APP CAPTURE BACKGROUND: key-frame valido, inoltro live alla Voge")
                                            } else {
                                                hiddenSamples.incrementAndGet()
                                            }
                                        } else {
                                            if (plausibleContent) {
                                                if (H264FrameBus.pushEncodedSample(sample, isKey)) {
                                                    accepted++
                                                    pipForwardedSamples.incrementAndGet()
                                                }
                                            } else {
                                                hiddenSamples.incrementAndGet()
                                            }
                                        }
                                    } else if (resumeNeedsKeyFrame) {
                                        if (isKey) {
                                            resumeNeedsKeyFrame = false
                                            AppLog.add("APP CAPTURE RESYNC: nuovo key-frame valido, inoltro H264 ripristinato")
                                            if (H264FrameBus.pushEncodedSample(sample, true)) accepted++
                                            visibleSampleSizeEwma = if (visibleSampleSizeEwma == 0.0) sample.size.toDouble() else visibleSampleSizeEwma * 0.9 + sample.size * 0.1
                                        } else {
                                            hiddenSamples.incrementAndGet()
                                        }
                                    } else {
                                        if (H264FrameBus.pushEncodedSample(sample, isKey)) accepted++
                                        visibleSampleSizeEwma = if (visibleSampleSizeEwma == 0.0) sample.size.toDouble() else visibleSampleSizeEwma * 0.9 + sample.size * 0.1
                                    }
                                }
                            }
                            c.releaseOutputBuffer(idx, false)

                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastLog >= 5000) {
                        AppLog.add("Encoder vivo: frame=$frames I=$keyFrames P=$pFrames accepted=$accepted ${bytes / 1024}KiB syncReq=$syncRequests hiddenHold=${hiddenSamples.get()} hiddenLive=${pipForwardedSamples.get()} | ${H264FrameBus.stats()}")
                        lastLog = now
                    }
                }
            } catch (t: Throwable) {
                if (draining.get()) AppLog.add("Drain encoder: ${t.javaClass.simpleName}: ${t.message ?: ""}")
            }
        }, "VogeMirror-H264-Drain").also { it.start() }
    }

    private fun requestImmediateSyncFrame(reason: String) {
        val c = codec ?: return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            c.setParameters(params)
            AppLog.add("H264 sync-frame richiesto all'encoder ($reason)")
        } catch (t: Throwable) {
            AppLog.add("H264 sync-frame request non disponibile ($reason): ${t.javaClass.simpleName}")
        }
    }

    private fun copyBuffer(src: ByteBuffer?): ByteArray? {
        if (src == null) return null
        val dup = src.duplicate()
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out
    }

    private fun shutdown(stopProjection: Boolean = true) {
        projectionStartPending = false
        geometrySettleRunnable?.let { geometryHandler.removeCallbacks(it) }
        geometrySettleRunnable = null
        pendingGeometryWidth = 0
        pendingGeometryHeight = 0
        // MediaProjection.stop() can invoke MediaProjection.Callback.onStop().
        // Guard against the callback re-entering shutdown while ACTION_STOP is already cleaning up.
        if (!shuttingDown.compareAndSet(false, true)) return
        try {
            hideAdaptationOverlay("shutdown")
            disarmManualDisplayGesture("shutdown")
            releaseProximityScreenOff()
            proximityWakeLock = null
            val hadResources = projection != null || codec != null || display != null || lockPlaceholderActive || H264FrameBus.lockPlaceholderActive()
            draining.set(false)
            try { drainThread?.interrupt() } catch (_: Throwable) {}
            drainThread = null
            try { display?.release() } catch (_: Throwable) {}
            display = null
            try { coverRenderer?.release() } catch (_: Throwable) {}
            coverRenderer = null
            try { autoActiveAreaCoverRenderer?.release() } catch (_: Throwable) {}
            autoActiveAreaCoverRenderer = null
            projectionInputSurface = null
            try { inputSurface?.release() } catch (_: Throwable) {}
            inputSurface = null
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            codec = null

            // Detach the reference before stop(): if Android calls onStop synchronously,
            // the callback sees an already-detached projection and cannot recurse on it.
            val oldProjection = projection
            projection = null
            if (stopProjection) {
                try { oldProjection?.stop() } catch (_: Throwable) {}
            }

            lockPlaceholderActive = false
            capturedContentVisible = true
            resumeNeedsKeyFrame = false
            hiddenSamples.set(0L)
            pipForwardedSamples.set(0L)
            pipProbeLive = false
            pipProbeAwaitKeyFrame = false
            pipProbeCandidateStreak = 0
            visibleSampleSizeEwma = 0.0
            H264FrameBus.resetAll()
            if (hadResources) AppLog.add("Mirror/encoder fermato; frame RAM eliminati")
        } finally {
            shuttingDown.set(false)
        }
    }

    override fun onDestroy() {
        shutdown()
        disarmManualDisplayUnlockFailsafe()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MotoLink", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
