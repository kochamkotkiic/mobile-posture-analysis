package com.postureguard.posture

/** What the monitoring screen shows about the current session. */
data class SessionSummary(
    /** Time with a person in view. */
    val trackedMs: Long = 0,
    /** Part of [trackedMs] with the red "Bad posture" sign shown (what the user saw as bad). */
    val badMs: Long = 0,
    /** Number of 10-minute alerts (LongBadPostureAlert). */
    val alerts: Int = 0,
) {
    val goodMs: Long get() = trackedMs - badMs

    /** 0..1, null until there is at least a few seconds of data. */
    val goodFraction: Float? get() = if (trackedMs < MIN_TRACKED_FOR_FRACTION_MS) null else goodMs.toFloat() / trackedMs

    companion object {
        const val MIN_TRACKED_FOR_FRACTION_MS = 5_000L
    }
}

/**
 * Adds up the time of a monitoring session from the per-frame [PostureState]s. Plain Kotlin, the caller passes
 * the frame time (same clock as [PostureStateMachine]).
 *
 * "Good" means "the red sign was not shown", i.e. what the user experienced, not the raw model answers.
 * Gaps longer than [maxGapMs] between frames (app paused, camera stopped) are not counted.
 */
class SessionStats(private val maxGapMs: Long = 1_000) {

    private var lastFrameMs: Long? = null
    private var summary = SessionSummary()

    val current: SessionSummary get() = summary

    fun onFrame(nowMs: Long, state: PostureState?) {
        val last = lastFrameMs
        lastFrameMs = nowMs
        if (last == null || state == null || !state.personVisible) return
        val dt = nowMs - last
        if (dt <= 0 || dt > maxGapMs) return
        summary = summary.copy(
            trackedMs = summary.trackedMs + dt,
            badMs = summary.badMs + if (state.badPostureShown) dt else 0,
        )
    }

    fun onEvent(event: PostureEvent) {
        if (event == PostureEvent.LongBadPostureAlert) summary = summary.copy(alerts = summary.alerts + 1)
    }

    fun reset() {
        lastFrameMs = null
        summary = SessionSummary()
    }
}
