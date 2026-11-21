package com.postureguard.selftest

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Step 1 checkpoint screen (temporary): runs SelfTest in the background and shows the results. */
@Composable
fun SelfTestScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var results by remember { mutableStateOf<List<SelfTest.CheckResult>?>(null) }

    LaunchedEffect(Unit) {
        // Loading models takes a moment -> never on the main (UI) thread
        results = withContext(Dispatchers.Default) { SelfTest.runAll(context) }
    }

    Column(
        modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("PostureGuard · Step 1 self-test", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        val current = results
        if (current == null) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("Loading models…")
        } else {
            current.forEach { result ->
                Text(
                    text = (if (result.passed) "✅  " else "❌  ") + result.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (result.passed) Color(0xFF2E7D32) else Color(0xFFC62828),
                )
                Text(result.details, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
            }
            Text(
                if (current.all { it.passed }) "All checks passed: Step 1 is done."
                else "Some checks failed: copy the red text (or Logcat tag \"SelfTest\") and send it to Claude.",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
