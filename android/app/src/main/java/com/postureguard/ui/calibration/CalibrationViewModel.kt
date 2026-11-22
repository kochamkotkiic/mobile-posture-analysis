package com.postureguard.ui.calibration

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.postureguard.calibration.CalibrationCollector
import com.postureguard.calibration.CalibrationRepository
import com.postureguard.calibration.SavedCalibration
import com.postureguard.camera.FrameAnalyzer
import com.postureguard.pose.Landmark
import com.postureguard.pose.PoseDetector
import com.postureguard.pose.PoseFrame
import com.postureguard.posture.FeatureExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** What the calibration screen is showing. */
sealed interface CalibrationPhase {
    /** Reading the saved calibration from the phone. */
    data object Loading : CalibrationPhase

    /** Waiting for the user to sit straight and press Start. [current] = existing calibration, if any. */
    data class Ready(val current: SavedCalibration?) : CalibrationPhase

    /** Countdown after Start, so the user can get back into position after touching the phone. */
    data class Countdown(val secondsLeft: Int) : CalibrationPhase

    /** Collecting samples for 5 s. */
    data class Collecting(val progress: Float, val samples: Int) : CalibrationPhase

    /** A calibration exists: the live check is shown. */
    data class Calibrated(val saved: SavedCalibration) : CalibrationPhase
}

data class CalibrationUiState(
    val phase: CalibrationPhase = CalibrationPhase.Loading,
    val personVisible: Boolean = false,
    val shoulderVisibility: Float = 0f,
    val nose: Landmark? = null,
    val leftShoulder: Landmark? = null,
    val rightShoulder: Landmark? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /** Current 9 features minus the calibration (≈ 0 when sitting like during calibration). */
    val differenceFromCalibration: FloatArray? = null,
    val message: String? = null,
) {
    /** Start only when both shoulders are clearly visible (calibration.py: visibility > 0.6). */
    val canStart: Boolean get() = shoulderVisibility > CalibrationCollector.MIN_SHOULDER_VISIBILITY
}

/**
 * Step 4: 5-second calibration with the same rules as the desktop app (plus a 3 s countdown), saved on the phone.
 * Pose detection runs with confidence 0.7 here (calibration value in server.py / calibration.py).
 */
class CalibrationViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CalibrationRepository(application)

    private val _state = MutableStateFlow(CalibrationUiState())
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    /** Camera frames are analysed on this background thread, never on the UI thread. */
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Used only on the pose result thread
    private val collector = CalibrationCollector()
    @Volatile private var startRequested = false
    @Volatile private var saved: SavedCalibration? = null

    private val detector: PoseDetector? = try {
        PoseDetector(
            application,
            minConfidence = PoseDetector.CALIBRATION_CONFIDENCE,
            onResult = ::onPose,
            onError = { e -> showMessage("Pose detection error: ${e.message}") },
        )
    } catch (e: Exception) {
        Log.e(TAG, "Could not create the pose detector", e)
        _state.update { it.copy(message = "Could not start pose detection: ${e.message}") }
        null
    }

    val analyzer = FrameAnalyzer { frame, _ -> detector?.detectAsync(frame) }

    init {
        // Load the saved calibration (and follow changes after saving)
        viewModelScope.launch {
            repository.calibration.collect { loaded ->
                saved = loaded
                _state.update { current ->
                    when (current.phase) {
                        CalibrationPhase.Loading, is CalibrationPhase.Calibrated ->
                            current.copy(phase = if (loaded == null) CalibrationPhase.Ready(null) else CalibrationPhase.Calibrated(loaded))
                        else -> current
                    }
                }
            }
        }
    }

    /** "Start" button: the next camera frame starts the countdown + 5 s (same clock as the frames). */
    fun startCalibration() {
        val phase = _state.value.phase
        if (!_state.value.canStart || phase is CalibrationPhase.Countdown || phase is CalibrationPhase.Collecting) return
        startRequested = true
        val seconds = (CalibrationCollector.COUNTDOWN_MS / 1000).toInt()
        _state.update { it.copy(phase = CalibrationPhase.Countdown(seconds), message = null) }
    }

    /** "Recalibrate" button: back to the start screen; the old calibration stays until a new one is saved. */
    fun recalibrate() {
        _state.update { it.copy(phase = CalibrationPhase.Ready(saved), message = null) }
    }

    /** "Keep current calibration" button on the start screen. */
    fun keepCurrent() {
        val current = saved ?: return
        _state.update { it.copy(phase = CalibrationPhase.Calibrated(current), message = null) }
    }

    // Called on a MediaPipe thread for every frame
    private fun onPose(frame: PoseFrame) {
        if (startRequested) {
            startRequested = false
            collector.start(frame.timestampMs)
        }
        val landmarks = frame.landmarks
        val status = collector.onFrame(frame.timestampMs, landmarks)

        val phase = when (status) {
            is CalibrationCollector.Status.Countdown -> CalibrationPhase.Countdown(status.secondsLeft)
            is CalibrationCollector.Status.Collecting -> CalibrationPhase.Collecting(status.progress, status.samples)
            is CalibrationCollector.Status.Done -> {
                val newCalibration = SavedCalibration(status.calibration, System.currentTimeMillis(), status.samples)
                saved = newCalibration
                viewModelScope.launch {
                    repository.save(newCalibration.calibration, newCalibration.sampleCount, newCalibration.calibratedAtMillis)
                }
                Log.i(TAG, "Calibration saved from ${status.samples} samples: ${status.calibration}")
                showMessage("Calibration saved (${status.samples} frames).")
                CalibrationPhase.Calibrated(newCalibration)
            }
            is CalibrationCollector.Status.TooFewSamples -> {
                showMessage(
                    "Not enough good frames (${status.samples} of ${status.needed}). " +
                        "Make sure both shoulders are visible and try again."
                )
                CalibrationPhase.Ready(saved)
            }
            CalibrationCollector.Status.Idle -> null // keep the current phase
        }

        val calibration = saved?.calibration
        val difference = if (landmarks != null && calibration != null) {
            calibration.normalize(FeatureExtractor.extract(landmarks))
        } else null

        _state.update {
            it.copy(
                phase = phase ?: it.phase,
                personVisible = frame.personVisible,
                shoulderVisibility = landmarks?.let(FeatureExtractor::shoulderVisibility) ?: 0f,
                nose = frame.nose,
                leftShoulder = frame.leftShoulder,
                rightShoulder = frame.rightShoulder,
                imageWidth = frame.imageWidth,
                imageHeight = frame.imageHeight,
                differenceFromCalibration = difference,
            )
        }
    }

    private fun showMessage(text: String) = _state.update { it.copy(message = text) }

    override fun onCleared() {
        analysisExecutor.shutdown()
        detector?.close()
    }

    private companion object {
        const val TAG = "Calibration"
    }
}
