package com.postureguard

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.postureguard.ui.calibration.CalibrationScreen
import com.postureguard.ui.calibration.CalibrationViewModel
import com.postureguard.ui.monitor.MonitorScreen
import com.postureguard.ui.monitor.MonitorViewModel
import com.postureguard.ui.theme.PostureGuardTheme

private const val SCREEN_CALIBRATION = "calibration"
private const val SCREEN_MONITOR = "monitor"

class MainActivity : ComponentActivity() {

    // Survive configuration changes; release the camera threads + MediaPipe when the Activity finishes
    private val calibrationViewModel: CalibrationViewModel by viewModels()
    private val monitorViewModel: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The phone stands on the desk and watches the user: the screen must not turn off
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PostureGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val screenModifier = Modifier.padding(innerPadding)
                    val hasCalibration by monitorViewModel.hasCalibration.collectAsState()
                    var screen by rememberSaveable { mutableStateOf<String?>(null) }

                    // First screen: monitoring if a calibration is saved, otherwise calibration
                    LaunchedEffect(hasCalibration) {
                        val saved = hasCalibration
                        if (screen == null && saved != null) screen = if (saved) SCREEN_MONITOR else SCREEN_CALIBRATION
                    }

                    when (screen) {
                        null -> Box(screenModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        SCREEN_CALIBRATION -> CalibrationScreen(
                            viewModel = calibrationViewModel,
                            lifecycleOwner = this,
                            onStartMonitoring = { screen = SCREEN_MONITOR },
                            modifier = screenModifier,
                        )
                        else -> MonitorScreen(
                            viewModel = monitorViewModel,
                            lifecycleOwner = this,
                            onRecalibrate = {
                                calibrationViewModel.recalibrate()
                                screen = SCREEN_CALIBRATION
                            },
                            modifier = screenModifier,
                        )
                    }
                }
            }
        }
    }
}
