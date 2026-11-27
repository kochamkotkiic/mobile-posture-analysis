package com.postureguard.challenge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeRecorderTest {

    private fun features(v: Float) = FloatArray(9) { v }

    /** Frames every [stepMs] from [fromMs] to [toMs] (inclusive), with features whenever someone is visible. */
    private fun ChallengeRecorder.feed(
        fromMs: Long,
        toMs: Long,
        stepMs: Long = 40,
        visible: Boolean = true,
    ): ChallengeRecorder.Status {
        var t = fromMs
        var last = current
        while (t <= toMs) {
            last = onFrame(ChallengeFrame(t, visible, if (visible) features(t.toFloat()) else null))
            t += stepMs
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
        r.feed(3_000, 8_000)                 // 5 s recording at 25 FPS
        val done = r.onFrame(ChallengeFrame(8_040, true, features(9f))) as ChallengeRecorder.Status.Done
        assertEquals(126, done.samples.size) // every visible frame: 3000..8000 every 40 ms
        assertTrue(done.samples.all { it[0] in 3_000f..8_000f })
        assertEquals(1f, done.visibleFraction, 1e-6f)
    }

    @Test
    fun slowDeviceRecordsLongerUntilEnoughSamples() {
        // The emulator: ~2.5 pose frames per second -> only ~12 samples in 5 s, the server needs 15
        val r = ChallengeRecorder(countdownMs = 0, durationMs = 5_000, minSamples = 15)
        r.start()
        assertTrue(r.feed(0, 5_200, stepMs = 400) is ChallengeRecorder.Status.Recording) // 14 samples: keep going
        val done = r.feed(5_600, 6_400, stepMs = 400) as ChallengeRecorder.Status.Done
        assertEquals(15, done.samples.size)
    }

    @Test
    fun givesUpWhenNobodyIsVisible() {
        val r = ChallengeRecorder(countdownMs = 0, durationMs = 5_000, minSamples = 15, maxExtraMs = 5_000)
        r.start()
        r.feed(0, 1_000, visible = true)               // 26 frames
        r.feed(1_040, 10_000, visible = false)         // then out of view
        val done = r.onFrame(ChallengeFrame(10_040, false, null)) as ChallengeRecorder.Status.Done
        assertEquals(26, done.samples.size)            // enough samples (done after 5 s), but...
        assertEquals(26f / 126f, done.visibleFraction, 1e-4f) // ...only 1 of the 5 s in view
    }

    @Test
    fun tooFewSamplesAfterTheExtraTime() {
        val r = ChallengeRecorder(countdownMs = 0, durationMs = 5_000, minSamples = 15, maxExtraMs = 5_000)
        r.start()
        r.feed(0, 10_000, stepMs = 1_000) // 1 FPS: 11 samples in 10 s
        val done = r.onFrame(ChallengeFrame(10_040, true, null)) as ChallengeRecorder.Status.Done
        assertEquals(11, done.samples.size)
    }

    @Test
    fun capsAtTheServerLimit() {
        val r = ChallengeRecorder(countdownMs = 0, durationMs = 5_000, maxSamples = 200)
        r.start()
        val done = r.feed(0, 5_100, stepMs = 10) as ChallengeRecorder.Status.Done // 100 FPS
        assertEquals(200, done.samples.size)
    }

    @Test
    fun samplesAreCopied() {
        val r = ChallengeRecorder(countdownMs = 0, durationMs = 1_000, minSamples = 1)
        r.start()
        val f = features(1f)
        r.onFrame(ChallengeFrame(0, true, f))
        f[0] = 99f // the pipeline may reuse its arrays
        val done = r.feed(40, 1_100) as ChallengeRecorder.Status.Done
        assertEquals(1f, done.samples.first()[0], 0f)
    }

    @Test
    fun startAgainClearsAndCancelStops() {
        val r = ChallengeRecorder(countdownMs = 0, durationMs = 1_000, minSamples = 1)
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
