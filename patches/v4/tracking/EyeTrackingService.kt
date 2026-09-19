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
import com.dimas.eyecontrol.R
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

class EyeTrackingService : LifecycleService(), FaceLandmarkerHelper.Listener {
    private lateinit var executor: ExecutorService
    private var helper: FaceLandmarkerHelper? = null
    private val estimator = GazeEstimator()
    private var lastPublishAt = 0L

    private val recentMapped = ArrayDeque<Pair<Float, Float>>()
    private var filteredX = 0.5f
    private var filteredY = 0.5f
    private var filterInitialized = false

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
        val openIntent = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 2, Intent(this, EyeTrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Eye Control aktif")
            .setContentText("Kamera depan membaca arah pandangan di perangkat.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
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
                    if (now - lastAnalyzedAt < 50L) proxy.close() else {
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
            EyeControlBus.publish(EyeControlBus.sample.value.copy(faceDetected = false, tracking = true, timestampMs = now))
            return
        }
        val mapped = GazeCalibration.map(this, estimate.rawX, estimate.rawY)
        val stabilized = stabilize(mapped.first, mapped.second)
        EyeControlBus.publish(
            EyeControlBus.GazeSample(
                rawX = estimate.rawX,
                rawY = estimate.rawY,
                xNorm = stabilized.first,
                yNorm = stabilized.second,
                faceDetected = true,
                blinkScore = estimate.blinkScore,
                longBlinkEventId = estimate.longBlinkEventId,
                tracking = true,
                timestampMs = now
            )
        )
    }

    private fun stabilize(x: Float, y: Float): Pair<Float, Float> {
        recentMapped.addLast(x to y)
        while (recentMapped.size > 5) recentMapped.removeFirst()
        val medianX = median(recentMapped.map { it.first })
        val medianY = median(recentMapped.map { it.second })
        if (!filterInitialized) {
            filteredX = medianX
            filteredY = medianY
            filterInitialized = true
            return filteredX to filteredY
        }
        var dx = medianX - filteredX
        var dy = medianY - filteredY
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (distance < 0.010f) return filteredX to filteredY
        val alpha = when {
            distance > 0.30f -> 0.24f
            distance > 0.14f -> 0.20f
            distance > 0.06f -> 0.16f
            else -> 0.12f
        }
        dx *= alpha
        dy *= alpha
        val step = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val maxStep = 0.032f
        if (step > maxStep && step > 0f) {
            val scale = maxStep / step
            dx *= scale
            dy *= scale
        }
        filteredX = (filteredX + dx).coerceIn(0.025f, 0.975f)
        filteredY = (filteredY + dy).coerceIn(0.025f, 0.975f)
        return filteredX to filteredY
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0.5f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) * 0.5f else sorted[mid]
    }

    override fun onError(error: RuntimeException) {
        EyeControlBus.publish(EyeControlBus.sample.value.copy(faceDetected = false, tracking = true))
    }

    private fun stopTracking() {
        EyeControlBus.setTracking(false)
        helper?.close()
        helper = null
        recentMapped.clear()
        filterInitialized = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        EyeControlBus.setTracking(false)
        helper?.close()
        helper = null
        recentMapped.clear()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Eye Control", NotificationManager.IMPORTANCE_LOW)
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
