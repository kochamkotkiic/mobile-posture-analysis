package com.postureguard

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.postureguard.ui.calibration.CalibrationScreen
import com.postureguard.ui.calibration.CalibrationViewModel
import com.postureguard.ui.theme.PostureGuardTheme

class MainActivity : ComponentActivity() {

    // Survives configuration changes; releases the camera thread + MediaPipe when the Activity finishes
    private val calibrationViewModel: CalibrationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The phone stands on the desk and watches the user: the screen must not turn off
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PostureGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Step 4: calibration. Step 5 adds monitoring once a calibration exists.
                    // Earlier checkpoints are still in the code: selftest/ (Step 1), ui/posedebug/ (Step 2).
                    CalibrationScreen(
                        viewModel = calibrationViewModel,
                        lifecycleOwner = this,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
