package com.postureguard.ui.calibration

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.postureguard.calibration.CalibrationCollector
import com.postureguard.calibration.SavedCalibration
import com.postureguard.ui.common.CameraPreview
import com.postureguard.ui.common.LandmarkOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private val Green = Color(0xFF2E7D32)
private val Orange = Color(0xFFEF6C00)
private val Red = Color(0xFFC62828)

/** Step 4: calibration (minimal testing UI; the polished UI is Zenon's). */
@Composable
fun CalibrationScreen(viewModel: CalibrationViewModel, lifecycleOwner: LifecycleOwner, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    if (!hasCameraPermission) {
        Column(modifier.padding(24.dp)) {
            Text("PostureGuard needs the camera to see your posture.", style = MaterialTheme.typography.titleMedium)
            Text("Images stay on this phone and are never uploaded.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        }
        return
    }

    val state by viewModel.state.collectAsState()
    val aspect = if (state.imageWidth > 0 && state.imageHeight > 0) state.imageWidth.toFloat() / state.imageHeight else 4f / 3f

    Row(modifier.fillMaxSize().padding(12.dp)) {
        Box(Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true)) {
            CameraPreview(viewModel.analysisExecutor, viewModel.analyzer, lifecycleOwner, Modifier.fillMaxSize())
            LandmarkOverlay(state.nose, state.leftShoulder, state.rightShoulder, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Calibration", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            DetectionHint(state)
            Spacer(Modifier.height(12.dp))

            when (val phase = state.phase) {
                CalibrationPhase.Loading -> CircularProgressIndicator()
                is CalibrationPhase.Ready -> ReadyPanel(state, phase, viewModel)
                is CalibrationPhase.Countdown -> CountdownPanel(phase)
                is CalibrationPhase.Collecting -> CollectingPanel(phase)
                is CalibrationPhase.Calibrated -> CalibratedPanel(state, phase.saved, viewModel)
            }

            state.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = if (it.startsWith("Calibration saved")) Green else Red)
            }
        }
    }
}

/** Same thresholds as the desktop calibration window: > 0.7 excellent, otherwise adjust. */
@Composable
private fun DetectionHint(state: CalibrationUiState) {
    val (text, color) = when {
        !state.personVisible -> "⚠ Nobody detected: sit in front of the camera" to Red
        state.shoulderVisibility > 0.7f -> "✓ Detection: excellent" to Green
        else -> "⚠ Adjust your position: both shoulders must be visible" to Orange
    }
    Text(text, color = color, style = MaterialTheme.typography.titleMedium)
    Text(fmt("Shoulder visibility: %.2f", state.shoulderVisibility), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ReadyPanel(state: CalibrationUiState, phase: CalibrationPhase.Ready, viewModel: CalibrationViewModel) {
    Text("1. Put the phone where it will stay (end of the desk, lens about chin height).")
    Text("2. Sit up STRAIGHT and look at the screen. Both shoulders must be visible.")
    Text("3. Press Start, go back to your position during the 3 s countdown, then hold still for 5 seconds.")
    Spacer(Modifier.height(12.dp))
    Button(onClick = viewModel::startCalibration, enabled = state.canStart) { Text("Start") }
    if (!state.canStart) {
        Text(
            fmt("Start is possible when shoulder visibility is above %.1f.", CalibrationCollector.MIN_SHOULDER_VISIBILITY),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    phase.current?.let { current ->
        Spacer(Modifier.height(12.dp))
        Text("Current calibration: ${formatTime(current.calibratedAtMillis)}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = viewModel::keepCurrent) { Text("Keep current calibration") }
    }
}

@Composable
private fun CountdownPanel(phase: CalibrationPhase.Countdown) {
    Text("Get into position…", style = MaterialTheme.typography.titleMedium)
    Text("${phase.secondsLeft}", style = MaterialTheme.typography.displayLarge, color = Orange)
    Text("Sit up straight and look at the screen. Collecting starts after the countdown.")
}

@Composable
private fun CollectingPanel(phase: CalibrationPhase.Collecting) {
    val secondsLeft = ceil((1f - phase.progress) * CalibrationCollector.DURATION_MS / 1000f).toInt()
    Text("Hold still… ${secondsLeft}s", style = MaterialTheme.typography.titleMedium, color = Green)
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(progress = { phase.progress }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Text("Good frames: ${phase.samples} (at least ${CalibrationCollector.MIN_SAMPLES} needed)")
}

@Composable
private fun CalibratedPanel(state: CalibrationUiState, saved: SavedCalibration, viewModel: CalibrationViewModel) {
    Text("✅ Calibrated", style = MaterialTheme.typography.titleMedium, color = Green)
    Text("${formatTime(saved.calibratedAtMillis)} · ${saved.sampleCount} frames", style = MaterialTheme.typography.bodySmall)
    Text("Recalibrate whenever you move the phone or the chair.", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = viewModel::recalibrate) { Text("Recalibrate") }

    Spacer(Modifier.height(12.dp))
    Text("Check: difference from calibration", style = MaterialTheme.typography.titleSmall)
    Text("Sit like during calibration → all values close to 0. Slouch → nose y grows.", style = MaterialTheme.typography.bodySmall)
    val d = state.differenceFromCalibration
    if (d == null) {
        Text("(nobody detected)")
    } else {
        val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        Text("            Δx      Δy      Δz", style = mono)
        Text(fmt("nose     %+.3f  %+.3f  %+.3f", d[0], d[1], d[2]), style = mono)
        Text(fmt("left_sh  %+.3f  %+.3f  %+.3f", d[3], d[4], d[5]), style = mono)
        Text(fmt("right_sh %+.3f  %+.3f  %+.3f", d[6], d[7], d[8]), style = mono)
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(millis))

private fun fmt(format: String, vararg args: Any) = String.format(Locale.US, format, *args)
