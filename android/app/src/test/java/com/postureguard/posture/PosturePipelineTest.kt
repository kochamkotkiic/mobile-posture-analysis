package com.postureguard.posture

import com.postureguard.pose.Landmark
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole chain with a FAKE classifier (the real ONNX model only runs on the phone, see LocalOnnxClassifierTest):
 * frames at ~23 FPS like the Samsung -> features - calibration -> classifier at most 5x/s -> state machine.
 */
class PosturePipelineTest {

    /** Fake judge: "bad" when the nose is more than 0.05 below the calibrated position (normalized nose_y > 0.05). */
    private class FakeClassifier : PostureClassifier {
        val inputs = mutableListOf<FloatArray>()
        override suspend fun classify(normalizedFeatures: FloatArray): Prediction {
            inputs += normalizedFeatures.copyOf()
            return if (normalizedFeatures[1] > 0.05f) Prediction(Prediction.BAD, 0.9f) else Prediction(Prediction.GOOD, 0.8f)
        }
    }

    /** 33 landmarks; nose at (0.5, noseY, -1), shoulders at y 0.8. */
    private fun person(noseY: Float) = List(33) { i ->
        when (i) {
            0 -> Landmark(0.5f, noseY, -1f, 0.99f)
            11 -> Landmark(0.7f, 0.8f, -0.4f, 0.99f)
            12 -> Landmark(0.3f, 0.8f, -0.4f, 0.99f)
            else -> Landmark(0f, 0f, 0f, 0.5f)
        }
    }

    private val straight = Calibration(floatArrayOf(0.5f, 0.4f, -1f, 0.7f, 0.8f, -0.4f, 0.3f, 0.8f, -0.4f))
    private val frameMs = 43L // ~23 FPS

    private class Run(val pipeline: PosturePipeline, var nowMs: Long = 1_000_000) {
        var last: PosturePipeline.Result? = null
        val events = mutableListOf<PostureEvent>()
        fun frames(count: Int, frameMs: Long, landmarks: () -> List<Landmark>?) = runBlocking {
            repeat(count) {
                nowMs += frameMs
                last = pipeline.onFrame(nowMs, landmarks())
                last?.let { events += it.update.events }
            }
        }
    }

    @Test
    fun `without calibration nothing is judged`() {
        val fake = FakeClassifier()
        val run = Run(PosturePipeline(fake))
        run.frames(30, frameMs) { person(0.4f) }
        assertEquals(null, run.last)
        assertTrue(fake.inputs.isEmpty())
    }

    @Test
    fun `classifier gets features minus calibration`() {
        val fake = FakeClassifier()
        val pipeline = PosturePipeline(fake).apply { setCalibration(straight) }
        Run(pipeline).frames(1, frameMs) { person(0.47f) }
        assertArrayEquals(floatArrayOf(0f, 0.07f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), fake.inputs.single(), 1e-6f)
    }

    @Test
    fun `about 5 classifications per second at 23 FPS`() {
        val fake = FakeClassifier()
        val run = Run(PosturePipeline(fake).apply { setCalibration(straight) })
        run.frames(230, frameMs) { person(0.4f) } // 10 s
        assertTrue("got ${fake.inputs.size}", fake.inputs.size in 45..50)
    }

    @Test
    fun `no person means no classification and person not visible`() {
        val fake = FakeClassifier()
        val run = Run(PosturePipeline(fake).apply { setCalibration(straight) })
        run.frames(30, frameMs) { null }
        assertTrue(fake.inputs.isEmpty())
        assertFalse(run.last!!.update.state.personVisible)
    }

    @Test
    fun `sitting straight stays good`() {
        val run = Run(PosturePipeline(FakeClassifier()).apply { setCalibration(straight) })
        run.frames(23 * 30, frameMs) { person(0.41f) } // 30 s, nose 0.01 below calibration
        assertEquals(Prediction.GOOD, run.last!!.update.state.smoothedLabel)
        assertFalse(run.last!!.update.state.badPostureShown)
    }

    @Test
    fun `slouching shows the sign after about 5,8 s, not before`() {
        val run = Run(PosturePipeline(FakeClassifier()).apply { setCalibration(straight) })
        run.frames(23 * 5, frameMs) { person(0.5f) } // 4.9 s of slouching
        assertFalse(run.last!!.update.state.badPostureShown)
        run.frames(23, frameMs) { person(0.5f) }     // 5.9 s
        assertTrue(run.last!!.update.state.badPostureShown)
    }

    @Test
    fun `test mode config gives the alert after 30 s`() {
        val pipeline = PosturePipeline(FakeClassifier()).apply {
            setCalibration(straight)
            setConfig(PostureConfig(longAlertMs = 30_000))
        }
        val run = Run(pipeline)
        run.frames(23 * 40, frameMs) { person(0.5f) } // 40 s slouching: smoothing 2.8 s + 30 s
        assertEquals(1, run.events.count { it == PostureEvent.LongBadPostureAlert })
    }

    @Test
    fun `new calibration starts from scratch`() {
        val pipeline = PosturePipeline(FakeClassifier()).apply { setCalibration(straight) }
        val run = Run(pipeline)
        run.frames(23 * 6, frameMs) { person(0.5f) }
        assertTrue(run.last!!.update.state.badPostureShown)

        pipeline.setCalibration(Calibration(straight.values.copyOf().also { it[1] = 0.5f })) // recalibrated lower
        run.frames(1, frameMs) { person(0.5f) }
        assertEquals(Prediction.GOOD, run.last!!.update.state.smoothedLabel)
        assertFalse(run.last!!.update.state.badPostureShown)
    }
}
