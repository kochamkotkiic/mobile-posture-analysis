package com.postureguard.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Receives camera frames (on the analysis thread), turns them into what the model expects and hands them on.
 *
 * ORDER MATTERS (see AGENTS.md): 1) rotate upright, 2) mirror horizontally (= cv2.flip(frame, 1) in Python).
 * The model was trained on mirrored webcam images.
 */
class FrameAnalyzer(
    private val onFrame: (frame: Bitmap, preprocessMs: Double) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val start = System.nanoTime()
        val frame = try {
            uprightAndMirrored(image.toBitmap(), image.imageInfo.rotationDegrees)
        } finally {
            image.close() // give the buffer back to CameraX as soon as possible
        }
        onFrame(frame, (System.nanoTime() - start) / 1_000_000.0)
    }

    companion object {
        fun uprightAndMirrored(source: Bitmap, rotationDegrees: Int): Bitmap {
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat()) // 1) upright
                postScale(-1f, 1f)                    // 2) mirror left <-> right
            }
            return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }
    }
}
