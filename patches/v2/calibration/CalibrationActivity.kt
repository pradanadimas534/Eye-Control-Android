package com.dimas.eyecontrol.calibration

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dimas.eyecontrol.tracking.EyeControlBus
import com.dimas.eyecontrol.tracking.GazeCalibration
import kotlinx.coroutines.launch
import kotlin.math.abs

class CalibrationActivity : AppCompatActivity() {
    private lateinit var calibrationView: CalibrationView

    private val targets = listOf(
        0.15f to 0.15f, 0.50f to 0.15f, 0.85f to 0.15f,
        0.15f to 0.50f, 0.50f to 0.50f, 0.85f to 0.50f,
        0.15f to 0.85f, 0.50f to 0.85f, 0.85f to 0.85f
    )

    private data class CalPoint(val rawX: Float, val rawY: Float, val targetX: Float, val targetY: Float)

    private val points = mutableListOf<CalPoint>()
    private var targetIndex = 0
    private var phaseStartedAt = 0L
    private val samples = mutableListOf<Pair<Float, Float>>()
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EyeControlBus.setCalibrationActive(true)
        calibrationView = CalibrationView(this)
        setContentView(calibrationView)
        hideSystemUi()
        moveToTarget(0)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                EyeControlBus.sample.collect { sample ->
                    if (!sample.tracking) {
                        calibrationView.subMessage = "Tracking belum aktif. Kembali dan tekan Mulai Eye Control."
                        calibrationView.invalidate()
                        return@collect
                    }
                    if (!sample.faceDetected) {
                        calibrationView.subMessage = "Wajah belum terdeteksi. Hadapkan wajah ke kamera."
                        calibrationView.invalidate()
                        return@collect
                    }
                    if (sample.blinkScore > 0.48f) return@collect
                    processSample(sample.rawX, sample.rawY)
                }
            }
        }
    }

    private fun moveToTarget(index: Int) {
        targetIndex = index
        val target = targets[index]
        calibrationView.targetXNorm = target.first
        calibrationView.targetYNorm = target.second
        calibrationView.message = "Kalibrasi ${index + 1}/${targets.size}"
        calibrationView.subMessage = "Tatap titik. Kepala tetap diam."
        calibrationView.invalidate()
        samples.clear()
        phaseStartedAt = SystemClock.uptimeMillis()
    }

    private fun processSample(rawX: Float, rawY: Float) {
        if (completed) return
        val now = SystemClock.uptimeMillis()
        val elapsed = now - phaseStartedAt

        if (elapsed < 800L) return
        if (elapsed <= 1900L) {
            samples += rawX to rawY
            calibrationView.subMessage = "Tahan pandangan…"
            calibrationView.invalidate()
            return
        }

        if (samples.size < 8) {
            phaseStartedAt = now - 800L
            samples.clear()
            calibrationView.subMessage = "Ulangi titik ini, pandangan belum stabil"
            calibrationView.invalidate()
            return
        }

        val rawMedianX = median(samples.map { it.first })
        val rawMedianY = median(samples.map { it.second })
        val target = targets[targetIndex]
        points += CalPoint(rawMedianX, rawMedianY, target.first, target.second)

        if (targetIndex == targets.lastIndex) finishCalibration()
        else moveToTarget(targetIndex + 1)
    }

    private fun finishCalibration() {
        if (completed) return
        completed = true

        val x = fitLinear(points.map { it.rawX to it.targetX })
        val y = fitLinear(points.map { it.rawY to it.targetY })

        val horizontalRange = abs(
            median(points.filter { it.targetX > 0.8f }.map { it.rawX }) -
                median(points.filter { it.targetX < 0.2f }.map { it.rawX })
        )
        val verticalRange = abs(
            median(points.filter { it.targetY > 0.8f }.map { it.rawY }) -
                median(points.filter { it.targetY < 0.2f }.map { it.rawY })
        )

        val enoughMovement = horizontalRange >= 0.07f && verticalRange >= 0.07f
        val ok = enoughMovement && x != null && y != null && GazeCalibration.save(
            this,
            floatArrayOf(x[0], 0f, x[1]),
            floatArrayOf(0f, y[0], y[1])
        )

        Toast.makeText(
            this,
            if (ok) "Kalibrasi selesai — arah mata sudah disesuaikan"
            else "Kalibrasi belum cukup. Dekatkan HP dan ulangi tanpa menggerakkan kepala.",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    private fun fitLinear(values: List<Pair<Float, Float>>): FloatArray? {
        if (values.size < 3) return null
        val meanX = values.map { it.first.toDouble() }.average()
        val meanY = values.map { it.second.toDouble() }.average()
        var covariance = 0.0
        var variance = 0.0
        for ((x, y) in values) {
            val dx = x - meanX
            covariance += dx * (y - meanY)
            variance += dx * dx
        }
        if (variance < 1e-6) return null
        val slope = covariance / variance
        val intercept = meanY - slope * meanX
        if (!slope.isFinite() || !intercept.isFinite() || abs(slope) < 0.05) return null
        return floatArrayOf(slope.toFloat(), intercept.toFloat())
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) * 0.5f else sorted[mid]
    }

    override fun onStop() {
        EyeControlBus.setCalibrationActive(false)
        super.onStop()
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }
}
