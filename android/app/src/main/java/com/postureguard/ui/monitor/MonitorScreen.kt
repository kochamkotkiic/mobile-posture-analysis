package com.postureguard.ui.monitor

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import com.postureguard.posture.Prediction
import com.postureguard.ui.common.CameraCard
import com.postureguard.ui.common.RequireCameraPermission
import com.postureguard.ui.components.Gap
import com.postureguard.ui.components.Panel
import com.postureguard.ui.components.PrimaryButton
import com.postureguard.ui.components.SecondaryButton
import com.postureguard.ui.components.SectionLabel
import com.postureguard.ui.components.StatTile
import com.postureguard.ui.components.StatusDot
import com.postureguard.ui.components.TextLink
import com.postureguard.ui.formatClock
import com.postureguard.ui.formatShortDuration
import com.postureguard.ui.theme.Black
import com.postureguard.ui.theme.Grey500
import com.postureguard.ui.theme.Grey700
import com.postureguard.ui.theme.StatusColors
import com.postureguard.ui.theme.White
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Home screen while the phone watches the user: camera on the left, a calm status panel on the right.
 * Developer tools (FPS, test mode, Step 6 recording) only when switched on in Settings.
 */
@Composable
fun MonitorScreen(
    viewModel: MonitorViewModel,
    lifecycleOwner: LifecycleOwner,
    developerTools: Boolean,
    onRecalibrate: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFriends: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { viewModel.onScreenShown() }

    RequireCameraPermission(modifier) {
        val state by viewModel.state.collectAsState()
        val alertVisible by viewModel.alertVisible.collectAsState()
        val look = statusLook(state)

        Row(modifier.fillMaxSize().padding(16.dp)) {
            CameraCard(
                analysisExecutor = viewModel.analysisExecutor,
                analyzer = viewModel.analyzer,
                lifecycleOwner = lifecycleOwner,
                imageWidth = state.imageWidth,
                imageHeight = state.imageHeight,
                landmarks = if (developerTools) Triple(state.nose, state.leftShoulder, state.rightShoulder) else null,
                accent = if (look.isBad) StatusColors.Bad else Color.Transparent,
            ) {
                LiveChip(look.dot, Modifier.align(Alignment.TopStart).padding(14.dp))
            }
            Spacer(Modifier.width(20.dp))

            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("PostureGuard", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextLink("Friends", onClick = onOpenFriends)
                    TextLink("Settings", onClick = onOpenSettings)
                }
                Gap(12.dp)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    state.recording?.let {
                        RecordingCard(it)
                        Gap(12.dp)
                    }
                    StatusPanel(look)
                    Gap(12.dp)
                    SessionTiles(state)
                    if (developerTools) {
                        Gap(12.dp)
                        DeveloperPanel(state, viewModel)
                    }
                }
                Gap(12.dp)
                SecondaryButton("Recalibrate", onClick = onRecalibrate)
            }
        }

        if (alertVisible) BreakDialog(testMode = state.testMode, onDismiss = viewModel::dismissAlert)
    }
}

// --- Status ------------------------------------------------------------------------------------------

/** Everything that changes with the posture status: colours and texts. */
private data class StatusLook(
    val label: String,
    val title: String,
    val detail: String?,
    val dot: Color,
    val background: Color?, // null = default panel colour
    val content: Color?,
    val isBad: Boolean = false,
)

private fun statusLook(state: MonitorUiState): StatusLook {
    val posture = state.posture
    return when {
        state.error != null -> StatusLook("Problem", "Something went wrong", state.error, StatusColors.Bad, null, null)
        !state.modelReady || posture == null ->
            StatusLook("Starting", "Getting ready…", "Starting the camera and the posture model.", Grey500, null, null)
        !posture.personVisible ->
            StatusLook("Paused", "No one in view", "Sit in front of the phone so your head and shoulders are visible.", Grey500, null, null)
        posture.badPostureShown -> StatusLook(
            "Bad posture", "Sit up straight",
            "Slouching for ${formatClock(posture.badSeconds.toLong())}",
            White, StatusColors.Bad, White, isBad = true,
        )
        else -> StatusLook("Posture", "Looking good", "You'll see it here if you start to slouch.", StatusColors.Good, null, null)
    }
}

