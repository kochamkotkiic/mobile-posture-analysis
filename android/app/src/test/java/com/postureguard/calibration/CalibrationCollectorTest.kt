package com.postureguard.calibration

import com.postureguard.calibration.CalibrationCollector.Status
import com.postureguard.pose.Landmark
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules from server.py / calibration.py: 5 s, shoulder visibility > 0.6, >= 10 samples, mean of each feature.
 * Most tests use countdownMs = 0 to test these rules directly; the countdown has its own tests at the end.
 */
class CalibrationCollectorTest {

    /** 33 landmarks; nose = (v, v+1, v+2), shoulders the same; both shoulders have visibility [vis]. */
    private fun person(v: Float, vis: Float = 0.9f) = List(33) { i ->
        Landmark(v, v + 1, v + 2, if (i == 11 || i == 12) vis else 0.5f)
    }

    private val start = 100_000L

    /** Feeds [count] frames 100 ms apart starting at [fromMs]; returns the last status. */
    private fun CalibrationCollector.frames(count: Int, fromMs: Long, landmarks: (Int) -> List<Landmark>?): Status {
        var status: Status = Status.Idle
        repeat(count) { i -> status = onFrame(fromMs + i * 100L, landmarks(i)) }
        return status
    }

    @Test
    fun `not started means idle`() {
        assertEquals(Status.Idle, CalibrationCollector(countdownMs = 0).onFrame(start, person(0.5f)))
    }

    @Test
    fun `calibration is the mean of the collected frames`() {
        val collector = CalibrationCollector(countdownMs = 0)
        collector.start(start)
        collector.frames(50, start) { i -> person(if (i % 2 == 0) 0.4f else 0.6f) } // 0 ms .. 4900 ms
        val status = collector.onFrame(start + 5_000, person(0.5f))                // 51st sample, then done

        assertTrue("got $status", status is Status.Done)
        status as Status.Done
        assertEquals(51, status.samples)
        assertArrayEquals(
            floatArrayOf(0.5f, 1.5f, 2.5f, 0.5f, 1.5f, 2.5f, 0.5f, 1.5f, 2.5f),
            status.calibration.values, 1e-5f,
        )
    }

    @Test
    fun `progress grows over the 5 s`() {
        val collector = CalibrationCollector(countdownMs = 0)
        collector.start(start)
        val status = collector.onFrame(start + 2_500, person(0.5f))
        assertEquals(Status.Collecting(0.5f, 1), status)
        assertTrue(collector.isCollecting)
    }

    @Test
    fun `frames without a person or with shoulder visibility 0,6 or lower are not used`() {
        val collector = CalibrationCollector(countdownMs = 0)
        collector.start(start)
        collector.frames(10, start) { null }                          // nobody in the picture
        collector.frames(10, start + 1_000) { person(9f, vis = 0.6f) } // exactly 0.6 is NOT > 0.6
        collector.frames(12, start + 2_000) { person(0.5f, vis = 0.61f) }
        val status = collector.onFrame(start + 5_000, null)

        status as Status.Done
        assertEquals(12, status.samples)
        assertEquals(0.5f, status.calibration.values[0], 1e-6f) // the 9f frames were ignored
    }

    @Test
    fun `fewer than 10 good frames fails and stops collecting`() {
        val collector = CalibrationCollector(countdownMs = 0)
        collector.start(start)
        collector.frames(9, start) { person(0.5f) }
        val status = collector.onFrame(start + 5_000, null)

        assertEquals(Status.TooFewSamples(9, 10), status)
        assertFalse(collector.isCollecting)
        assertEquals(Status.Idle, collector.onFrame(start + 5_100, person(0.5f)))
    }

    @Test
    fun `exactly 10 good frames is enough`() {
        val collector = CalibrationCollector(countdownMs = 0)
        collector.start(start)
        collector.frames(10, start) { person(0.5f) }
        assertTrue(collector.onFrame(start + 5_000, null) is Status.Done)
    }

    @Test
    fun `the frame at exactly 5 s is still used, then it finishes`() {
        val collector = CalibrationCollector(countdownMs = 0)
        collector.start(start)
        collector.frames(9, start) { person(0.5f) }
        val status = collector.onFrame(start + 5_000, person(0.5f)) // 10th sample arrives on the last frame
        assertEquals(10, (status as Status.Done).samples)
    }

    @Test
    fun `starting again throws away the old samples`() {
        val collector = CalibrationCollector(countdownMs = 0)
        collector.start(start)
        collector.frames(30, start) { person(9f) }
        collector.start(start + 10_000) // e.g. user pressed Start again
        collector.frames(10, start + 10_000) { person(0.5f) }
        val status = collector.onFrame(start + 15_000, null) as Status.Done
        assertEquals(10, status.samples)
        assertEquals(0.5f, status.calibration.values[0], 1e-6f)
    }

    @Test
    fun `cancel stops collecting`() {
        val collector = CalibrationCollector(countdownMs = 0)
        collector.start(start)
        collector.cancel()
        assertFalse(collector.isCollecting)
        assertEquals(Status.Idle, collector.onFrame(start + 100, person(0.5f)))
    }

    // --- countdown (phone-specific: time to get back into position after pressing Start) ---------

    @Test
    fun `default countdown is 3 s and counts down 3, 2, 1`() {
        val collector = CalibrationCollector()
        collector.start(start)
        assertEquals(Status.Countdown(3), collector.onFrame(start, person(0.5f)))
        assertEquals(Status.Countdown(3), collector.onFrame(start + 900, person(0.5f)))
        assertEquals(Status.Countdown(2), collector.onFrame(start + 1_000, person(0.5f)))
        assertEquals(Status.Countdown(1), collector.onFrame(start + 2_999, person(0.5f)))
        assertTrue(collector.onFrame(start + 3_000, person(0.5f)) is Status.Collecting)
    }

    @Test
    fun `frames during the countdown are ignored, then 5 s are collected`() {
        val collector = CalibrationCollector()
        collector.start(start)
        collector.frames(30, start) { person(9f) }                  // 0 .. 2.9 s: user walking back, ignored
        collector.frames(50, start + 3_000) { person(0.5f) }        // 3.0 .. 7.9 s: sitting straight
        assertTrue(collector.onFrame(start + 7_900, null) is Status.Collecting)
        val status = collector.onFrame(start + 8_000, person(0.5f)) // 3 s countdown + 5 s collection

        status as Status.Done
        assertEquals(51, status.samples)
        assertEquals(0.5f, status.calibration.values[0], 1e-6f)
    }

    @Test
    fun `progress starts at 0 after the countdown`() {
        val collector = CalibrationCollector()
        collector.start(start)
        assertEquals(Status.Collecting(0.5f, 1), collector.onFrame(start + 5_500, person(0.5f)))
    }
}
