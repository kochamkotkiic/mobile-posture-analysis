package com.postureguard.challenge

import kotlin.math.ceil

/**
 * One processed camera frame, as published by MonitorViewModel.challengeFrames (read-only hook).
 * [normalizedFeatures] = the 9 features minus calibration, on every frame with a person in view
 * (null when nobody is visible or there is no calibration).
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
 * The server needs at least [minSamples] samples. A slow device (the emulator runs pose detection at ~3 FPS) may
 * not reach that in 5 s, so recording continues for up to [maxExtraMs] more until it does.
 *
 * Usage: [start], then [onFrame] for every frame until it returns [Status.Done].
 */
class ChallengeRecorder(
    private val countdownMs: Long = 3_000,
    private val durationMs: Long = 5_000,
    private val minSamples: Int = 15,
    private val maxExtraMs: Long = 5_000,
    private val maxSamples: Int = 200, // server limit per upload
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
        if (t < countdownMs) {
            status = Status.Countdown(secondsOf(countdownMs - t))
            return status
        }
        val elapsed = t - countdownMs
        val finished = (elapsed > durationMs && samples.size >= minSamples) || elapsed > durationMs + maxExtraMs
        status = if (finished) {
            Status.Done(samples.toList(), if (frames == 0) 0f else visibleFrames.toFloat() / frames)
        } else {
            frames++
            if (frame.personVisible) visibleFrames++
            if (samples.size < maxSamples) frame.normalizedFeatures?.let { samples += it.copyOf() }
            Status.Recording(
                progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f),
                samples = samples.size,
                secondsLeft = secondsOf(durationMs - elapsed).coerceAtLeast(1), // "1 s" while waiting for samples
            )
        }
        return status
    }

    private fun secondsOf(ms: Long): Int = ceil(ms / 1000.0).toInt()
}
