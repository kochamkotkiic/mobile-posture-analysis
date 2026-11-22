package com.postureguard.ui.monitor

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.postureguard.alert.AlertManager
import com.postureguard.calibration.CalibrationRepository
import com.postureguard.camera.FrameAnalyzer
import com.postureguard.pose.Landmark
import com.postureguard.pose.PoseDetector
import com.postureguard.pose.PoseFrame
import com.postureguard.posture.Calibration
import com.postureguard.posture.LocalOnnxClassifier
import com.postureguard.posture.PostureConfig
import com.postureguard.posture.PostureEvent
import com.postureguard.posture.PosturePipeline
import com.postureguard.posture.PostureState
import com.postureguard.posture.Prediction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class MonitorUiState(
    val modelReady: Boolean = false,
    val error: String? = null,
    /** null until the first processed frame (or while there is no calibration). */
    val posture: PostureState? = null,
    val lastPrediction: Prediction? = null,
    val nose: Landmark? = null,
    val leftShoulder: Landmark? = null,
    val rightShoulder: Landmark? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val testMode: Boolean = false,
    val poseFps: Double = 0.0,
    val classificationsPerSecond: Double = 0.0,
    val classifyMs: Double = 0.0,
) {
    val alertAfterMs: Long get() = if (testMode) MonitorViewModel.TEST_ALERT_MS else MonitorViewModel.ALERT_MS
}

