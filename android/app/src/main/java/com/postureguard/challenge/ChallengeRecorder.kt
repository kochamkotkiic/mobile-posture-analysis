package com.postureguard.challenge

import kotlin.math.ceil

/**
 * One processed camera frame, as published by MonitorViewModel.challengeFrames (read-only hook).
 * [normalizedFeatures] = the 9 features minus calibration, only on frames where the model ran (~5x/s),
 * otherwise null. These are exactly the numbers the phone's own model judged.
 */
class ChallengeFrame(
    val timestampMs: Long,
    val personVisible: Boolean,
    val normalizedFeatures: FloatArray?,
)

/**
 * Friend challenge "hold good posture for 5 s": a short countdown (the user tapped the phone at the far end of
 * the desk and must sit back), then [durationMs] of recording. Plain Kotlin: time comes from the frames, so it is
 * unit-tested on the JVM, like the calibration collector.
 *
 * Usage: [start], then [onFrame] for every frame until it returns [Status.Done].
 */
class ChallengeRecorder(
    private val countdownMs: Long = 3_000,
    private val durationMs: Long = 5_000,
) {
    sealed interface Status {
        data object Idle : Status
        data class Countdown(val secondsLeft: Int) : Status
        data class Recording(val progress: Float, val samples: Int, val secondsLeft: Int) : Status

        /** [visibleFraction] = share of recording frames with a person in view (0..1). */
        class Done(val samples: List<FloatArray>, val visibleFraction: Float) : Status
    }

    private var status: Status = Status.Idle
    private var startMs: Long? = null
    private val samples = mutableListOf<FloatArray>()
    private var frames = 0
    private var visibleFrames = 0

    val current: Status get() = status
    val isActive: Boolean get() = status is Status.Countdown || status is Status.Recording

    /** The countdown starts with the next frame (same clock as the frames). */
    fun start() {
        startMs = null
        samples.clear()
        frames = 0
        visibleFrames = 0
        status = Status.Countdown(secondsOf(countdownMs))
    }

    fun cancel() {
        startMs = null
        samples.clear()
        status = Status.Idle
    }

    fun onFrame(frame: ChallengeFrame): Status {
        if (!isActive) return status
        val start = startMs ?: frame.timestampMs.also { startMs = it }
        val t = frame.timestampMs - start
        status = when {
            t < countdownMs -> Status.Countdown(secondsOf(countdownMs - t))
            t <= countdownMs + durationMs -> {
                frames++
                if (frame.personVisible) visibleFrames++
                frame.normalizedFeatures?.let { samples += it.copyOf() }
                val elapsed = t - countdownMs
                Status.Recording(
                    progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f),
                    samples = samples.size,
                    secondsLeft = secondsOf(durationMs - elapsed),
                )
            }
            else -> Status.Done(samples.toList(), if (frames == 0) 0f else visibleFrames.toFloat() / frames)
        }
        return status
    }

    private fun secondsOf(ms: Long): Int = ceil(ms / 1000.0).toInt()
}
