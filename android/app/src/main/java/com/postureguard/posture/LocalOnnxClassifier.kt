package com.postureguard.posture

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * On-device classification with posture_model.onnx (scaler + RandomForest inside, so do NOT scale again).
 * Takes the model as bytes, so this file has no Android imports (load them with context.assets in the caller).
 * Tested on the phone: app/src/androidTest/.../LocalOnnxClassifierTest.kt (ONNX Runtime Android needs a device).
 */
class LocalOnnxClassifier(modelBytes: ByteArray) : PostureClassifier, AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions())
    private val shape = longArrayOf(1, FeatureExtractor.N_FEATURES.toLong())

    /** ~0.11 ms on the Samsung, so it runs directly on the caller's (background) thread. */
    @Suppress("UNCHECKED_CAST")
    override suspend fun classify(normalizedFeatures: FloatArray): Prediction {
        require(normalizedFeatures.size == FeatureExtractor.N_FEATURES) {
            "expected ${FeatureExtractor.N_FEATURES} features, got ${normalizedFeatures.size}"
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(normalizedFeatures), shape).use { input ->
            session.run(mapOf(INPUT_NAME to input)).use { result ->
                val probabilities = (result.get(PROBABILITIES_NAME).get().value as Array<FloatArray>)[0]
                return Prediction.fromProbabilities(probabilities[0], probabilities[1])
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val MODEL_ASSET = "posture_model.onnx"
        private const val INPUT_NAME = "features"
        private const val PROBABILITIES_NAME = "probabilities"
    }
}
