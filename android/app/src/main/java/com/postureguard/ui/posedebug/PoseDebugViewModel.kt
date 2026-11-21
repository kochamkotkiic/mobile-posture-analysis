package com.postureguard.ui.posedebug

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.postureguard.camera.FrameAnalyzer
import com.postureguard.pose.Landmark
import com.postureguard.pose.PoseDetector
import com.postureguard.pose.PoseFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** What the Step 2 debug screen shows. */
data class PoseDebugState(
    val personVisible: Boolean = false,
    val nose: Landmark? = null,
    val leftShoulder: Landmark? = null,
    val rightShoulder: Landmark? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val poseFps: Double = 0.0,
    val detectionMs: Double = 0.0,
    val preprocessMs: Double = 0.0,
    val error: String? = null,
)

/**
 * Step 2: camera frames -> rotate + mirror -> MediaPipe -> state for the screen.
 * Owns the pose detector and the camera analysis thread; both are released in onCleared().
 */
class PoseDebugViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PoseDebugState())
    val state: StateFlow<PoseDebugState> = _state.asStateFlow()

    /** Camera frames are analysed on this background thread, never on the UI thread. */
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var lastPreprocessMs = 0.0
    private val recentTimestamps = ArrayDeque<Long>()
    private var detectionMsAvg = 0.0
    private var preprocessMsAvg = 0.0
    private var lastLogMs = 0L

    private val detector: PoseDetector? = try {
        PoseDetector(application, onResult = ::onPose, onError = ::onError)
    } catch (e: Exception) {
        onError(e)
        null
    }

    val analyzer = FrameAnalyzer { frame, preprocessMs ->
        lastPreprocessMs = preprocessMs
        detector?.detectAsync(frame)
    }

    // Called on a MediaPipe thread
    private fun onPose(frame: PoseFrame) {
        recentTimestamps.addLast(frame.timestampMs)
        while (recentTimestamps.size > 30) recentTimestamps.removeFirst()
        val fps = if (recentTimestamps.size >= 2) {
            (recentTimestamps.size - 1) * 1000.0 / (recentTimestamps.last() - recentTimestamps.first()).coerceAtLeast(1)
        } else 0.0
        if (!frame.detectionLatencyMs.isNaN()) detectionMsAvg = smooth(detectionMsAvg, frame.detectionLatencyMs)
        preprocessMsAvg = smooth(preprocessMsAvg, lastPreprocessMs)

        _state.update {
            it.copy(
                personVisible = frame.personVisible,
                nose = frame.nose,
                leftShoulder = frame.leftShoulder,
                rightShoulder = frame.rightShoulder,
                imageWidth = frame.imageWidth,
                imageHeight = frame.imageHeight,
                poseFps = fps,
                detectionMs = detectionMsAvg,
                preprocessMs = preprocessMsAvg,
            )
        }
        logOncePerSecond(frame, fps)
    }

    private fun onError(error: Throwable) {
        Log.e(TAG, "Pose detection error", error)
        _state.update { it.copy(error = "${error::class.simpleName}: ${error.message}") }
    }

    private fun smooth(average: Double, value: Double) = if (average == 0.0) value else 0.9 * average + 0.1 * value

    private fun logOncePerSecond(frame: PoseFrame, fps: Double) {
        if (frame.timestampMs - lastLogMs < 1000) return
        lastLogMs = frame.timestampMs
        fun f(l: Landmark?) = if (l == null) "-" else String.format(Locale.US, "%.3f,%.3f,%.3f", l.x, l.y, l.z)
        Log.i(
            TAG,
            String.format(
                Locale.US, "visible=%s nose=%s left_sh=%s right_sh=%s img=%dx%d fps=%.1f detect=%.1fms prep=%.1fms",
                frame.personVisible, f(frame.nose), f(frame.leftShoulder), f(frame.rightShoulder),
                frame.imageWidth, frame.imageHeight, fps, detectionMsAvg, preprocessMsAvg,
            )
        )
    }

    override fun onCleared() {
        analysisExecutor.shutdown()
        detector?.close()
    }

    private companion object {
        const val TAG = "PoseDebug"
    }
}
