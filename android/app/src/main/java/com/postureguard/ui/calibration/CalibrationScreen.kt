package com.postureguard.ui.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import com.postureguard.calibration.CalibrationCollector
import com.postureguard.calibration.SavedCalibration
import com.postureguard.ui.common.CameraCard
import com.postureguard.ui.common.RequireCameraPermission
import com.postureguard.ui.components.Gap
import com.postureguard.ui.components.NumberedStep
import com.postureguard.ui.components.Panel
import com.postureguard.ui.components.PrimaryButton
import com.postureguard.ui.components.SecondaryButton
import com.postureguard.ui.components.SectionLabel
import com.postureguard.ui.components.StatusDot
import com.postureguard.ui.components.TextLink
import com.postureguard.ui.formatCalibrationTime
import com.postureguard.ui.theme.Black
import com.postureguard.ui.theme.Grey500
import com.postureguard.ui.theme.StatusColors
import com.postureguard.ui.theme.White
import java.util.Locale
import kotlin.math.ceil

/**
 * Step 4 calibration: the user sits straight for 5 s, the app remembers that position.
 * [onBack] is null when there is no calibration yet (nothing to go back to).
 */
@Composable
fun CalibrationScreen(
    viewModel: CalibrationViewModel,
    lifecycleOwner: LifecycleOwner,
    developerTools: Boolean,
    onStartMonitoring: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) = RequireCameraPermission(modifier) {
    val state by viewModel.state.collectAsState()
    val phase = state.phase

    Row(modifier.fillMaxSize().padding(16.dp)) {
        CameraCard(
            analysisExecutor = viewModel.analysisExecutor,
            analyzer = viewModel.analyzer,
            lifecycleOwner = lifecycleOwner,
            imageWidth = state.imageWidth,
            imageHeight = state.imageHeight,
            landmarks = Triple(state.nose, state.leftShoulder, state.rightShoulder),
            accent = when (phase) {
                is CalibrationPhase.Collecting -> StatusColors.Good
                else -> Color.Transparent
            },
        ) {
            if (phase is CalibrationPhase.Countdown) CountdownOverlay(phase.secondsLeft)
            DetectionChip(state, Modifier.align(Alignment.TopStart).padding(14.dp))
        }
        Spacer(Modifier.width(20.dp))

        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (onBack != null && (phase is CalibrationPhase.Ready || phase is CalibrationPhase.Calibrated)) {
                TextLink("←  Back", onClick = onBack)
                Gap(4.dp)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when (phase) {
                    CalibrationPhase.Loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    is CalibrationPhase.Ready -> ReadyContent(state)
                    is CalibrationPhase.Countdown -> CountdownContent()
                    is CalibrationPhase.Collecting -> CollectingContent(phase)
                    is CalibrationPhase.Calibrated -> CalibratedContent(state, phase.saved, developerTools)
                }
                // Success is shown by the "You're all set" content; only show problems here
                state.message?.takeUnless { it.startsWith("Calibration saved") }?.let {
                    Gap(16.dp)
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = StatusColors.Bad)
                }
            }
            Gap(12.dp)
            when (phase) {
                is CalibrationPhase.Ready -> {
                    PrimaryButton("Start", onClick = viewModel::startCalibration, enabled = state.canStart)
                    if (phase.current != null) {
                        Gap(8.dp)
                        SecondaryButton("Keep current calibration", onClick = viewModel::keepCurrent)
                    }
                }
                is CalibrationPhase.Calibrated -> {
                    PrimaryButton("Start monitoring", onClick = onStartMonitoring)
                    Gap(8.dp)
                    SecondaryButton("Calibrate again", onClick = viewModel::recalibrate)
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ReadyContent(state: CalibrationUiState) {
    Text("Calibrate", style = MaterialTheme.typography.headlineLarge)
    Gap(4.dp)
    Text(
        "Show the app what sitting straight looks like for you.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Gap(20.dp)
    NumberedStep(1, "Place the phone", "Where it will stay, lens at about chin height.")
    Gap(14.dp)
    NumberedStep(2, "Sit up straight", "Both shoulders in the picture, look at the screen.")
    Gap(14.dp)
    NumberedStep(3, "Press Start and hold still", "3 s to get back into position, then 5 s of stillness.")
    if (!state.canStart) {
        Gap(16.dp)
        Text(
            "Start unlocks when both shoulders are clearly visible.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    (state.phase as? CalibrationPhase.Ready)?.current?.let {
        Gap(16.dp)
        Text(
            "Current calibration: ${formatCalibrationTime(it.calibratedAtMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CountdownContent() {
    Text("Get into position", style = MaterialTheme.typography.headlineLarge)
    Gap(4.dp)
    Text(
        "Sit up straight and look at the screen. Recording starts after the countdown.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CollectingContent(phase: CalibrationPhase.Collecting) {
    val secondsLeft = ceil((1f - phase.progress) * CalibrationCollector.DURATION_MS / 1000f).toInt()
    Text("Hold still", style = MaterialTheme.typography.headlineLarge)
    Gap(4.dp)
    Text("$secondsLeft s", style = MaterialTheme.typography.displayMedium)
    Gap(16.dp)
    LinearProgressIndicator(
        progress = { phase.progress },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.secondaryContainer,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
    Gap(8.dp)
    Text(
        "${phase.samples} good frames (at least ${CalibrationCollector.MIN_SAMPLES} needed)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CalibratedContent(state: CalibrationUiState, saved: SavedCalibration, developerTools: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(StatusColors.Good)
        Spacer(Modifier.width(8.dp))
        SectionLabel("Calibrated")
    }
    Gap(8.dp)
    Text("You're all set", style = MaterialTheme.typography.headlineLarge)
    Gap(4.dp)
    Text(
        "Calibrated ${formatCalibrationTime(saved.calibratedAtMillis)}. Recalibrate whenever you move the phone or your chair.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (developerTools) {
        Gap(16.dp)
        DifferencePanel(state.differenceFromCalibration, saved.sampleCount)
    }
}

/** Developer check: live features minus calibration (all close to 0 when sitting as during calibration). */
@Composable
private fun DifferencePanel(d: FloatArray?, frames: Int) {
    Panel(Modifier.fillMaxWidth()) {
        Column {
            SectionLabel("Difference from calibration · $frames frames")
            Gap(8.dp)
            if (d == null) {
                Text("Nobody detected", style = MaterialTheme.typography.bodySmall)
            } else {
                val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                Text("            Δx      Δy      Δz", style = mono)
                Text(fmt("nose     %+.3f  %+.3f  %+.3f", d[0], d[1], d[2]), style = mono)
                Text(fmt("left_sh  %+.3f  %+.3f  %+.3f", d[3], d[4], d[5]), style = mono)
                Text(fmt("right_sh %+.3f  %+.3f  %+.3f", d[6], d[7], d[8]), style = mono)
            }
        }
    }
}

/** Pill on the camera: is the user detected well enough? (> 0.7 excellent, as in the desktop app) */
@Composable
private fun DetectionChip(state: CalibrationUiState, modifier: Modifier = Modifier) {
    val (text, dot) = when {
        !state.personVisible -> "No one in view" to Grey500
        state.shoulderVisibility > 0.7f -> "You're in view" to StatusColors.Good
        else -> "Show both shoulders" to StatusColors.Warning
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        StatusDot(dot, size = 8.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = White)
    }
}

/** Huge countdown number over the camera, readable from the far end of the desk. */
@Composable
private fun CountdownOverlay(secondsLeft: Int) {
    Box(Modifier.fillMaxSize().background(Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Text(
            "$secondsLeft",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = MaterialTheme.typography.displayLarge.fontSize * 2.5f),
            color = White,
        )
    }
}

private fun fmt(format: String, vararg args: Any) = String.format(Locale.US, format, *args)
