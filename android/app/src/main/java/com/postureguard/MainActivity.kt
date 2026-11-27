package com.postureguard

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.postureguard.posture.PostureEvent
import com.postureguard.ui.calibration.CalibrationScreen
import com.postureguard.ui.friends.ChallengeCard
import com.postureguard.ui.friends.ChallengeViewModel
import com.postureguard.ui.friends.FriendsScreen
import com.postureguard.ui.friends.FriendsViewModel
import kotlinx.coroutines.launch
import com.postureguard.ui.calibration.CalibrationViewModel
import com.postureguard.ui.monitor.MonitorScreen
import com.postureguard.ui.monitor.MonitorViewModel
import com.postureguard.ui.settings.SettingsScreen
import com.postureguard.ui.settings.SettingsViewModel
import com.postureguard.ui.theme.PostureGuardTheme
import com.postureguard.ui.welcome.WelcomeScreen

/** Top-level screens. Settings is a layer over the monitor (the camera and timers keep running). */
private enum class Screen { LOADING, WELCOME, CALIBRATION, MONITOR }

class MainActivity : ComponentActivity() {

    // Survive configuration changes; release the camera threads + MediaPipe when the Activity finishes
    private val calibrationViewModel: CalibrationViewModel by viewModels()
    private val monitorViewModel: MonitorViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val friendsViewModel: FriendsViewModel by viewModels()
    private val challengeViewModel: ChallengeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The phone stands on the desk and watches the user: the screen must not turn off
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 10 min of continuous bad posture -> tell the cloud, which notifies the favorite friends (D6 / D9)
        lifecycleScope.launch {
            monitorViewModel.events.collect { event ->
                if (event == PostureEvent.LongBadPostureAlert) friendsViewModel.reportAlert()
            }
        }
        // Friend challenge: the 5 s recording reads the monitor's frames (main thread, one consumer)
        lifecycleScope.launch {
            monitorViewModel.challengeFrames.collect { challengeViewModel.onFrame(it) }
        }

        setContent {
            PostureGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
                    val screenModifier = Modifier.padding(innerPadding)
                    val hasCalibration by monitorViewModel.hasCalibration.collectAsState()
                    val settings by settingsViewModel.state.collectAsState()
                    val developerTools = settings.settings.developerTools
                    var screen by rememberSaveable { mutableStateOf(Screen.LOADING) }
                    var settingsOpen by rememberSaveable { mutableStateOf(false) }
                    var friendsOpen by rememberSaveable { mutableStateOf(false) }

                    // First screen: monitoring if a calibration is saved, otherwise the welcome screen
                    LaunchedEffect(hasCalibration) {
                        val saved = hasCalibration
                        if (screen == Screen.LOADING && saved != null) screen = if (saved) Screen.MONITOR else Screen.WELCOME
                    }

                    fun openCalibration() {
                        calibrationViewModel.recalibrate()
                        settingsOpen = false
                        friendsOpen = false
                        screen = Screen.CALIBRATION
                    }

                    Crossfade(targetState = screen, label = "screen") { current ->
                        when (current) {
                            Screen.LOADING -> Box(screenModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                            Screen.WELCOME -> WelcomeScreen(
                                onGetStarted = { screen = Screen.CALIBRATION },
                                modifier = screenModifier,
                            )
                            Screen.CALIBRATION -> {
                                val canGoBack = hasCalibration == true
                                BackHandler(enabled = canGoBack) { screen = Screen.MONITOR }
                                CalibrationScreen(
                                    viewModel = calibrationViewModel,
                                    lifecycleOwner = this@MainActivity,
                                    developerTools = developerTools,
                                    onStartMonitoring = { screen = Screen.MONITOR },
                                    onBack = if (canGoBack) ({ screen = Screen.MONITOR }) else null,
                                    modifier = screenModifier,
                                )
                            }
                            Screen.MONITOR -> Box(Modifier.fillMaxSize()) {
                                MonitorScreen(
                                    viewModel = monitorViewModel,
                                    lifecycleOwner = this@MainActivity,
                                    developerTools = developerTools,
                                    onRecalibrate = { openCalibration() },
                                    onOpenSettings = { settingsOpen = true },
                                    onOpenFriends = { friendsOpen = true },
                                    modifier = screenModifier,
                                    topContent = {
                                        val challenge by challengeViewModel.state.collectAsState()
                                        ChallengeCard(
                                            state = challenge,
                                            onStart = challengeViewModel::start,
                                            onClose = challengeViewModel::close,
                                            onDismissSentResult = challengeViewModel::dismissSentResult,
                                        )
                                    },
                                )
                                OverlayLayer(visible = friendsOpen) {
                                    FriendsScreen(
                                        viewModel = friendsViewModel,
                                        onClose = { friendsOpen = false },
                                        modifier = screenModifier,
                                    )
                                }
                                OverlayLayer(visible = settingsOpen) {
                                    SettingsScreen(
                                        viewModel = settingsViewModel,
                                        onClose = { settingsOpen = false },
                                        onRecalibrate = { openCalibration() },
                                        modifier = screenModifier,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Full-screen layer that slides in over the monitor (which keeps running underneath). */
@Composable
private fun OverlayLayer(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        // Opaque background under the system bars too; the screen itself applies the padding
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
    }
}
