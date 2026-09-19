package com.dimas.eyecontrol.tracking

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.abs
import kotlin.math.hypot

class GazeEstimator {
    private var smoothX = 0f
    private var smoothY = 0f
    private var initialized = false
    private var blinkStartedAt = 0L
    private var blinkWasClosed = false
    private var longBlinkEventId = 0L

    data class Estimate(
        val rawX: Float,
        val rawY: Float,
        val blinkScore: Float,
        val longBlinkEventId: Long
    )

    fun estimate(result: FaceLandmarkerResult, nowMs: Long): Estimate? {
        val blendshapes = result.faceBlendshapes()
        if (!blendshapes.isPresent) return null
        val shapes = blendshapes.get().firstOrNull() ?: return null
        val scores = shapes.associate { it.categoryName() to it.score() }
        fun score(name: String): Float = scores[name] ?: 0f

        val blink = (score("eyeBlinkLeft") + score("eyeBlinkRight")) * 0.5f

        // MediaPipe blendshapes are expressed from the person's point of view.
        val lookLeft = (score("eyeLookOutLeft") + score("eyeLookInRight")) * 0.5f
        val lookRight = (score("eyeLookInLeft") + score("eyeLookOutRight")) * 0.5f
        val lookUp = (score("eyeLookUpLeft") + score("eyeLookUpRight")) * 0.5f
        val lookDown = (score("eyeLookDownLeft") + score("eyeLookDownRight")) * 0.5f
        val blendX = ((lookRight - lookLeft) * 1.65f).coerceIn(-1.15f, 1.15f)
        val blendY = ((lookDown - lookUp) * 1.55f).coerceIn(-1.15f, 1.15f)

        val face = result.faceLandmarks().firstOrNull()
        var rawX = blendX
        var rawY = blendY

        if (face != null) {
            irisSignal(face)?.let { (irisX, irisY) ->
                // CameraX front-camera analysis is not mirrored. irisSignal() converts
                // horizontal movement into the user's/screen point of view, then we keep
                // blendshapes only as a small stabilizing/fallback signal.
                rawX = irisX * 0.90f + blendX * 0.10f
                rawY = irisY * 0.90f + blendY * 0.10f
            }
        }

        rawX = rawX.coerceIn(-1.5f, 1.5f)
        rawY = rawY.coerceIn(-1.5f, 1.5f)

        // Freeze while blinking. Use adaptive smoothing: fast for intentional large
        // movements, slow for micro jitter while staring at one target.
        if (blink < 0.58f || !initialized) {
            if (!initialized) {
                smoothX = rawX
                smoothY = rawY
                initialized = true
            } else {
                val movement = hypot((rawX - smoothX).toDouble(), (rawY - smoothY).toDouble()).toFloat()
                val alpha = when {
                    movement > 0.45f -> 0.52f
                    movement > 0.20f -> 0.38f
                    else -> 0.20f
                }
                smoothX += alpha * (rawX - smoothX)
                smoothY += alpha * (rawY - smoothY)
            }
        }

        val closed = blink > 0.68f
        if (closed && !blinkWasClosed) {
            blinkStartedAt = nowMs
            blinkWasClosed = true
        } else if (!closed && blinkWasClosed) {
            val duration = nowMs - blinkStartedAt
            if (duration in 550..1600) longBlinkEventId++
            blinkWasClosed = false
        }

        return Estimate(smoothX, smoothY, blink, longBlinkEventId)
    }

    /**
     * Refined Face Landmarker mesh exposes iris centers at 468 and 473.
     * CameraX gives the front-camera analysis image in sensor/image coordinates,
     * which is horizontally opposite to the user's screen point of view. Therefore
     * the horizontal iris signal is inverted here before calibration.
     */
    private fun irisSignal(face: List<NormalizedLandmark>): Pair<Float, Float>? {
        if (face.size < 478) return null

        val right = normalizedEye(
            iris = face[468],
            cornerA = face[33],
            cornerB = face[133],
            top = face[159],
            bottom = face[145]
        ) ?: return null

        val left = normalizedEye(
            iris = face[473],
            cornerA = face[362],
            cornerB = face[263],
            top = face[386],
            bottom = face[374]
        ) ?: return null

        val imageX = (((right.first + left.first) * 0.5f) - 0.5f) * 3.0f
        val userX = -imageX
        val y = (((right.second + left.second) * 0.5f) - 0.5f) * 3.0f
        return userX.coerceIn(-1.5f, 1.5f) to y.coerceIn(-1.5f, 1.5f)
    }

    private fun normalizedEye(
        iris: NormalizedLandmark,
        cornerA: NormalizedLandmark,
        cornerB: NormalizedLandmark,
        top: NormalizedLandmark,
        bottom: NormalizedLandmark
    ): Pair<Float, Float>? {
        val minX = minOf(cornerA.x(), cornerB.x())
        val maxX = maxOf(cornerA.x(), cornerB.x())
        val minY = minOf(top.y(), bottom.y())
        val maxY = maxOf(top.y(), bottom.y())
        val width = maxX - minX
        val height = maxY - minY
        if (abs(width) < 1e-5f || abs(height) < 1e-5f) return null
        return ((iris.x() - minX) / width).coerceIn(0f, 1f) to
            ((iris.y() - minY) / height).coerceIn(0f, 1f)
    }
}
