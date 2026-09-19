package com.dimas.eyecontrol.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

class FaceLandmarkerHelper(
    context: Context,
    private val listener: Listener
) : AutoCloseable {

    private val faceLandmarker: FaceLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .setResultListener { result, input -> listener.onResult(result, input) }
            .setErrorListener { error -> listener.onError(error) }
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    }

    fun detect(imageProxy: ImageProxy) {
        val rotation = imageProxy.imageInfo.rotationDegrees
        val width = imageProxy.width
        val height = imageProxy.height
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + (rowPadding / pixelStride).coerceAtLeast(0)

        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        try {
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
        } finally {
            imageProxy.close()
        }

        // CameraX RGBA buffers may include row padding. Crop it before rotation or the
        // landmark image becomes horizontally sheared on devices with padded buffers.
        val cropped = if (padded.width == width) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, width, height)
        }

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
        if (cropped !== padded) padded.recycle()
        if (rotated !== cropped) cropped.recycle()

        val mpImage = BitmapImageBuilder(rotated).build()
        faceLandmarker.detectAsync(mpImage, SystemClock.uptimeMillis())
    }

    override fun close() {
        faceLandmarker.close()
    }

    interface Listener {
        fun onResult(result: FaceLandmarkerResult, input: MPImage)
        fun onError(error: RuntimeException)
    }
}
