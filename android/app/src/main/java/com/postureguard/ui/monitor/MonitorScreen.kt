package com.postureguard.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import com.postureguard.posture.Prediction
import com.postureguard.ui.common.CameraPreview
import com.postureguard.ui.common.LandmarkOverlay
import com.postureguard.ui.common.RequireCameraPermission
import java.util.Locale

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Grey = Color(0xFF616161)
private val Blue = Color(0xFF1565C0)
private val Orange = Color(0xFFEF6C00)

/** Step 5: live monitoring (minimal testing UI; the polished UI is Zenon's). */
@Composable
fun MonitorScreen(
    viewModel: MonitorViewModel,
    lifecycleOwner: LifecycleOwner,
    onRecalibrate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { viewModel.onScreenShown() }

    RequireCameraPermission(modifier) {
        val state by viewModel.state.collectAsState()
        val alertVisible by viewModel.alertVisible.collectAsState()
        val aspect = if (state.imageWidth > 0 && state.imageHeight > 0) state.imageWidth.toFloat() / state.imageHeight else 4f / 3f

        Row(modifier.fillMaxSize().padding(12.dp)) {
            Box(Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true)) {
                CameraPreview(viewModel.analysisExecutor, viewModel.analyzer, lifecycleOwner, Modifier.fillMaxSize())
                LandmarkOverlay(state.nose, state.leftShoulder, state.rightShoulder, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                state.recording?.let { RecordingCard(it) }
                StatusBox(state)
                Spacer(Modifier.height(12.dp))
                Details(state)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.testMode, onCheckedChange = viewModel::setTestMode)
                    Spacer(Modifier.width(8.dp))
                    Text("Test mode: alert after 30 s instead of 10 min")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRecalibrate) { Text("Recalibrate") }
                Text("Recalibrate whenever you move the phone or the chair.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                RecordingControls(state, viewModel)
                Spacer(Modifier.height(12.dp))
                Stats(state)
            }
        }

        if (alertVisible) {
            AlertDialog(
                onDismissRequest = viewModel::dismissAlert,
                confirmButton = { TextButton(onClick = viewModel::dismissAlert) { Text("OK") } },
                title = { Text("Time for a break") },
                text = {
                    val time = if (state.testMode) "30 seconds (test mode)" else "10 minutes"
                    Text("You have been sitting with bad posture for $time. Stand up, stretch for a moment, then sit up straight.")
                },
            )
        }
    }
}

/** Green "Good" / red "Bad posture" (after 3 s, D6) / grey when nobody is visible. */
@Composable
private fun StatusBox(state: MonitorUiState) {
    val posture = state.posture
    val error = state.error
    val (text, color) = when {
        error != null -> error to Red
        !state.modelReady -> "Loading the model…" to Grey
        posture == null -> "Starting…" to Grey
        !posture.personVisible -> "No one detected" to Grey
        posture.badPostureShown -> "Bad posture" to Red
        else -> "Good" to Green
    }
    Box(
        Modifier.fillMaxWidth().background(color, RoundedCornerShape(12.dp)).padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.headlineLarge)
    }
}

@Composable
private fun Details(state: MonitorUiState) {
    val posture = state.posture ?: return
    if (posture.personVisible && posture.smoothedLabel == Prediction.BAD) {
        Text(fmt("Bad posture for %.1f s", posture.badSeconds), style = MaterialTheme.typography.titleMedium)
    } else {
        Text("Posture OK", style = MaterialTheme.typography.titleMedium)
    }
    val alertAt = if (state.testMode) "30 s" else "10 min"
    Text("Sign after 3 s of bad posture · alert after $alertAt without a break", style = MaterialTheme.typography.bodySmall)
    state.lastPrediction?.let {
        val label = if (it.label == Prediction.BAD) "bad" else "good"
        Text(fmt("Last model answer: %s (%.0f%%)", label, it.confidence * 100), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Stats(state: MonitorUiState) {
    Text(
        fmt(
            "Pose %.1f FPS · model %.1f×/s · %.2f ms per model run",
            state.poseFps, state.classificationsPerSecond, state.classifyMs,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = Grey,
    )
}

private fun fmt(format: String, vararg args: Any) = String.format(Locale.US, format, *args)

// --- Step 6: guided test / recording ------------------------------------------------------------------

/** Big instruction card during the guided test (the user follows it, the app knows the ground truth). */
@Composable
private fun RecordingCard(recording: RecordingUi) {
    if (recording.guided) {
        val (title, color) = when (recording.stepKind) {
            "good" -> "Sit straight" to Blue
            "mild" -> "Slouch slightly" to Orange
            "strong" -> "Slouch strongly" to Red
            else -> "Get ready" to Grey
        }
        Column(
            Modifier.fillMaxWidth().background(color, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            Text(
                "Guided test · step ${recording.stepNumber}/${recording.stepCount} · ${recording.secondsLeftInStep} s left",
                color = Color.White, style = MaterialTheme.typography.bodyMedium,
            )
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text(recording.instruction, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Text(
            fmt("● Recording %d:%02d · %d frames", recording.elapsedSeconds / 60, recording.elapsedSeconds % 60, recording.frames),
            color = Red, style = MaterialTheme.typography.titleMedium,
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun RecordingControls(state: MonitorUiState, viewModel: MonitorViewModel) {
    Text("Step 6: real test", style = MaterialTheme.typography.titleSmall)
    if (state.recording == null) {
        Button(onClick = viewModel::startGuidedTest) { Text("Start guided test (5 min)") }
        OutlinedButton(onClick = viewModel::startFreeRecording) { Text("Record session (battery / heat)") }
    } else {
        OutlinedButton(onClick = viewModel::stopRecording) { Text("Stop recording") }
    }
    state.lastSessionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
