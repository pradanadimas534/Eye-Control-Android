package com.dimas.eyecontrol.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object EyeControlBus {
    data class GazeSample(
        val rawX: Float = 0f,
        val rawY: Float = 0f,
        val xNorm: Float = 0.5f,
        val yNorm: Float = 0.5f,
        val faceDetected: Boolean = false,
        val blinkScore: Float = 0f,
        val longBlinkEventId: Long = 0L,
        val tracking: Boolean = false,
        val timestampMs: Long = 0L
    )

    private val _sample = MutableStateFlow(GazeSample())
    val sample: StateFlow<GazeSample> = _sample

    @Volatile
    var calibrationActive: Boolean = false
        private set

    fun publish(sample: GazeSample) {
        _sample.value = sample
    }

    fun setTracking(active: Boolean) {
        _sample.value = _sample.value.copy(tracking = active, faceDetected = false)
    }

    fun setCalibrationActive(active: Boolean) {
        calibrationActive = active
    }
}
