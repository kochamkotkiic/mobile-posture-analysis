package com.postureguard.ui.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.postureguard.ui.components.Gap
import com.postureguard.ui.components.Panel
import com.postureguard.ui.components.SectionLabel
import com.postureguard.ui.components.SmallButton
import com.postureguard.ui.components.StatusDot
import com.postureguard.ui.theme.StatusColors
import com.postureguard.ui.theme.White

/**
 * Shown at the top of the monitor panel: an incoming challenge (and its countdown, recording and result),
 * and on the friend side the answer to a challenge I sent. Nothing is drawn when there is nothing to show.
 */
@Composable
fun ChallengeCard(
    state: ChallengeUiState,
    onStart: () -> Unit,
    onClose: () -> Unit,
    onDismissSentResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        state.sentResult?.let { text ->
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Your challenge")
                        Text(text, style = MaterialTheme.typography.titleMedium)
                    }
                    SmallButton("OK", onClick = onDismissSentResult)
                }
            }
            Gap(12.dp)
        }
        state.challenge?.let { challenge -> IncomingChallengeCard(challenge, state.phase, onStart, onClose) }
    }
}

@Composable
private fun IncomingChallengeCard(challenge: IncomingChallenge, phase: ChallengePhase, onStart: () -> Unit, onClose: () -> Unit) {
    Column {
        val background = when {
            phase is ChallengePhase.Result && phase.passed -> StatusColors.Good
            phase is ChallengePhase.Result -> StatusColors.Bad
            else -> MaterialTheme.colorScheme.primary
        }
        val content = if (background == MaterialTheme.colorScheme.primary) MaterialTheme.colorScheme.onPrimary else White

        Panel(Modifier.fillMaxWidth(), color = background, contentColor = content) {
            Column {
                SectionLabel("Challenge from ${challenge.fromName}", color = content.copy(alpha = 0.7f))
                Gap(6.dp)
                when (phase) {
                    ChallengePhase.Offer -> {
                        Title("Hold good posture for 5 seconds", content)
                        Body("Tap Start, sit back, and sit up straight when the countdown ends.", content)
                        Gap(14.dp)
                        Buttons {
                            InvertedButton("Start", content, background, onStart)
                            SmallGhostButton("Not now", content, onClose)
                        }
                    }
                    is ChallengePhase.Countdown -> {
                        Title("Get ready", content)
                        Text("${phase.secondsLeft}", style = MaterialTheme.typography.displayLarge, color = content)
                        Body("Sit up straight, look at the screen.", content)
                    }
                    is ChallengePhase.Recording -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(StatusColors.Bad, size = 10.dp)
                            Spacer(Modifier.width(8.dp))
                            Title("Hold it…", content)
                        }
                        Text("${phase.secondsLeft} s", style = MaterialTheme.typography.displayMedium, color = content)
                        Gap(8.dp)
                        LinearProgressIndicator(
                            progress = { phase.progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                            color = content,
                            trackColor = content.copy(alpha = 0.2f),
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    }
                    ChallengePhase.Sending -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = content, strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Title("Checking…", content)
                        }
                        Body("The cloud is judging your 5 seconds.", content)
                    }
                    is ChallengePhase.Result -> {
                        Title(if (phase.passed) "Challenge passed" else "Not quite", content)
                        Body(
                            if (phase.passed) {
                                "${phase.goodPercent}% good posture. ${challenge.fromName} has been told."
                            } else {
                                "${phase.goodPercent}% good posture, 80% needed. ${challenge.fromName} has been told."
                            },
                            content,
                        )
                        Gap(14.dp)
                        Buttons { InvertedButton("Done", content, background, onClose) }
                    }
                    is ChallengePhase.Problem -> {
                        Title("Couldn't finish", content)
                        Body(phase.message, content)
                        Gap(14.dp)
                        Buttons {
                            if (phase.canRetry) InvertedButton("Try again", content, background, onStart)
                            SmallGhostButton("Close", content, onClose)
                        }
                    }
                }
            }
        }
        Gap(12.dp)
    }
}

@Composable
private fun Title(text: String, color: Color) =
    Text(text, style = MaterialTheme.typography.headlineSmall, color = color)

@Composable
private fun Body(text: String, color: Color) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color.copy(alpha = 0.8f))

@Composable
private fun Buttons(buttons: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
}

/** Filled button in the card's text colour (white on black / white on green or red). */
@Composable
private fun InvertedButton(text: String, container: Color, textColor: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = textColor),
    ) { Text(text, style = MaterialTheme.typography.titleSmall) }
}

/** Text-only button for the secondary action. */
@Composable
private fun SmallGhostButton(text: String, content: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = content),
    ) { Text(text, style = MaterialTheme.typography.titleSmall) }
}
