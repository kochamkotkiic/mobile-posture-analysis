package com.postureguard.pose

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Wrapper around MediaPipe PoseLandmarker in LIVE_STREAM mode (one person, full model).
 *
 * Feed it UPRIGHT + MIRRORED frames with [detectAsync]; results arrive on a MediaPipe thread via [onResult].
 * Settings match the Python app: pose_landmarker_full.task, confidence 0.6 (monitoring) / 0.7 (calibration).
 */
class PoseDetector(
    context: Context,
    minConfidence: Float = MONITORING_CONFIDENCE,
    private val onResult: (PoseFrame) -> Unit,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {

    private val landmarker: PoseLandmarker
    private val sentAtNanos = ConcurrentHashMap<Long, Long>()
    private var lastTimestampMs = -1L

    init {
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(minConfidence)
            .setMinPosePresenceConfidence(minConfidence)
            .setMinTrackingConfidence(minConfidence)
            .setResultListener { result: PoseLandmarkerResult, image: MPImage -> handleResult(result, image) }
            .setErrorListener { error: RuntimeException -> onError(error) }
            .build()
        landmarker = PoseLandmarker.createFromOptions(context, options)
    }

    /** Call from the camera analysis thread. [frame] must already be rotated upright and mirrored. */
    fun detectAsync(frame: Bitmap) {
        var timestampMs = SystemClock.uptimeMillis()
        if (timestampMs <= lastTimestampMs) timestampMs = lastTimestampMs + 1 // MediaPipe needs increasing timestamps
        lastTimestampMs = timestampMs
        sentAtNanos[timestampMs] = System.nanoTime()
        sentAtNanos.keys.removeIf { it < timestampMs - 5_000 } // frames MediaPipe skipped because it was busy
        landmarker.detectAsync(BitmapImageBuilder(frame).build(), timestampMs)
    }

    private fun handleResult(result: PoseLandmarkerResult, image: MPImage) {
        val timestampMs = result.timestampMs()
        val sentAt = sentAtNanos.remove(timestampMs)
        val latencyMs = if (sentAt != null) (System.nanoTime() - sentAt) / 1_000_000.0 else Double.NaN
        val landmarks = result.landmarks().firstOrNull()?.map { lm ->
            Landmark(lm.x(), lm.y(), lm.z(), lm.visibility().orElse(0f))
        }
        onResult(PoseFrame(timestampMs, landmarks, image.width, image.height, latencyMs))
    }

    override fun close() = landmarker.close()

    companion object {
        const val MODEL_ASSET = "pose_landmarker_full.task"
        const val MONITORING_CONFIDENCE = 0.6f
        const val CALIBRATION_CONFIDENCE = 0.7f
    }
}
