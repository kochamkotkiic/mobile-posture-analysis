package com.postureguard.posture

/** Numbers from SKILL.md section 5.5 / server.py. */
data class PostureConfig(
    val smoothN: Int = 20,           // keep the last 20 predictions
    val badVotesNeeded: Int = 14,    // smoothed = bad if >= 14 of them are bad (70%)
    val badSignMs: Long = 3_000,     // red "Bad posture" sign after 3 s of smoothed-bad posture
    val longAlertMs: Long = 600_000, // alert after 10 min of CONTINUOUS smoothed-bad posture
)

/**
 * Smoothing + timers, ported from the per-frame loop of server.py. Plain Kotlin, no clock inside:
 * the caller passes the time, so tests can "fast-forward" (and the app passes SystemClock time).
 *
 * Call [onFrame] for EVERY camera frame:
 *  - personVisible = false  -> timers reset; the last predictions are KEPT (as in server.py)
 *  - prediction != null     -> a new classification (at most every 0.2 s, see InferenceThrottle)
 */
class PostureStateMachine(private val config: PostureConfig = PostureConfig()) {

    data class Update(val state: PostureState, val events: List<PostureEvent>)

    private val predictions = ArrayDeque<Int>()
    private var smoothedLabel = Prediction.GOOD
    private var lastConfidence = 1f
    private var badSinceMs: Long? = null
    private var signShown = false
    private var alertFired = false

    fun onFrame(nowMs: Long, personVisible: Boolean, prediction: Prediction? = null): Update {
        if (!personVisible) {
            endBadEpisode()
            return Update(PostureState(false, smoothedLabel, lastConfidence, 0f, false), emptyList())
        }

        if (prediction != null) {
            predictions.addLast(prediction.label)
            while (predictions.size > config.smoothN) predictions.removeFirst()
            smoothedLabel = if (predictions.count { it == Prediction.BAD } >= config.badVotesNeeded) {
                Prediction.BAD
            } else {
                Prediction.GOOD
            }
            lastConfidence = prediction.confidence
        }

        val events = mutableListOf<PostureEvent>()
        var badMs = 0L
        if (smoothedLabel == Prediction.BAD) {
            val since = badSinceMs ?: nowMs.also { badSinceMs = it }
            badMs = nowMs - since
            if (badMs >= config.badSignMs) signShown = true
            if (badMs >= config.longAlertMs && !alertFired) {
                alertFired = true
                events += PostureEvent.LongBadPostureAlert
            }
        } else {
            if (signShown) events += PostureEvent.PostureRecovered
            endBadEpisode()
        }

        return Update(PostureState(true, smoothedLabel, lastConfidence, badMs / 1000f, signShown), events)
    }

    /** Good posture or person lost: the next bad posture starts a new episode (new 3 s / 10 min timers). */
    private fun endBadEpisode() {
        badSinceMs = null
        signShown = false
        alertFired = false
    }
}
