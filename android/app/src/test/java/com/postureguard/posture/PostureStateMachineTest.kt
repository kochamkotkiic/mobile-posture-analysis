package com.postureguard.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenarios from SKILL.md step 3. Time is simulated: one prediction every 200 ms (5 Hz, like the app).
 * With all-bad predictions the smoothed label turns bad at the 14th prediction, i.e. 14 x 200 ms = 2.8 s after the
 * user starts slouching. That is the ~2.8 s "smoothing lag" of the desktop app (D7) before the 3 s timer starts.
 */
class PostureStateMachineTest {

    private val good = Prediction(Prediction.GOOD, 0.9f)
    private val bad = Prediction(Prediction.BAD, 0.9f)

    /** Simulated app: feeds one prediction every [stepMs], returns the last update and all events. */
    private class Sim(val machine: PostureStateMachine = PostureStateMachine(), var nowMs: Long = 1_000_000) {
        val events = mutableListOf<PostureEvent>()
        lateinit var last: PostureStateMachine.Update

        fun feed(prediction: Prediction, count: Int, stepMs: Long = 200) {
            repeat(count) {
                nowMs += stepMs
                step(machine.onFrame(nowMs, personVisible = true, prediction = prediction))
            }
        }

        /** A frame without a new prediction (between classifications) at [nowMs] + [afterMs]. */
        fun frameAfter(afterMs: Long, visible: Boolean = true) {
            nowMs += afterMs
            step(machine.onFrame(nowMs, personVisible = visible))
        }

        /** Keeps the same prediction coming for [durationMs]. */
        fun hold(prediction: Prediction, durationMs: Long) = feed(prediction, (durationMs / 200).toInt())

        private fun step(update: PostureStateMachine.Update) {
            last = update
            events += update.events
        }

        fun alerts() = events.count { it == PostureEvent.LongBadPostureAlert }
        fun recoveries() = events.count { it == PostureEvent.PostureRecovered }
    }

    // --- smoothing: 14 of the last 20 ---------------------------------------------------------------

    @Test
    fun `13 of 20 bad is still good, 14 of 20 is bad`() {
        val sim = Sim()
        sim.feed(good, 7)
        sim.feed(bad, 13)
        assertEquals(Prediction.GOOD, sim.last.state.smoothedLabel) // 13 bad + 7 good

        sim.feed(bad, 1) // oldest good drops out -> 14 bad of 20
        assertEquals(Prediction.BAD, sim.last.state.smoothedLabel)
    }

    @Test
    fun `smoothed label turns bad at the 14th bad prediction`() {
        val sim = Sim()
        sim.feed(bad, 13)
        assertEquals(Prediction.GOOD, sim.last.state.smoothedLabel)
        sim.feed(bad, 1)
        assertEquals(Prediction.BAD, sim.last.state.smoothedLabel)
    }

    @Test
    fun `back to good after 7 good predictions`() {
        val sim = Sim()
        sim.feed(bad, 20)
        sim.feed(good, 6) // 14 bad left
        assertEquals(Prediction.BAD, sim.last.state.smoothedLabel)
        sim.feed(good, 1) // 13 bad left
        assertEquals(Prediction.GOOD, sim.last.state.smoothedLabel)
    }

    // --- 3 s "Bad posture" sign ------------------------------------------------------------------------

    @Test
    fun `bad for 2,9 s shows no sign, 3,1 s shows the sign`() {
        val sim = Sim()
        sim.feed(bad, 14) // smoothed bad starts now (bad timer = 0)
        assertEquals(0f, sim.last.state.badSeconds, 1e-6f)

        sim.frameAfter(2_900)
        assertFalse(sim.last.state.badPostureShown)

        sim.frameAfter(200) // 3.1 s
        assertTrue(sim.last.state.badPostureShown)
        assertEquals(3.1f, sim.last.state.badSeconds, 1e-3f)
    }

    @Test
    fun `sign appears 5,8 s after slouching starts (2,8 s smoothing + 3 s timer)`() {
        val sim = Sim()
        val start = sim.nowMs
        sim.feed(bad, 1)
        while (!sim.last.state.badPostureShown) sim.feed(bad, 1)
        // predictions at +0.2 s, +0.4 s, ...; the 14th (+2.8 s) makes the smoothed label bad; sign 3 s later
        assertEquals(5_800L, sim.nowMs - start)
    }

