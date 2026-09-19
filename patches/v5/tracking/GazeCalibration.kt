package com.dimas.eyecontrol.tracking

import android.content.Context
import kotlin.math.abs
import kotlin.math.sign

/**
 * Calibration v5 converts raw gaze into a direction vector instead of an absolute
 * screen coordinate. This makes ordinary selfie-camera tracking much more usable:
 * the eyes behave like a joystick and the pointer can always reach the full display.
 */
object GazeCalibration {
    private const val PREFS = "eye_control_prefs"
    private const val CALIBRATED = "calibrated"
    private const val CALIBRATION_VERSION_KEY = "calibration_version"
    private const val CALIBRATION_VERSION = 5

    private const val LEFT_X = "v5_left_x"
    private const val CENTER_X = "v5_center_x"
    private const val RIGHT_X = "v5_right_x"
    private const val TOP_Y = "v5_top_y"
    private const val CENTER_Y = "v5_center_y"
    private const val BOTTOM_Y = "v5_bottom_y"

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

    /** Returns X/Y steering values. Zero means neutral; +/-1 roughly equals a
     * calibrated cardinal-direction gaze. Values can exceed 1 slightly. */
    fun direction(context: Context, rawX: Float, rawY: Float): Pair<Float, Float> {
        ensureLoaded(context)
        if (!calibrated) return 0f to 0f
        return axisDirection(rawX, leftX, centerX, rightX) to
            axisDirection(rawY, topY, centerY, bottomY)
    }

    // Kept for compatibility/debugging. The controller itself uses direction().
    fun map(context: Context, rawX: Float, rawY: Float): Pair<Float, Float> {
        val d = direction(context, rawX, rawY)
        return (0.5f + d.first * 0.45f).coerceIn(0.025f, 0.975f) to
            (0.5f + d.second * 0.45f).coerceIn(0.025f, 0.975f)
    }

    private fun axisDirection(value: Float, low: Float, center: Float, high: Float): Float {
        val orientation = sign(high - low).takeIf { it != 0f } ?: 1f
        val delta = (value - center) * orientation
        val span = if (delta < 0f) abs(center - low) else abs(high - center)
        val rawRatio = delta / span.coerceAtLeast(0.035f)

        // A fairly large neutral zone is intentional: when the user looks near the
        // center, the pointer should stop instead of slowly drifting.
        val dead = 0.16f
        val magnitude = abs(rawRatio)
        if (magnitude <= dead) return 0f
        val normalized = ((magnitude - dead) / (1f - dead)).coerceIn(0f, 1.45f)
        return sign(rawRatio) * normalized
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
        if (abs(a) < 0.022f || abs(b) < 0.022f) return false
        if (a * b >= 0f) return false
        return abs(high - low) >= 0.060f
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
