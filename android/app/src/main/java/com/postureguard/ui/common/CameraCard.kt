package com.postureguard.ui.common

import androidx.camera.core.ImageAnalysis
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import com.postureguard.pose.Landmark
import java.util.concurrent.Executor

/**
 * The camera preview as a rounded card (the "map" of the app), full height, 4:3.
 * [accent] draws a coloured frame around it (e.g. red while the "Bad posture" sign is shown).
 * [overlay] is drawn on top, e.g. a small status chip.
 */
@Composable
fun CameraCard(
    analysisExecutor: Executor,
    analyzer: ImageAnalysis.Analyzer,
    lifecycleOwner: LifecycleOwner,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    landmarks: Triple<Landmark?, Landmark?, Landmark?>? = null,
    accent: Color = Color.Transparent,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val aspect = if (imageWidth > 0 && imageHeight > 0) imageWidth.toFloat() / imageHeight else 4f / 3f
    val shape = MaterialTheme.shapes.extraLarge
    val border by animateColorAsState(accent, label = "camera-accent")

    Box(
        modifier
            .fillMaxHeight()
            .aspectRatio(aspect, matchHeightConstraintsFirst = true)
            .clip(shape)
            .background(Color.Black)
            .border(4.dp, border, shape),
    ) {
        CameraPreview(analysisExecutor, analyzer, lifecycleOwner, Modifier.fillMaxSize())
        landmarks?.let { (nose, left, right) -> LandmarkOverlay(nose, left, right, Modifier.fillMaxSize()) }
        overlay()
    }
}