    @Test
    fun `short slouch then good gives no PostureRecovered event`() {
        val sim = Sim()
        sim.feed(bad, 14)
        sim.frameAfter(1_000)
        sim.feed(good, 7)
        assertEquals(Prediction.GOOD, sim.last.state.smoothedLabel)
        assertEquals(0, sim.recoveries())
    }

    @Test
    fun `good posture after the sign fires PostureRecovered once`() {
        val sim = Sim()
        sim.feed(bad, 14)
        sim.frameAfter(3_000)
        assertTrue(sim.last.state.badPostureShown)
        sim.feed(good, 20)
        assertFalse(sim.last.state.badPostureShown)
        assertEquals(1, sim.recoveries())
    }

    // --- person not visible ----------------------------------------------------------------------------

    @Test
    fun `person lost resets the timers but keeps the predictions`() {
        val sim = Sim()
        sim.feed(bad, 14)
        sim.frameAfter(5_000)
        assertTrue(sim.last.state.badPostureShown)

        sim.frameAfter(200, visible = false)
        assertFalse(sim.last.state.personVisible)
        assertEquals(0f, sim.last.state.badSeconds, 0f)
        assertFalse(sim.last.state.badPostureShown)

        // Back in view: still smoothed-bad (predictions kept, as in server.py), but the timer starts again
        sim.frameAfter(1_000, visible = true)
        assertEquals(Prediction.BAD, sim.last.state.smoothedLabel)
        assertEquals(0f, sim.last.state.badSeconds, 0f)
        sim.frameAfter(2_000)
        assertFalse(sim.last.state.badPostureShown)
    }

    // --- 10 min alert --------------------------------------------------------------------------------

    @Test
    fun `599 s bad gives no alert, 600 s gives exactly one`() {
        val sim = Sim()
        sim.feed(bad, 14) // smoothed bad, timer = 0
        sim.hold(bad, 599_000)
        assertEquals(599f, sim.last.state.badSeconds, 1e-3f)
        assertEquals(0, sim.alerts())

        sim.hold(bad, 1_000)
        assertEquals(1, sim.alerts())
    }

    @Test
    fun `bad for 20 min still gives only one alert`() {
        val sim = Sim()
        sim.feed(bad, 14)
        sim.hold(bad, 20 * 60_000L)
        assertEquals(1, sim.alerts())
    }

    @Test
    fun `good posture in between allows a second alert`() {
        val sim = Sim()
        sim.feed(bad, 14)
        sim.hold(bad, 600_000)
        assertEquals(1, sim.alerts())

        sim.feed(good, 20)
        assertEquals(Prediction.GOOD, sim.last.state.smoothedLabel)

        sim.feed(bad, 14)
        sim.hold(bad, 600_000)
        assertEquals(2, sim.alerts())
    }

    @Test
    fun `alert needs CONTINUOUS bad posture, a good break restarts the 10 min`() {
        val sim = Sim()
        sim.feed(bad, 14)
        sim.hold(bad, 500_000)
        sim.feed(good, 7) // 13 bad + 7 good -> smoothed good -> episode ends
        sim.feed(bad, 14) // the 7 good ones must leave the window before 14 of 20 are bad again -> new episode
        assertEquals(Prediction.BAD, sim.last.state.smoothedLabel)
        sim.hold(bad, 500_000)
        assertEquals(0, sim.alerts()) // 500 s + 500 s, but not continuous
    }

    @Test
    fun `person lost restarts the 10 min`() {
        val sim = Sim()
        sim.feed(bad, 14)
        sim.hold(bad, 500_000)
        sim.frameAfter(200, visible = false)
        sim.frameAfter(200, visible = true)
        sim.hold(bad, 500_000)
        assertEquals(0, sim.alerts())
    }

    @Test
    fun `alert time can be lowered for testing on the phone`() {
        val sim = Sim(PostureStateMachine(PostureConfig(longAlertMs = 30_000)))
        sim.feed(bad, 14)
        sim.hold(bad, 30_000)
        assertEquals(1, sim.alerts())
    }

    @Test
    fun `confidence is the one of the last raw prediction`() {
        val sim = Sim()
        sim.feed(Prediction(Prediction.BAD, 0.73f), 1)
        assertEquals(0.73f, sim.last.state.confidence, 0f)
    }
}
