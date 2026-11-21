package com.postureguard.pose

/**
 * One MediaPipe landmark in normalized image coordinates of the UPRIGHT, MIRRORED frame:
 * x, y in 0..1 of the image width/height (y grows downwards), z = relative depth (smaller = closer).
 * Plain Kotlin (no Android imports) so the posture logic can be unit-tested on the JVM.
 */
data class Landmark(val x: Float, val y: Float, val z: Float, val visibility: Float)

/** Pose detection result for one camera frame. */
data class PoseFrame(
    val timestampMs: Long,
    /** All 33 MediaPipe landmarks, or null when no person was detected. */
    val landmarks: List<Landmark>?,
    val imageWidth: Int,
    val imageHeight: Int,
    /** Time from handing the frame to MediaPipe until the result arrived. */
    val detectionLatencyMs: Double,
) {
    val personVisible: Boolean get() = landmarks != null
    val nose: Landmark? get() = landmarks?.get(NOSE)
    val leftShoulder: Landmark? get() = landmarks?.get(LEFT_SHOULDER)
    val rightShoulder: Landmark? get() = landmarks?.get(RIGHT_SHOULDER)

    companion object {
        // MediaPipe landmark indices used by the model (same as server.py)
        const val NOSE = 0
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
    }
}
