package com.postureguard.posture

import com.postureguard.pose.Landmark
import com.postureguard.pose.PoseFrame

/**
 * 33 MediaPipe landmarks -> the 9 model features (same as extract_features() in server.py).
 * The ORDER is the model's input order and must never change.
 */
object FeatureExtractor {
    const val N_FEATURES = 9

    /** Also the keys of the calibration JSON (same as data/profiles/<name>.json -> "calibration" in Python). */
    val FEATURE_NAMES: List<String> = listOf(
        "nose_x", "nose_y", "nose_z",
        "left_shoulder_x", "left_shoulder_y", "left_shoulder_z",
        "right_shoulder_x", "right_shoulder_y", "right_shoulder_z",
    )

    private val LANDMARK_INDICES = intArrayOf(PoseFrame.NOSE, PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_SHOULDER)

    fun extract(landmarks: List<Landmark>): FloatArray {
        require(landmarks.size > PoseFrame.RIGHT_SHOULDER) { "expected 33 landmarks, got ${landmarks.size}" }
        val features = FloatArray(N_FEATURES)
        var i = 0
        for (index in LANDMARK_INDICES) {
            val lm = landmarks[index]
            features[i++] = lm.x
            features[i++] = lm.y
            features[i++] = lm.z
        }
        return features
    }

    /** Average visibility of both shoulders (calibration only uses frames where this is > 0.6). */
    fun shoulderVisibility(landmarks: List<Landmark>): Float =
        (landmarks[PoseFrame.LEFT_SHOULDER].visibility + landmarks[PoseFrame.RIGHT_SHOULDER].visibility) / 2f
}
