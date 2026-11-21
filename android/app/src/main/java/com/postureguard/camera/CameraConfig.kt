package com.postureguard.camera

import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/**
 * CameraX use cases. Both are 4:3 (like the 640x480 webcam frames the model was trained on), so the preview
 * shows exactly the same picture area as the frames that go to pose detection.
 */
object CameraConfig {

    private fun resolutionSelector(): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        )
        .build()

    fun preview(): Preview = Preview.Builder()
        .setResolutionSelector(resolutionSelector())
        .build()

    /** Frames for pose detection: RGBA, only the newest frame is kept (no queue building up lag). */
    fun imageAnalysis(): ImageAnalysis = ImageAnalysis.Builder()
        .setResolutionSelector(resolutionSelector())
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()
}
