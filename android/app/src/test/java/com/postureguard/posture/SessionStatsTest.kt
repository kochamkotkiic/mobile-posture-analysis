package com.postureguard.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStatsTest {

    private fun state(visible: Boolean = true, signShown: Boolean = false) =
        PostureState(personVisible = visible, smoothedLabel = if (signShown) 1 else 0, confidence = 1f,
            badSeconds = 0f, badPostureShown = signShown)

    /** Feeds frames every [stepMs] from [fromMs] (inclusive) to [toMs] (exclusive). */
    private fun SessionStats.feed(fromMs: Long, toMs: Long, stepMs: Long = 40, s: PostureState = state()) {
        var t = fromMs
        while (t < toMs) {
            onFrame(t, s)
            t += stepMs
        }
    }

    @Test
    fun countsTrackedAndBadTime() {
        val stats = SessionStats()
        stats.feed(0, 10_000)                                   // good
        stats.feed(10_000, 14_000, s = state(signShown = true)) // sign shown
        stats.onFrame(14_000, state())
        assertEquals(14_000, stats.current.trackedMs)
        assertEquals(4_000, stats.current.badMs)
        assertEquals(10_000, stats.current.goodMs)
        assertEquals(10f / 14f, stats.current.goodFraction!!, 1e-4f)
    }

    @Test
    fun timeWithoutAPersonIsNotTracked() {
        val stats = SessionStats()
        stats.feed(0, 5_000)
        stats.feed(5_000, 8_000, s = state(visible = false))
        stats.feed(8_000, 10_000)
        stats.onFrame(10_000, state())
        // 0..4.96 s + 7.96..10 s: an interval counts when the frame that ends it shows a person
        assertEquals(7_000, stats.current.trackedMs)
    }

    @Test
    fun longGapsAreIgnored() {
        val stats = SessionStats(maxGapMs = 1_000)
        stats.onFrame(0, state())
        stats.onFrame(40, state())
        stats.onFrame(60_000, state()) // app was paused for a minute
        stats.onFrame(60_040, state())
        assertEquals(80, stats.current.trackedMs)
    }

    @Test
    fun noFractionInTheFirstSeconds() {
        val stats = SessionStats()
        stats.feed(0, 4_000)
        assertNull(stats.current.goodFraction)
    }

    @Test
    fun countsAlertsAndResets() {
        val stats = SessionStats()
        stats.feed(0, 6_000)
        stats.onEvent(PostureEvent.LongBadPostureAlert)
        stats.onEvent(PostureEvent.PostureRecovered)
        stats.onEvent(PostureEvent.LongBadPostureAlert)
        assertEquals(2, stats.current.alerts)
        stats.reset()
        assertEquals(SessionSummary(), stats.current)
        stats.onFrame(100_000, state()) // first frame after reset: no time yet
        assertEquals(0, stats.current.trackedMs)
    }
}