/**
 * Step 5: live monitoring. camera -> pose (confidence 0.6) -> PosturePipeline (features - calibration ->
 * ONNX model at most 5x/s -> smoothing + timers) -> screen + 10-minute alert.
 *
 * Threads: MediaPipe results, calibration changes and button presses all go into ONE queue ([inputs]) that a
 * single background coroutine processes in order, so the pipeline is never used from two threads at once.
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CalibrationRepository(application)
    private val alertManager = AlertManager()

    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    /** For the rest of the app (Zenon: UI/friend feature, Nuno: cloud) - see SKILL.md contracts. */
    private val _postureState = MutableStateFlow<PostureState?>(null)
    val postureState: StateFlow<PostureState?> = _postureState.asStateFlow()
    private val _events = MutableSharedFlow<PostureEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PostureEvent> = _events.asSharedFlow()

    val alertVisible: StateFlow<Boolean> = alertManager.alertVisible

    /** null = still loading, then true/false. Used by MainActivity to pick the first screen. */
    val hasCalibration: StateFlow<Boolean?> = repository.calibration
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Camera frames are analysed on this background thread, never on the UI thread. */
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private sealed interface Input {
        data class Frame(val frame: PoseFrame) : Input
        data class NewCalibration(val calibration: Calibration?) : Input
        data class NewConfig(val config: PostureConfig) : Input
        data object Reset : Input
    }

    private val inputs = Channel<Input>(Channel.UNLIMITED)

    private val detector: PoseDetector? = try {
        PoseDetector(
            application,
            minConfidence = PoseDetector.MONITORING_CONFIDENCE,
            onResult = { frame -> inputs.trySend(Input.Frame(frame)) },
            onError = { e -> _state.update { it.copy(error = "Pose detection error: ${e.message}") } },
        )
    } catch (e: Exception) {
        Log.e(TAG, "Could not create the pose detector", e)
        _state.update { it.copy(error = "Could not start pose detection: ${e.message}") }
        null
    }

    val analyzer = FrameAnalyzer { frame, _ -> detector?.detectAsync(frame) }

    // Statistics, only touched by the processing coroutine
    private val frameTimes = ArrayDeque<Long>()
    private val classificationTimes = ArrayDeque<Long>()
    private var classifyMsAvg = 0.0
    private var lastLogMs = 0L

    init {
        viewModelScope.launch { repository.calibration.collect { inputs.trySend(Input.NewCalibration(it?.calibration)) } }

        viewModelScope.launch(Dispatchers.Default) {
            val classifier = try {
                val bytes = application.assets.open(LocalOnnxClassifier.MODEL_ASSET).use { it.readBytes() }
                LocalOnnxClassifier(bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Could not load the posture model", e)
                _state.update { it.copy(error = "Could not load the posture model: ${e.message}") }
                return@launch
            }
            val pipeline = PosturePipeline(classifier, configFor(testMode = false))
            _state.update { it.copy(modelReady = true) }
            try {
                for (input in inputs) {
                    when (input) {
                        is Input.Frame -> process(pipeline, input.frame)
                        is Input.NewCalibration -> pipeline.setCalibration(input.calibration)
                        is Input.NewConfig -> pipeline.setConfig(input.config)
                        Input.Reset -> pipeline.reset()
                    }
                }
            } finally {
                classifier.close()
            }
        }
    }

    /** Called when the monitoring screen appears: start fresh (no old timers from before a recalibration). */
    fun onScreenShown() {
        inputs.trySend(Input.Reset)
    }

    /** Test mode: alert after 30 s instead of 10 min (SKILL.md tip), so it can be tested on the phone. */
    fun setTestMode(enabled: Boolean) {
        _state.update { it.copy(testMode = enabled) }
        inputs.trySend(Input.NewConfig(configFor(enabled)))
    }

    fun dismissAlert() = alertManager.dismiss()

    private suspend fun process(pipeline: PosturePipeline, frame: PoseFrame) {
        val result = pipeline.onFrame(frame.timestampMs, frame.landmarks)

        addTime(frameTimes, frame.timestampMs, keep = 30)
        if (result?.prediction != null) addTime(classificationTimes, frame.timestampMs, keep = 10)
        result?.classifyMs?.let { classifyMsAvg = if (classifyMsAvg == 0.0) it else 0.9 * classifyMsAvg + 0.1 * it }

        result?.update?.events?.forEach { event ->
            Log.i(TAG, "Event: $event")
            alertManager.onEvent(event)
            _events.tryEmit(event)
        }
        _postureState.value = result?.update?.state

        _state.update {
            it.copy(
                posture = result?.update?.state,
                lastPrediction = result?.prediction ?: it.lastPrediction,
                nose = frame.nose,
                leftShoulder = frame.leftShoulder,
                rightShoulder = frame.rightShoulder,
                imageWidth = frame.imageWidth,
                imageHeight = frame.imageHeight,
                poseFps = rate(frameTimes),
                classificationsPerSecond = rate(classificationTimes),
                classifyMs = classifyMsAvg,
            )
        }
        logOncePerSecond(frame.timestampMs)
    }

    private fun addTime(times: ArrayDeque<Long>, timeMs: Long, keep: Int) {
        times.addLast(timeMs)
        while (times.size > keep) times.removeFirst()
    }

    private fun rate(times: ArrayDeque<Long>): Double =
        if (times.size < 2) 0.0 else (times.size - 1) * 1000.0 / (times.last() - times.first()).coerceAtLeast(1)

    private fun logOncePerSecond(nowMs: Long) {
        if (nowMs - lastLogMs < 1000) return
        lastLogMs = nowMs
        val s = _state.value
        val p = s.posture
        Log.i(
            TAG,
            String.format(
                Locale.US, "visible=%s smoothed=%s sign=%s bad=%.1fs last=%s fps=%.1f cls/s=%.1f model=%.2fms",
                p?.personVisible, p?.smoothedLabel, p?.badPostureShown, p?.badSeconds ?: 0f,
                s.lastPrediction, s.poseFps, s.classificationsPerSecond, s.classifyMs,
            )
        )
    }

    override fun onCleared() {
        inputs.close()
        analysisExecutor.shutdown()
        detector?.close()
    }

    companion object {
        private const val TAG = "Monitor"
        const val ALERT_MS = 10 * 60_000L // 10 min continuous bad posture (D6)
        const val TEST_ALERT_MS = 30_000L // test mode

        fun configFor(testMode: Boolean) = PostureConfig(longAlertMs = if (testMode) TEST_ALERT_MS else ALERT_MS)
    }
}
