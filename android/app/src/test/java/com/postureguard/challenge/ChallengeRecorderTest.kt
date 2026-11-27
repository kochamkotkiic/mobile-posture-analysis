package com.postureguard.challenge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeRecorderTest {

    private fun features(v: Float) = FloatArray(9) { v }

    /** Frames every 40 ms (25 FPS); the model runs on every 5th frame (5x/s), like the real pipeline. */
    private fun ChallengeRecorder.feed(fromMs: Long, toMs: Long, visible: Boolean = true): ChallengeRecorder.Status {
        var t = fromMs
        var last = current
        var i = 0
        while (t <= toMs) {
            val f = if (visible && i % 5 == 0) features(t.toFloat()) else null
            last = onFrame(ChallengeFrame(t, visible, f))
            t += 40
            i++
        }
        return last
    }

    @Test
    fun idleUntilStarted() {
        val r = ChallengeRecorder()
        assertEquals(ChallengeRecorder.Status.Idle, r.onFrame(ChallengeFrame(0, true, features(1f))))
        assertFalse(r.isActive)
    }

    @Test
    fun countdownThenRecordingThenDone() {
        val r = ChallengeRecorder(countdownMs = 3_000, durationMs = 5_000)
        r.start()
        assertEquals(ChallengeRecorder.Status.Countdown(3), r.onFrame(ChallengeFrame(10_000, true, features(0f))))
        assertEquals(ChallengeRecorder.Status.Countdown(1), r.onFrame(ChallengeFrame(12_500, true, features(0f))))

        val recording = r.onFrame(ChallengeFrame(13_000, true, features(1f)))
        assertTrue(recording is ChallengeRecorder.Status.Recording)
        assertEquals(5, (recording as ChallengeRecorder.Status.Recording).secondsLeft)

        val done = r.feed(13_040, 18_100)
        assertTrue(done is ChallengeRecorder.Status.Done)
        assertFalse(r.isActive)
    }

    @Test
    fun keepsOnlySamplesFromTheRecordingWindow() {
        val r = ChallengeRecorder(countdownMs = 3_000, durationMs = 5_000)
        r.start()
        r.feed(0, 2_960)                     // countdown: ignored
        r.feed(3_000, 8_000)                 // 5 s recording
        val done = r.onFrame(ChallengeFrame(8_040, true, features(9f))) as ChallengeRecorder.Status.Done
        // 3000..8000 every 40 ms = 126 frames, every 5th has features -> 26 samples (~5 per second)
        assertEquals(26, done.samples.size)
        assertTrue(done.samples.all { it[0] in 3_000f..8_000f })
        assertEquals(1f, done.visibleFraction, 1e-6f)
    }

    @Test
    fun framesWithoutAPersonGiveFewSamples() {
        val r = ChallengeRecorder(countdownMs = 0, durationMs = 5_000)
        r.start()
        r.feed(0, 2_480, visible = true)
        r.feed(2_520, 5_000, visible = false)
        val done = r.onFrame(ChallengeFrame(5_040, false, null)) as ChallengeRecorder.Status.Done
        assertTrue(done.samples.size < 15)  // not enough for the server (>= 15 needed)
        assertEquals(0.5f, done.visibleFraction, 0.02f)
    }

    @Test
    fun samplesAreCopied() {
        val r = ChallengeRecorder(countdownMs = 0, durationMs = 1_000)
        r.start()
        val f = features(1f)
        r.onFrame(ChallengeFrame(0, true, f))
        f[0] = 99f // the pipeline may reuse its arrays
        val done = r.feed(40, 1_100) as ChallengeRecorder.Status.Done
        assertEquals(1f, done.samples.first()[0], 0f)
    }

    @Test
    fun startAgainClearsAndCancelStops() {
        val r = ChallengeRecorder(countdownMs = 0, durationMs = 1_000)
        r.start()
        r.feed(0, 500)
        r.start()
        val done = r.feed(10_000, 11_100) as ChallengeRecorder.Status.Done
        assertTrue(done.samples.all { it[0] >= 10_000f })

        r.start()
        r.cancel()
        assertEquals(ChallengeRecorder.Status.Idle, r.onFrame(ChallengeFrame(20_000, true, features(1f))))
    }
}
