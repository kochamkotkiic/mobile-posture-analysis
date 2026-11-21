package com.postureguard.posture

import com.postureguard.pose.Landmark
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureAndCalibrationTest {

    /** 33 landmarks where landmark i has x = i, y = i + 0.1, z = i + 0.2, visibility = 0.5. */
    private fun landmarks() = List(33) { i -> Landmark(i.toFloat(), i + 0.1f, i + 0.2f, 0.5f) }

    @Test
    fun `features are nose, left shoulder, right shoulder - x y z each`() {
        val f = FeatureExtractor.extract(landmarks())
        assertArrayEquals(
            floatArrayOf(0f, 0.1f, 0.2f, 11f, 11.1f, 11.2f, 12f, 12.1f, 12.2f),
            f, 1e-6f,
        )
    }

    @Test
    fun `feature names match the Python profile keys`() {
        assertEquals(
            listOf(
                "nose_x", "nose_y", "nose_z",
                "left_shoulder_x", "left_shoulder_y", "left_shoulder_z",
                "right_shoulder_x", "right_shoulder_y", "right_shoulder_z",
            ),
            FeatureExtractor.FEATURE_NAMES,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `too few landmarks are rejected`() {
        FeatureExtractor.extract(landmarks().take(12))
    }

    @Test
    fun `shoulder visibility is the mean of both shoulders`() {
        val lms = landmarks().toMutableList()
        lms[11] = lms[11].copy(visibility = 0.9f)
        lms[12] = lms[12].copy(visibility = 0.5f)
        assertEquals(0.7f, FeatureExtractor.shoulderVisibility(lms), 1e-6f)
    }

    @Test
    fun `normalize subtracts the calibration`() {
        val calibration = Calibration(floatArrayOf(0.5f, 0.3f, -1f, 0.8f, 0.7f, -0.2f, 0.3f, 0.7f, -0.3f))
        val features = floatArrayOf(0.6f, 0.4f, -1.2f, 0.8f, 0.75f, -0.2f, 0.25f, 0.7f, -0.4f)
        assertArrayEquals(
            floatArrayOf(0.1f, 0.1f, -0.2f, 0f, 0.05f, 0f, -0.05f, 0f, -0.1f),
            calibration.normalize(features), 1e-6f,
        )
    }

    @Test
    fun `calibration map round trip uses the Python keys`() {
        val calibration = Calibration(FloatArray(9) { it * 0.1f })
        val map = calibration.toMap()
        assertEquals(FeatureExtractor.FEATURE_NAMES, map.keys.toList())
        assertEquals(calibration, Calibration.fromMap(map))
    }

    @Test
    fun `missing calibration keys count as 0 like calibration get(key, 0) in Python`() {
        val calibration = Calibration.fromMap(mapOf("nose_x" to 0.5))
        assertEquals(0.5f, calibration.values[0], 0f)
        assertTrue(calibration.values.drop(1).all { it == 0f })
    }

    @Test
    fun `calibration keeps its own copy of the values`() {
        val values = FloatArray(9) { 1f }
        val calibration = Calibration(values)
        values[0] = 99f
        assertEquals(1f, calibration.values[0], 0f)
    }
}
