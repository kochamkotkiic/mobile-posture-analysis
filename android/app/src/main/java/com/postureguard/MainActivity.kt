package com.postureguard

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.postureguard.selftest.SelfTestScreen
import com.postureguard.ui.theme.PostureGuardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The phone stands on the desk and watches the user: the screen must not turn off
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PostureGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Step 1: temporary self-test. Replaced by CalibrationScreen / MonitorScreen later.
                    SelfTestScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}