@Composable
private fun StatusPanel(look: StatusLook) {
    val background by animateColorAsState(look.background ?: MaterialTheme.colorScheme.surfaceVariant, label = "status-bg")
    val content by animateColorAsState(look.content ?: MaterialTheme.colorScheme.onSurface, label = "status-fg")
    Panel(Modifier.fillMaxWidth(), color = background, contentColor = content) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(look.dot)
                Spacer(Modifier.width(8.dp))
                SectionLabel(look.label, color = content.copy(alpha = 0.75f))
            }
            Gap(8.dp)
            Text(look.title, style = MaterialTheme.typography.headlineLarge, color = content)
            look.detail?.let {
                Gap(4.dp)
                Text(it, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.75f))
            }
        }
    }
}

/** Small "● Live" pill on the camera; the dot has the status colour. */
@Composable
private fun LiveChip(dot: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(dot, size = 8.dp)
        Spacer(Modifier.width(6.dp))
        Text("Live", style = MaterialTheme.typography.labelMedium, color = White)
    }
}

@Composable
private fun SessionTiles(state: MonitorUiState) {
    val session = state.session
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(formatShortDuration(session.trackedMs / 1000), "Tracked")
        StatTile(session.goodFraction?.let { "${(it * 100).roundToInt()}%" } ?: "–", "Good posture")
        StatTile("${session.alerts}", if (session.alerts == 1) "Break" else "Breaks")
    }
}

@Composable
private fun BreakDialog(testMode: Boolean, onDismiss: () -> Unit) {
    val time = if (testMode) "30 seconds (test mode)" else "10 minutes"
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Time for a break", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Text(
                "You've been slouching for $time. Stand up, stretch for a minute, then sit back up straight.",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = { PrimaryButton("Got it", onClick = onDismiss, modifier = Modifier) },
    )
}

// --- Developer tools (Step 5/6 test UI, kept for the evaluation) ---------------------------------------

@Composable
private fun DeveloperPanel(state: MonitorUiState, viewModel: MonitorViewModel) {
    Panel(Modifier.fillMaxWidth()) {
        Column {
            SectionLabel("Developer")
            Gap(8.dp)
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                fmt("Pose %.1f FPS · model %.1f×/s · %.2f ms", state.poseFps, state.classificationsPerSecond, state.classifyMs),
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
            state.posture?.let {
                Text(
                    fmt("Smoothed: %s · bad for %.1f s", if (it.smoothedLabel == Prediction.BAD) "bad" else "good", it.badSeconds),
                    style = MaterialTheme.typography.bodySmall, color = muted,
                )
            }
            state.lastPrediction?.let {
                Text(
                    fmt("Last model answer: %s (%.0f%%)", if (it.label == Prediction.BAD) "bad" else "good", it.confidence * 100),
                    style = MaterialTheme.typography.bodySmall, color = muted,
                )
            }
            Gap(8.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Test mode", style = MaterialTheme.typography.titleSmall)
                    Text("Break reminder after 30 s instead of 10 min", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Switch(checked = state.testMode, onCheckedChange = viewModel::setTestMode)
            }
            Gap(12.dp)
            if (state.recording == null) {
                SecondaryButton("Start guided test (5 min)", onClick = viewModel::startGuidedTest)
                Gap(8.dp)
                SecondaryButton("Record session (battery / heat)", onClick = viewModel::startFreeRecording)
            } else {
                SecondaryButton("Stop recording", onClick = viewModel::stopRecording)
            }
            state.lastSessionMessage?.let {
                Gap(8.dp)
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

/** Big instruction card during the guided test (the user follows it from the desk, the app knows the truth). */
@Composable
private fun RecordingCard(recording: RecordingUi) {
    if (!recording.guided) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(StatusColors.Bad)
            Spacer(Modifier.width(8.dp))
            Text(
                fmt("Recording %s · %,d frames", formatClock(recording.elapsedSeconds.toLong()), recording.frames),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        return
    }
    val (title, background) = when (recording.stepKind) {
        "good" -> "Sit straight" to StatusColors.Good
        "mild" -> "Slouch slightly" to StatusColors.Warning
        "strong" -> "Slouch strongly" to StatusColors.Bad
        else -> "Get ready" to Grey700
    }
    val content = if (background == StatusColors.Warning) Black else White
    Panel(Modifier.fillMaxWidth(), color = background, contentColor = content) {
        Column {
            SectionLabel(
                "Guided test · step ${recording.stepNumber}/${recording.stepCount} · ${recording.secondsLeftInStep} s left",
                color = content.copy(alpha = 0.8f),
            )
            Gap(4.dp)
            Text(title, style = MaterialTheme.typography.headlineLarge, color = content)
            Text(recording.instruction, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.85f))
        }
    }
}

private fun fmt(format: String, vararg args: Any) = String.format(Locale.US, format, *args)
