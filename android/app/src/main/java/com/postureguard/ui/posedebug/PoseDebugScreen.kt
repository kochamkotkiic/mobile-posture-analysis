package com.postureguard.ui.posedebug

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.postureguard.camera.CameraConfig
import com.postureguard.pose.Landmark
import kotlinx.coroutines.awaitCancellation
import androidx.camera.lifecycle.awaitInstance
import java.util.Locale

private val NoseColor = Color(0xFFFFEB3B)          // yellow
private val LeftShoulderColor = Color(0xFF00E5FF)  // cyan    = MediaPipe "left_shoulder" (11)
private val RightShoulderColor = Color(0xFFFF4081) // magenta = MediaPipe "right_shoulder" (12)

/** Step 2 checkpoint: camera preview + detected nose/shoulders + live numbers. */
@Composable
fun PoseDebugScreen(viewModel: PoseDebugViewModel, lifecycleOwner: LifecycleOwner, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasCameraPermission) {
        Column(modifier.padding(24.dp)) {
            Text("PostureGuard needs the camera to see your posture.", style = MaterialTheme.typography.titleMedium)
            Text("Images stay on this phone and are never uploaded.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        }
        return
    }

    val state by viewModel.state.collectAsState()
    val aspect = if (state.imageWidth > 0 && state.imageHeight > 0) {
        state.imageWidth.toFloat() / state.imageHeight
    } else 4f / 3f

    Row(modifier.fillMaxSize().padding(12.dp)) {
        Box(Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true)) {
            CameraPreview(viewModel, lifecycleOwner, Modifier.fillMaxSize())
            LandmarkOverlay(state, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(16.dp))
        StatsPanel(state, Modifier.verticalScroll(rememberScrollState()))
    }
}

@Composable
private fun CameraPreview(viewModel: PoseDebugViewModel, lifecycleOwner: LifecycleOwner, modifier: Modifier) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER // show the whole 4:3 frame, so the dots line up
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)

    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = CameraConfig.preview().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = CameraConfig.imageAnalysis().also { it.setAnalyzer(viewModel.analysisExecutor, viewModel.analyzer) }
        provider.unbindAll()
        // Front camera; CameraX starts/stops it together with the Activity lifecycle
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)

        // The phone may be turned 180° (sensorLandscape): keep the frame rotation correct, otherwise
        // MediaPipe would get upside-down images.
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val display = previewView.display ?: return
                if (display.displayId == displayId) {
                    analysis.targetRotation = display.rotation
                    preview.targetRotation = display.rotation
                }
            }
        }
        displayManager.registerDisplayListener(listener, null)
        try {
            awaitCancellation()
        } finally {
            displayManager.unregisterDisplayListener(listener)
        }
    }
}

@Composable
private fun LandmarkOverlay(state: PoseDebugState, modifier: Modifier) {
    Canvas(modifier) {
        val left = state.leftShoulder
        val right = state.rightShoulder
        if (left != null && right != null) {
            drawLine(
                Color.White,
                Offset(left.x * size.width, left.y * size.height),
                Offset(right.x * size.width, right.y * size.height),
                strokeWidth = 3.dp.toPx(),
            )
        }
        listOf(state.nose to NoseColor, left to LeftShoulderColor, right to RightShoulderColor).forEach { (lm, color) ->
            if (lm != null) {
                drawCircle(color, radius = 10.dp.toPx(), center = Offset(lm.x * size.width, lm.y * size.height))
            }
        }
    }
}

@Composable
private fun StatsPanel(state: PoseDebugState, modifier: Modifier) {
    Column(modifier) {
        Text("Step 2 · camera + pose", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        state.error?.let { Text("Error: $it", color = Color(0xFFC62828)) }
        Text(
            if (state.personVisible) "Person: visible" else "Person: not detected",
            color = if (state.personVisible) Color(0xFF2E7D32) else Color(0xFFC62828),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        LandmarkRow("nose", state.nose, NoseColor)
        LandmarkRow("left_shoulder", state.leftShoulder, LeftShoulderColor)
        LandmarkRow("right_shoulder", state.rightShoulder, RightShoulderColor)
        Spacer(Modifier.height(8.dp))

        // Same relations as in the Python reference (testp6, upright + mirrored)
        val nose = state.nose
        val left = state.leftShoulder
        val right = state.rightShoulder
        if (nose != null && left != null && right != null) {
            Check("Nose above shoulders (nose y < shoulder y)", nose.y < minOf(left.y, right.y))
            // MediaPipe names the shoulder on the image's right side "left" whether or not the image is mirrored,
            // so left/right cannot prove mirroring. Mirroring is checked by moving (see text below).
            Check("Shoulders level (horizontal line, not rotated 90°)", kotlin.math.abs(left.y - right.y) < 0.15f)
            Text(
                "Mirror test: lean to YOUR left -> you move left on screen and nose x gets SMALLER.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(fmt("Image: %d x %d", state.imageWidth, state.imageHeight))
        Text(fmt("Pose FPS: %.1f", state.poseFps))
        Text(fmt("Pose detection: %.1f ms", state.detectionMs))
        Text(fmt("Rotate + mirror: %.1f ms", state.preprocessMs))
    }
}

@Composable
private fun LandmarkRow(name: String, landmark: Landmark?, color: Color) {
    Text(
        if (landmark == null) "● $name: -"
        else fmt("● %s: x=%.3f  y=%.3f  z=%.3f  vis=%.2f", name, landmark.x, landmark.y, landmark.z, landmark.visibility),
        color = color,
    )
}

@Composable
private fun Check(text: String, ok: Boolean) {
    Text((if (ok) "✅ " else "❌ ") + text, color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828))
}

private fun fmt(format: String, vararg args: Any) = String.format(Locale.US, format, *args)
