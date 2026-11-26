package com.postureguard.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.postureguard.ui.components.Gap
import com.postureguard.ui.components.NumberedStep
import com.postureguard.ui.components.PrimaryButton
import com.postureguard.ui.theme.Black
import com.postureguard.ui.theme.Grey500
import com.postureguard.ui.theme.White

/** First launch (no calibration yet): what the app does and how to set the phone up. */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxSize()) {
        // Left: black brand panel (stays black in dark mode too)
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Black)
                .padding(horizontal = 40.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("PostureGuard", style = MaterialTheme.typography.titleLarge, color = White)
            Column {
                Text("Sit better,\nwithout thinking\nabout it.", style = MaterialTheme.typography.displayMedium, color = White)
                Gap(16.dp)
                Text(
                    "Your phone watches your posture while you study and tells you when you slouch.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Grey500,
                )
            }
            Text("The camera image never leaves this phone.", style = MaterialTheme.typography.bodySmall, color = Grey500)
        }

        // Right: setup steps + the one action
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 40.dp, vertical = 32.dp),
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Set up your desk", style = MaterialTheme.typography.headlineMedium)
                Gap(24.dp)
                NumberedStep(1, "Stand the phone in landscape", "At the far end of your desk, facing you. A stand works best.")
                Gap(18.dp)
                NumberedStep(2, "Lens at about chin height", "Raise it on a few books if needed. Keep it plugged in.")
                Gap(18.dp)
                NumberedStep(3, "Light in front of you", "No bright window behind you, head and shoulders in view.")
            }
            Gap(16.dp)
            PrimaryButton("Get started", onClick = onGetStarted)
        }
    }
}
