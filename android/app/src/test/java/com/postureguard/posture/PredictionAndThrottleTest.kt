package com.postureguard.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionAndThrottleTest {

    // --- decision from probabilities (values from onnx_test_cases.json, borderline cases) ---------

    @Test
    fun `higher probability wins`() {
        assertEquals(Prediction(Prediction.BAD, 0.5021277f), Prediction.fromProbabilities(0.4978723f, 0.5021277f))
        assertEquals(Prediction(Prediction.GOOD, 0.509f), Prediction.fromProbabilities(0.509f, 0.491f))
        assertEquals(Prediction(Prediction.BAD, 1f), Prediction.fromProbabilities(0f, 1f))
    }

    @Test
    fun `a tie counts as good like sklearn argmax`() {
        assertEquals(Prediction.GOOD, Prediction.fromProbabilities(0.5f, 0.5f).label)
    }

    // --- 5 Hz inference ------------------------------------------------------------------------------

    @Test
    fun `first frame is classified, then at most every 200 ms`() {
        val throttle = InferenceThrottle()
        assertTrue(throttle.shouldRun(10_000))
        assertFalse(throttle.shouldRun(10_043)) // next camera frame (~23 FPS)
        assertFalse(throttle.shouldRun(10_199))
        assertTrue(throttle.shouldRun(10_200))
        assertFalse(throttle.shouldRun(10_350))
        assertTrue(throttle.shouldRun(10_420)) // measured from the last run, not a fixed grid (as in server.py)
    }

    @Test
    fun `23 FPS camera gives about 5 classifications per second`() {
        val throttle = InferenceThrottle()
        val runs = (0 until 23 * 10).count { frame -> throttle.shouldRun(frame * 1000L / 23) } // 10 s of frames
        assertTrue("got $runs runs in 10 s", runs in 40..50)
    }
}
