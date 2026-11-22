package com.postureguard.ui.common

import android.hardware.display.DisplayManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.postureguard.camera.CameraConfig
import com.postureguard.pose.Landmark
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executor
import androidx.camera.lifecycle.awaitInstance

/**
 * Front-camera preview + frames for pose detection (same setup as the Step 2 debug screen, reusable).
 * Frames go to [analyzer] on [analysisExecutor] (a background thread). Put this in a 4:3 box.
 */
@Composable
fun CameraPreview(
    analysisExecutor: Executor,
    analyzer: ImageAnalysis.Analyzer,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER // whole 4:3 frame visible, so the dots line up
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)

    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = CameraConfig.preview().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = CameraConfig.imageAnalysis().also { it.setAnalyzer(analysisExecutor, analyzer) }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)

        // Phone turned 180° (sensorLandscape): keep the frame rotation correct
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

val NoseColor = Color(0xFFFFEB3B)          // yellow
val LeftShoulderColor = Color(0xFF00E5FF)  // cyan    = MediaPipe "left_shoulder" (11)
val RightShoulderColor = Color(0xFFFF4081) // magenta = MediaPipe "right_shoulder" (12)

/** Dots on nose and shoulders (normalized coordinates of the upright, mirrored frame). */
@Composable
fun LandmarkOverlay(nose: Landmark?, leftShoulder: Landmark?, rightShoulder: Landmark?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (leftShoulder != null && rightShoulder != null) {
            drawLine(
                Color.White,
                Offset(leftShoulder.x * size.width, leftShoulder.y * size.height),
                Offset(rightShoulder.x * size.width, rightShoulder.y * size.height),
                strokeWidth = 3.dp.toPx(),
            )
        }
        listOf(nose to NoseColor, leftShoulder to LeftShoulderColor, rightShoulder to RightShoulderColor)
            .forEach { (lm, color) ->
                if (lm != null) drawCircle(color, radius = 10.dp.toPx(), center = Offset(lm.x * size.width, lm.y * size.height))
            }
    }
}
