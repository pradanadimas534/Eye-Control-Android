package com.dimas.eyecontrol.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.dimas.eyecontrol.MainActivity
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sign

class EyeTrackingService : LifecycleService(), FaceLandmarkerHelper.Listener {
    private lateinit var executor: ExecutorService
    private var helper: FaceLandmarkerHelper? = null
    private val estimator = GazeEstimator()
    private var lastPublishAt = 0L

    // v0.5: relative/joystick pointer state.
    private var cursorX = 0.5f
    private var cursorY = 0.5f
    private var filteredDirX = 0f
    private var filteredDirY = 0f
    private var lastControlAt = 0L
    private var wasCalibrationActive = false

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return Service.START_NOT_STICKY
        }
        startInForeground()
        if (helper == null) initializeTracking()
        return Service.START_NOT_STICKY
    }

    private fun startInForeground() {
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, EyeTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Eye Control aktif")
            .setContentText("Lihat arah untuk menggerakkan cursor; lihat tengah untuk berhenti.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun initializeTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        executor.execute {
            try {
                helper = FaceLandmarkerHelper(this, this)
                ContextCompat.getMainExecutor(this).execute { bindCamera() }
            } catch (_: Throwable) {
                EyeControlBus.setTracking(false)
                stopSelf()
            }
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                var lastAnalyzedAt = 0L
                analyzer.setAnalyzer(executor) { proxy ->
                    val now = SystemClock.uptimeMillis()
                    if (now - lastAnalyzedAt < 50L) {
                        proxy.close()
                    } else {
                        lastAnalyzedAt = now
                        helper?.detect(proxy) ?: proxy.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analyzer)
                EyeControlBus.setTracking(true)
            } catch (_: Throwable) {
                EyeControlBus.setTracking(false)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResult(result: FaceLandmarkerResult, input: MPImage) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPublishAt < 45L) return
        lastPublishAt = now

        val estimate = estimator.estimate(result, now) ?: run {
            EyeControlBus.publish(
                EyeControlBus.sample.value.copy(
                    faceDetected = false,
                    tracking = true,
                    timestampMs = now
                )
            )
            return
        }

        val calibrated = GazeCalibration.isCalibrated(this)
        val calibrationActive = EyeControlBus.calibrationActive

        if (calibrationActive) {
            // Keep the future cursor centered while the calibration UI learns anchors.
            cursorX = 0.5f
            cursorY = 0.5f
            filteredDirX = 0f
            filteredDirY = 0f
            lastControlAt = now
            wasCalibrationActive = true
        } else if (wasCalibrationActive) {
            // Start every new calibration from a predictable cursor position.
            cursorX = 0.5f
            cursorY = 0.5f
            filteredDirX = 0f
            filteredDirY = 0f
            lastControlAt = now
            wasCalibrationActive = false
        }

        var dirX = 0f
        var dirY = 0f
        if (calibrated && !calibrationActive && estimate.blinkScore < 0.58f) {
            val direction = GazeCalibration.direction(this, estimate.rawX, estimate.rawY)
            dirX = direction.first
            dirY = direction.second
        }

        val controlled = updateCursor(dirX, dirY, now, calibrated && !calibrationActive)

        EyeControlBus.publish(
            EyeControlBus.GazeSample(
                rawX = estimate.rawX,
                rawY = estimate.rawY,
                xNorm = controlled.first,
                yNorm = controlled.second,
                faceDetected = true,
                blinkScore = estimate.blinkScore,
                longBlinkEventId = estimate.longBlinkEventId,
                tracking = true,
                timestampMs = now
            )
        )
    }

    private fun updateCursor(dirX: Float, dirY: Float, now: Long, enabled: Boolean): Pair<Float, Float> {
        if (!enabled) {
            lastControlAt = now
            return cursorX to cursorY
        }

        if (lastControlAt == 0L) {
            lastControlAt = now
            return cursorX to cursorY
        }

        val dt = ((now - lastControlAt).coerceIn(0L, 120L) / 1000f)
        lastControlAt = now

        // Moving gaze is smoothed, but neutral gaze brakes very quickly. This removes
        // the floaty feeling from v0.4 while still suppressing noisy camera samples.
        filteredDirX = smoothDirection(filteredDirX, dirX)
        filteredDirY = smoothDirection(filteredDirY, dirY)

        val vx = velocity(filteredDirX, 0.55f)
        val vy = velocity(filteredDirY, 0.42f)

        cursorX = (cursorX + vx * dt).coerceIn(0.025f, 0.975f)
        cursorY = (cursorY + vy * dt).coerceIn(0.025f, 0.975f)

        return cursorX to cursorY
    }

    private fun smoothDirection(previous: Float, current: Float): Float {
        if (current == 0f) {
            val braked = previous * 0.28f
            return if (abs(braked) < 0.025f) 0f else braked
        }
        val alpha = if (sign(previous) != sign(current) && previous != 0f) 0.55f else 0.36f
        val value = previous + alpha * (current - previous)
        return if (abs(value) < 0.020f) 0f else value
    }

    /** Maximum speed is expressed as a fraction of the full screen per second. */
    private fun velocity(direction: Float, maxPerSecond: Float): Float {
        val magnitude = abs(direction)
        if (magnitude < 0.03f) return 0f
        val normalized = magnitude.coerceIn(0f, 1f)
        val shaped = normalized * normalized
        var speed = 0.075f + (maxPerSecond - 0.075f) * shaped
        if (magnitude > 1f) {
            speed += (magnitude - 1f).coerceAtMost(0.45f) * 0.12f
        }
        return sign(direction) * speed.coerceAtMost(maxPerSecond * 1.10f)
    }

    override fun onError(error: RuntimeException) {
        EyeControlBus.publish(EyeControlBus.sample.value.copy(faceDetected = false, tracking = true))
    }

    private fun stopTracking() {
        EyeControlBus.setTracking(false)
        helper?.close()
        helper = null
        cursorX = 0.5f
        cursorY = 0.5f
        filteredDirX = 0f
        filteredDirY = 0f
        lastControlAt = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        EyeControlBus.setTracking(false)
        helper?.close()
        helper = null
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Eye Control",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.dimas.eyecontrol.START"
        const val ACTION_STOP = "com.dimas.eyecontrol.STOP"
        private const val CHANNEL_ID = "eye_control_tracking"
        private const val NOTIFICATION_ID = 41
    }
}
