package com.postureguard.calibration

import com.postureguard.pose.Landmark
import com.postureguard.posture.Calibration
import com.postureguard.posture.FeatureExtractor

/**
 * The 5-second calibration, same rules as server.py (lines 99-148) / calibration.py:
 *  - Phone-specific addition: after start() a short countdown ([countdownMs], default 3 s) lets the user get
 *    back into position after touching the phone; frames during the countdown are ignored.
 *  - then, for 5 s, every frame where the average shoulder visibility is > 0.6 adds one sample (9 features)
 *  - after 5 s: >= 10 samples -> calibration = mean of each feature; fewer -> "not enough samples"
 * Plain Kotlin (no Android, no clock inside): the caller passes the frame time, so it is unit-tested on the PC.
 * Use it from one thread only (the pose result thread).
 */
class CalibrationCollector(
    private val countdownMs: Long = COUNTDOWN_MS,
    private val durationMs: Long = DURATION_MS,
    private val minShoulderVisibility: Float = MIN_SHOULDER_VISIBILITY,
    private val minSamples: Int = MIN_SAMPLES,
) {
    sealed interface Status {
        data object Idle : Status
        data class Countdown(val secondsLeft: Int) : Status
        data class Collecting(val progress: Float, val samples: Int) : Status
        data class Done(val calibration: Calibration, val samples: Int) : Status
        data class TooFewSamples(val samples: Int, val needed: Int) : Status
    }

    private var startMs: Long? = null
    private val samples = mutableListOf<FloatArray>()

    val isCollecting: Boolean get() = startMs != null

    /** Starts the countdown, then a new collection (old samples are thrown away, like samples = [] in Python). */
    fun start(nowMs: Long) {
        startMs = nowMs
        samples.clear()
    }

    fun cancel() {
        startMs = null
        samples.clear()
    }

    /** Call for every pose result. [landmarks] = null when no person was detected. */
    fun onFrame(nowMs: Long, landmarks: List<Landmark>?): Status {
        val start = startMs ?: return Status.Idle

        val sinceStartMs = nowMs - start
        if (sinceStartMs < countdownMs) {
            return Status.Countdown(((countdownMs - sinceStartMs + 999) / 1000).toInt()) // 3, 2, 1
        }

        // Same order as Python: first take the sample, then check whether the 5 s are over
        if (landmarks != null && FeatureExtractor.shoulderVisibility(landmarks) > minShoulderVisibility) {
            samples += FeatureExtractor.extract(landmarks)
        }

        val elapsedMs = sinceStartMs - countdownMs
        if (elapsedMs < durationMs) {
            return Status.Collecting((elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f), samples.size)
        }

        val result = if (samples.size >= minSamples) {
            Status.Done(Calibration(mean(samples)), samples.size)
        } else {
            Status.TooFewSamples(samples.size, minSamples)
        }
        cancel()
        return result
    }

    private fun mean(rows: List<FloatArray>): FloatArray =
        FloatArray(FeatureExtractor.N_FEATURES) { i -> (rows.sumOf { it[i].toDouble() } / rows.size).toFloat() }

    companion object {
        const val COUNTDOWN_MS = 3_000L
        const val DURATION_MS = 5_000L
        const val MIN_SHOULDER_VISIBILITY = 0.6f
        const val MIN_SAMPLES = 10
    }
}
