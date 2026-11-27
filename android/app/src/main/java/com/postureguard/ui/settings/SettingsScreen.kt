package com.postureguard.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.postureguard.remote.ClassifierMode
import com.postureguard.ui.components.ChoiceRow
import com.postureguard.ui.components.Gap
import com.postureguard.ui.components.SecondaryButton
import com.postureguard.ui.components.SectionLabel
import com.postureguard.ui.components.TextLink
import com.postureguard.ui.formatCalibrationTime

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onClose: () -> Unit,
    onRecalibrate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val state by viewModel.state.collectAsState()
    val settings = state.settings

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                TextLink("←  Back", onClick = onClose, modifier = Modifier.offset(x = (-8).dp))
                Gap(8.dp)
                Text("Settings", style = MaterialTheme.typography.headlineLarge)
                Gap(24.dp)

                // --- Calibration -------------------------------------------------------------------
                SectionLabel("Calibration")
                Gap(8.dp)
                SettingRow(
                    title = "Your sitting position",
                    detail = state.calibratedAtMillis?.let { "Calibrated ${formatCalibrationTime(it)} · ${state.calibrationFrames} frames" }
                        ?: "Not calibrated yet",
                ) {
                    SecondaryButton("Recalibrate", onClick = onRecalibrate, modifier = Modifier)
                }
                Text(
                    "Recalibrate whenever you move the phone or your chair.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionDivider()

                // --- Classification ----------------------------------------------------------------
                SectionLabel("Classification")
                Gap(4.dp)
                Text("Where your posture is judged", style = MaterialTheme.typography.titleMedium)
                Gap(12.dp)
                ChoiceRow(
                    options = listOf(ClassifierMode.ON_DEVICE to "On this phone", ClassifierMode.CLOUD to "Cloud"),
                    selected = if (settings.classifierMode == ClassifierMode.EDGE) ClassifierMode.CLOUD else settings.classifierMode,
                    onSelect = viewModel::setClassifierMode,
                )
                Gap(8.dp)
                Text(
                    if (settings.classifierMode == ClassifierMode.ON_DEVICE) {
                        "Fastest and works offline. Recommended."
                    } else {
                        "Sends only 9 numbers per check to the server, never images. " +
                            "Cloud mode is not connected yet: the phone still judges on-device."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(16.dp)
                ServerUrlField(settings.serverUrl, viewModel::setServerUrl)
                SectionDivider()

                // --- Developer ---------------------------------------------------------------------
                SectionLabel("Developer")
                Gap(8.dp)
                SettingRow(
                    title = "Developer tools",
                    detail = "Landmark dots, FPS and model timing, 30 s test alert, guided test and session recording.",
                ) {
                    Switch(checked = settings.developerTools, onCheckedChange = viewModel::setDeveloperTools)
                }
                SectionDivider()

                // --- Privacy -----------------------------------------------------------------------
                SectionLabel("Privacy")
                Gap(8.dp)
                Text(
                    "The camera image never leaves this phone. Pose detection always runs on the phone; " +
                        "in cloud mode only the 9 posture numbers are sent.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(24.dp)
            }
        }
    }
}

/** Title + detail on the left, a control (button / switch) on the right. */
@Composable
private fun SettingRow(title: String, detail: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
}

/** Edits locally and saves on "Done" or when the screen closes (not on every key press). */
@Composable
private fun ServerUrlField(savedUrl: String, onSave: (String) -> Unit) {
    var edited by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val latestEdited by rememberUpdatedState(edited)
    val latestSaved by rememberUpdatedState(savedUrl)
    val latestOnSave by rememberUpdatedState(onSave)

    fun save() {
        val value = latestEdited ?: return
        if (value.isNotBlank() && value.trim() != latestSaved) latestOnSave(value)
    }
    DisposableEffect(Unit) { onDispose { save() } }

    OutlinedTextField(
        value = edited ?: savedUrl,
        onValueChange = { edited = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Server address") },
        supportingText = { Text("Local server: http://10.0.2.2:8000, or http://localhost:8000 after \"adb reverse tcp:8000 tcp:8000\" · cloud: Nuno's https URL") },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            save()
            focusManager.clearFocus()
        }),
    )
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.padding(top = 20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.padding(top = 20.dp))
}
