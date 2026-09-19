package com.dimas.eyecontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.dimas.eyecontrol.MainActivity
import com.dimas.eyecontrol.tracking.EyeControlBus
import com.dimas.eyecontrol.tracking.GazeCalibration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.hypot

class EyeAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var overlay: CursorOverlayView? = null

    private var anchorX = 0f
    private var anchorY = 0f
    private var stableSince = 0L
    private var quietSince = 0L
    private var lastActionAt = 0L
    private var armed = true
    private var lastActionX = 0f
    private var lastActionY = 0f
    private var lastBlinkId = 0L
    private var blinkInitialized = false
    private var lastCursorX = Float.NaN
    private var lastCursorY = Float.NaN
    private var lastCursorAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
        scope.launch { EyeControlBus.sample.collectLatest { sample -> handleSample(sample) } }
    }

    private fun showOverlay() {
        if (overlay != null) return
        val view = CursorOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        windowManager.addView(view, params)
        overlay = view
    }

    private fun handleSample(sample: EyeControlBus.GazeSample) {
        val view = overlay ?: return
        if (EyeControlBus.calibrationActive || !GazeCalibration.isCalibrated(this)) {
            hideCursor(view)
            return
        }
        view.showCursor = sample.tracking
        view.faceDetected = sample.faceDetected
        if (!sample.tracking || !sample.faceDetected) {
            hideCursor(view)
            return
        }

        val w = view.width.toFloat().takeIf { it > 0f } ?: return
        val h = view.height.toFloat().takeIf { it > 0f } ?: return
        val x = sample.xNorm * w
        val y = sample.yNorm * h
        view.cursorX = x
        view.cursorY = y

        val now = SystemClock.uptimeMillis()
        val density = resources.displayMetrics.density
        val stableRadius = 30f * density
        val rearmDistance = 72f * density

        if (lastCursorAt > 0L && lastCursorX.isFinite() && lastCursorY.isFinite()) {
            val dt = (now - lastCursorAt).coerceAtLeast(1L)
            val distancePx = hypot(x - lastCursorX, y - lastCursorY)
            val speedDpPerSec = (distancePx / density) * 1000f / dt
            if (speedDpPerSec > 105f) quietSince = now
        } else {
            quietSince = now
        }
        lastCursorX = x
        lastCursorY = y
        lastCursorAt = now

        if (!armed && hypot(x - lastActionX, y - lastActionY) > rearmDistance) {
            armed = true
            stableSince = now
            quietSince = now
            anchorX = x
            anchorY = y
        }

        if (stableSince == 0L || hypot(x - anchorX, y - anchorY) > stableRadius) {
            anchorX = x
            anchorY = y
            stableSince = now
        }

        val normalDwellMs = 1450L
        val settledSince = maxOf(stableSince, quietSince)
        val stableFor = now - settledSince
        view.progress = (stableFor.toFloat() / normalDwellMs).coerceIn(0f, 1f)

        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val blinkClick = prefs.getBoolean(MainActivity.KEY_BLINK_CLICK, false)
        if (!blinkInitialized) {
            lastBlinkId = sample.longBlinkEventId
            blinkInitialized = true
        }
        if (blinkClick && sample.longBlinkEventId > lastBlinkId) {
            lastBlinkId = sample.longBlinkEventId
            if (now - lastActionAt > 900L) {
                performTap(x, y)
                markAction(x, y, now)
            }
        } else {
            lastBlinkId = maxOf(lastBlinkId, sample.longBlinkEventId)
        }

        if (stableFor >= normalDwellMs && now - lastActionAt > 900L) {
            val edgeDwellMs = 2100L
            val zoneW = w * 0.085f
            val zoneH = h * 0.045f
            when {
                x <= zoneW && y <= zoneH && stableFor >= edgeDwellMs -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    markAction(x, y, now)
                }
                x >= w - zoneW && y <= zoneH && stableFor >= edgeDwellMs -> {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    markAction(x, y, now)
                }
                y >= h * 0.982f && stableFor >= edgeDwellMs -> {
                    performScrollDown(w, h)
                    lastActionAt = now
                    stableSince = now
                    quietSince = now
                }
                y <= h * 0.018f && stableFor >= edgeDwellMs -> {
                    performScrollUp(w, h)
                    lastActionAt = now
                    stableSince = now
                    quietSince = now
                }
                armed -> {
                    performTap(x, y)
                    markAction(x, y, now)
                }
            }
        }
        view.invalidate()
    }

    private fun hideCursor(view: CursorOverlayView) {
        view.showCursor = false
        view.progress = 0f
        stableSince = 0L
        quietSince = 0L
        lastCursorAt = 0L
        lastCursorX = Float.NaN
        lastCursorY = Float.NaN
        view.invalidate()
    }

    private fun markAction(x: Float, y: Float, now: Long) {
        lastActionAt = now
        lastActionX = x
        lastActionY = y
        stableSince = now
        quietSince = now
        armed = false
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build()
        dispatchGesture(gesture, null, null)
    }

    private fun performScrollDown(w: Float, h: Float) {
        swipe(w * 0.5f, h * 0.76f, w * 0.5f, h * 0.34f, 360L)
    }

    private fun performScrollUp(w: Float, h: Float) {
        swipe(w * 0.5f, h * 0.34f, w * 0.5f, h * 0.76f, 360L)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        scope.cancel()
        super.onDestroy()
    }
}
