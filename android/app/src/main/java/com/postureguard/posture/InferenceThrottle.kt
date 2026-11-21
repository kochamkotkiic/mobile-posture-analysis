package com.postureguard.posture

/**
 * Pose detection runs on every camera frame (~23 FPS), but the model judges at most every [intervalMs]
 * (server.py: INFERENCE_INTERVAL = 0.2 s -> 5 Hz). Call only for frames where a person is visible,
 * like server.py does.
 */
class InferenceThrottle(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {
    private var lastRunMs: Long? = null

    /** True if a new classification should run at [nowMs]; remembers the time when it returns true. */
    fun shouldRun(nowMs: Long): Boolean {
        val last = lastRunMs
        if (last != null && nowMs - last < intervalMs) return false
        lastRunMs = nowMs
        return true
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 200L
    }
}
