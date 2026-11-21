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
import com.postureguard.ui.posedebug.PoseDebugScreen
import com.postureguard.ui.posedebug.PoseDebugViewModel
import com.postureguard.ui.theme.PostureGuardTheme

class MainActivity : ComponentActivity() {

    // Survives configuration changes; released (camera thread + MediaPipe) when the Activity finishes
    private val poseDebugViewModel: PoseDebugViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The phone stands on the desk and watches the user: the screen must not turn off
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PostureGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Step 2: camera + pose debug screen. The Step 1 self-test is still in selftest/
                    // (SelfTestScreen) if you need to run it again.
                    PoseDebugScreen(
                        viewModel = poseDebugViewModel,
                        lifecycleOwner = this,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
