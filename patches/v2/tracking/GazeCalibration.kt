package com.dimas.eyecontrol.tracking

import android.content.Context
import kotlin.math.abs

object GazeCalibration {
    private const val PREFS = "eye_control_prefs"
    private const val CALIBRATED = "calibrated"
    private const val CALIBRATION_VERSION_KEY = "calibration_version"
    private const val CALIBRATION_VERSION = 2
    private const val AX = "ax"
    private const val BX = "bx"
    private const val CX = "cx"
    private const val AY = "ay"
    private const val BY = "by"
    private const val CY = "cy"

    @Volatile private var loaded = false
    @Volatile private var calibrated = false
    @Volatile private var ax = 0.85f
    @Volatile private var bx = 0f
    @Volatile private var cx = 0.5f
    @Volatile private var ay = 0f
    @Volatile private var by = 0.85f
    @Volatile private var cy = 0.5f

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val version = p.getInt(CALIBRATION_VERSION_KEY, 0)
            if (version == CALIBRATION_VERSION) {
                calibrated = p.getBoolean(CALIBRATED, false)
                ax = p.getFloat(AX, ax)
                bx = p.getFloat(BX, bx)
                cx = p.getFloat(CX, cx)
                ay = p.getFloat(AY, ay)
                by = p.getFloat(BY, by)
                cy = p.getFloat(CY, cy)
            } else {
                calibrated = false
                ax = 0.85f; bx = 0f; cx = 0.5f
                ay = 0f; by = 0.85f; cy = 0.5f
            }
            loaded = true
        }
    }

    fun map(context: Context, rawX: Float, rawY: Float): Pair<Float, Float> {
        ensureLoaded(context)
        val x = (ax * rawX + bx * rawY + cx).coerceIn(0.03f, 0.97f)
        val y = (ay * rawX + by * rawY + cy).coerceIn(0.03f, 0.97f)
        return x to y
    }

    fun save(context: Context, xCoeff: FloatArray, yCoeff: FloatArray): Boolean {
        if (xCoeff.size != 3 || yCoeff.size != 3) return false
        if (xCoeff.any { !it.isFinite() } || yCoeff.any { !it.isFinite() }) return false
        if (abs(xCoeff[0]) + abs(xCoeff[1]) < 0.05f) return false
        if (abs(yCoeff[0]) + abs(yCoeff[1]) < 0.05f) return false

        ax = xCoeff[0]; bx = xCoeff[1]; cx = xCoeff[2]
        ay = yCoeff[0]; by = yCoeff[1]; cy = yCoeff[2]
        calibrated = true
        loaded = true

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(CALIBRATION_VERSION_KEY, CALIBRATION_VERSION)
            .putBoolean(CALIBRATED, true)
            .putFloat(AX, ax).putFloat(BX, bx).putFloat(CX, cx)
            .putFloat(AY, ay).putFloat(BY, by).putFloat(CY, cy)
            .apply()
        return true
    }

    fun isCalibrated(context: Context): Boolean {
        ensureLoaded(context)
        return calibrated
    }
}
