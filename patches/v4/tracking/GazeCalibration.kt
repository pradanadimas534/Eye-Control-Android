package com.dimas.eyecontrol.tracking

import android.content.Context
import kotlin.math.abs
import kotlin.math.sign

/**
 * Calibration v4 keeps the 3-anchor model from v3 but deliberately reduces gain.
 * Phone selfie cameras often report a very small raw eye range; mapping that tiny
 * range directly to the whole display makes the cursor overshoot badly.
 */
object GazeCalibration {
    private const val PREFS = "eye_control_prefs"
    private const val CALIBRATED = "calibrated"
    private const val CALIBRATION_VERSION_KEY = "calibration_version"
    private const val CALIBRATION_VERSION = 4

    private const val LEFT_X = "v4_left_x"
    private const val CENTER_X = "v4_center_x"
    private const val RIGHT_X = "v4_right_x"
    private const val TOP_Y = "v4_top_y"
    private const val CENTER_Y = "v4_center_y"
    private const val BOTTOM_Y = "v4_bottom_y"

    @Volatile private var loaded = false
    @Volatile private var calibrated = false

    @Volatile private var leftX = -0.35f
    @Volatile private var centerX = 0f
    @Volatile private var rightX = 0.35f
    @Volatile private var topY = -0.30f
    @Volatile private var centerY = 0f
    @Volatile private var bottomY = 0.30f

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val version = p.getInt(CALIBRATION_VERSION_KEY, 0)
            if (version == CALIBRATION_VERSION) {
                calibrated = p.getBoolean(CALIBRATED, false)
                leftX = p.getFloat(LEFT_X, leftX)
                centerX = p.getFloat(CENTER_X, centerX)
                rightX = p.getFloat(RIGHT_X, rightX)
                topY = p.getFloat(TOP_Y, topY)
                centerY = p.getFloat(CENTER_Y, centerY)
                bottomY = p.getFloat(BOTTOM_Y, bottomY)
            } else {
                calibrated = false
            }
            loaded = true
        }
    }

    fun map(context: Context, rawX: Float, rawY: Float): Pair<Float, Float> {
        ensureLoaded(context)
        if (!calibrated) return 0.5f to 0.5f
        return axisMap(rawX, leftX, centerX, rightX) to
            axisMap(rawY, topY, centerY, bottomY)
    }

    private fun axisMap(value: Float, low: Float, center: Float, high: Float): Float {
        val direction = sign(high - low).takeIf { it != 0f } ?: 1f
        val d = (value - center) * direction
        val lowSpan = abs(center - low).coerceAtLeast(0.060f)
        val highSpan = abs(high - center).coerceAtLeast(0.060f)
        var ratio = if (d < 0f) d / lowSpan else d / highSpan

        val dead = 0.09f
        ratio = when {
            abs(ratio) <= dead -> 0f
            ratio > 0f -> (ratio - dead) / (1f - dead)
            else -> (ratio + dead) / (1f - dead)
        }

        return (0.5f + 0.33f * ratio.coerceIn(-1.45f, 1.45f)).coerceIn(0.025f, 0.975f)
    }

    fun saveAnchors(
        context: Context,
        left: Float,
        centerHorizontal: Float,
        right: Float,
        top: Float,
        centerVertical: Float,
        bottom: Float
    ): Boolean {
        val all = floatArrayOf(left, centerHorizontal, right, top, centerVertical, bottom)
        if (all.any { !it.isFinite() }) return false
        if (!axisLooksValid(left, centerHorizontal, right)) return false
        if (!axisLooksValid(top, centerVertical, bottom)) return false

        leftX = left
        centerX = centerHorizontal
        rightX = right
        topY = top
        centerY = centerVertical
        bottomY = bottom
        calibrated = true
        loaded = true

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(CALIBRATION_VERSION_KEY, CALIBRATION_VERSION)
            .putBoolean(CALIBRATED, true)
            .putFloat(LEFT_X, leftX)
            .putFloat(CENTER_X, centerX)
            .putFloat(RIGHT_X, rightX)
            .putFloat(TOP_Y, topY)
            .putFloat(CENTER_Y, centerY)
            .putFloat(BOTTOM_Y, bottomY)
            .apply()
        return true
    }

    private fun axisLooksValid(low: Float, center: Float, high: Float): Boolean {
        val a = low - center
        val b = high - center
        if (abs(a) < 0.025f || abs(b) < 0.025f) return false
        if (a * b >= 0f) return false
        return abs(high - low) >= 0.07f
    }

    fun isCalibrated(context: Context): Boolean {
        ensureLoaded(context)
        return calibrated
    }

    fun invalidate(context: Context) {
        calibrated = false
        loaded = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(CALIBRATION_VERSION_KEY, CALIBRATION_VERSION)
            .putBoolean(CALIBRATED, false)
            .apply()
    }
}
