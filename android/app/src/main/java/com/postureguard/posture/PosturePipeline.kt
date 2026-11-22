package com.postureguard.posture

import com.postureguard.pose.Landmark

/**
 * Connects the pieces for live monitoring, frame by frame (= the monitoring loop of server.py):
 *   landmarks -> 9 features -> minus calibration -> classifier (at most every 200 ms) -> state machine.
 * Plain Kotlin: the classifier is an interface (LocalOnnxClassifier on the phone, a fake in tests, the cloud later).
 * Call from ONE coroutine / thread only.
 */
class PosturePipeline(
    private val classifier: PostureClassifier,
    config: PostureConfig = PostureConfig(),
    private val inferenceIntervalMs: Long = InferenceThrottle.DEFAULT_INTERVAL_MS,
) {
    /** One processed frame: the state-machine update plus the new raw prediction, if one was made. */
    data class Result(
        val update: PostureStateMachine.Update,
        val prediction: Prediction?,
        val classifyMs: Double?,
    )

    private var config = config
    private var machine = PostureStateMachine(config)
    private var throttle = InferenceThrottle(inferenceIntervalMs)

    var calibration: Calibration? = null
        private set

    /** New calibration (or a recalibration): start from scratch, old predictions were relative to the old one. */
    fun setCalibration(newCalibration: Calibration?) {
        calibration = newCalibration
        reset()
    }

    /** E.g. test mode (shorter alert time). Also resets. */
    fun setConfig(newConfig: PostureConfig) {
        config = newConfig
        reset()
    }

    /** Forget predictions and timers (new calibration, back on the monitoring screen after a pause). */
    fun reset() {
        machine = PostureStateMachine(config)
        throttle = InferenceThrottle(inferenceIntervalMs)
    }

    /**
     * [landmarks] = null when no person is visible. Returns null while there is no calibration
     * (nothing can be judged without it).
     */
    suspend fun onFrame(timestampMs: Long, landmarks: List<Landmark>?): Result? {
        val currentCalibration = calibration ?: return null

        var prediction: Prediction? = null
        var classifyMs: Double? = null
        // Like server.py: classify only frames with a person, at most every 0.2 s
        if (landmarks != null && throttle.shouldRun(timestampMs)) {
            val input = currentCalibration.normalize(FeatureExtractor.extract(landmarks))
            val start = System.nanoTime()
            prediction = classifier.classify(input)
            classifyMs = (System.nanoTime() - start) / 1_000_000.0
        }

        val update = machine.onFrame(timestampMs, personVisible = landmarks != null, prediction = prediction)
        return Result(update, prediction, classifyMs)
    }
}
