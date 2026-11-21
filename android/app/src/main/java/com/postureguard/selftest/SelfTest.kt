package com.postureguard.selftest

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * Step 1 checkpoint (temporary, delete when the real screens exist).
 *
 * Checks that both model files packaged in assets/ work on the phone:
 *  1. MediaPipe can load pose_landmarker_full.task
 *  2. ONNX Runtime can load posture_model.onnx (input "features", outputs "label" + "probabilities")
 *  3. The ONNX model gives the SAME answers as Python on the 20 cases from src/export/check_onnx.py
 *
 * Call runAll() off the main thread (it loads ~10 MB of models).
 */
object SelfTest {
    private const val TAG = "SelfTest"
    const val POSE_MODEL = "pose_landmarker_full.task"
    const val ONNX_MODEL = "posture_model.onnx"
    const val TEST_CASES = "onnx_test_cases.json"
    private const val N_FEATURES = 9

    data class CheckResult(val name: String, val passed: Boolean, val details: String)

    fun runAll(context: Context): List<CheckResult> = listOf(
        runCheck("Pose model loads (MediaPipe)") { checkPoseModel(context) },
        runCheck("Posture model loads (ONNX Runtime)") { checkOnnxLoads(context) },
        runCheck("ONNX answers = Python answers (20 test cases)") { checkOnnxCases(context) },
    ).onEach { Log.i(TAG, "${if (it.passed) "PASS" else "FAIL"} ${it.name}: ${it.details}") }

    private inline fun runCheck(name: String, block: () -> String): CheckResult =
        try {
            CheckResult(name, true, block())
        } catch (e: Throwable) {
            Log.e(TAG, "Check failed: $name", e)
            CheckResult(name, false, "${e::class.simpleName}: ${e.message}")
        }

    // 1. MediaPipe ---------------------------------------------------------------------------------
    private fun checkPoseModel(context: Context): String {
        val start = System.nanoTime()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(POSE_MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(1)
            .build()
        val landmarker = PoseLandmarker.createFromOptions(context, options)
        landmarker.close()
        return "loaded in ${(System.nanoTime() - start) / 1_000_000} ms"
    }

    // 2. ONNX load ---------------------------------------------------------------------------------
    private fun openSession(context: Context): Pair<OrtEnvironment, OrtSession> {
        val env = OrtEnvironment.getEnvironment()
        val bytes = context.assets.open(ONNX_MODEL).use { it.readBytes() }
        return env to env.createSession(bytes, OrtSession.SessionOptions())
    }

    private fun checkOnnxLoads(context: Context): String {
        val (_, session) = openSession(context)
        session.use {
            check(it.inputNames == setOf("features")) { "unexpected inputs ${it.inputNames}" }
            check(it.outputNames.containsAll(listOf("label", "probabilities"))) {
                "unexpected outputs ${it.outputNames}"
            }
            return "input ${it.inputNames}, outputs ${it.outputNames}"
        }
    }

    // 3. ONNX vs Python ----------------------------------------------------------------------------
    @Suppress("UNCHECKED_CAST")
    private fun checkOnnxCases(context: Context): String {
        val json = JSONObject(context.assets.open(TEST_CASES).bufferedReader().use { it.readText() })
        val tolerance = json.getDouble("tolerance")
        val cases = json.getJSONArray("cases")
        val n = cases.length()

        val input = FloatArray(n * N_FEATURES)
        val expectedLabel = IntArray(n)
        val expectedProba = DoubleArray(n * 2)
        for (i in 0 until n) {
            val case = cases.getJSONObject(i)
            val features = case.getJSONArray("input")
            require(features.length() == N_FEATURES) { "case $i has ${features.length()} features" }
            for (j in 0 until N_FEATURES) input[i * N_FEATURES + j] = features.getDouble(j).toFloat()
            expectedLabel[i] = case.getInt("label")
            val proba = case.getJSONArray("probabilities")
            expectedProba[i * 2] = proba.getDouble(0)
            expectedProba[i * 2 + 1] = proba.getDouble(1)
        }

        val (env, session) = openSession(context)
        session.use {
            // All 20 cases in one batch -> compare with Python
            // The app takes its decision from the PROBABILITIES (same rule as sklearn: bad if P(bad) > P(good)).
            // The model's own "label" output is only checked as extra information: ONNX Runtime 1.27 returned
            // wrong labels (always 1) for this model while its probabilities were right.
            var labelMismatches = 0
            var rawLabelMismatches = 0
            var maxDiff = 0.0
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(n.toLong(), N_FEATURES.toLong()))
                .use { tensor ->
                    it.run(mapOf("features" to tensor)).use { result ->
                        val rawLabels = result.get("label").get().value as LongArray
                        val probas = result.get("probabilities").get().value as Array<FloatArray>
                        for (i in 0 until n) {
                            val label = if (probas[i][1] > probas[i][0]) 1 else 0
                            if (label != expectedLabel[i]) labelMismatches++
                            if (rawLabels[i].toInt() != expectedLabel[i]) rawLabelMismatches++
                            maxDiff = max(maxDiff, abs(probas[i][0] - expectedProba[i * 2]))
                            maxDiff = max(maxDiff, abs(probas[i][1] - expectedProba[i * 2 + 1]))
                        }
                    }
                }
            check(maxDiff <= tolerance) { "probabilities differ by up to $maxDiff (tolerance $tolerance)" }
            check(labelMismatches == 0) { "$labelMismatches/$n decisions differ from Python" }

            // One sample at a time, like the app will do 5x per second -> time per classification
            val single = FloatArray(N_FEATURES)
            val shape = longArrayOf(1, N_FEATURES.toLong())
            repeat(5) { _ -> runOne(env, it, input, 0, single, shape) } // warm-up
            val start = System.nanoTime()
            for (i in 0 until n) runOne(env, it, input, i, single, shape)
            val msPerSample = (System.nanoTime() - start) / 1_000_000.0 / n

            return "$n/$n decisions match, max probability difference ${"%.1e".format(maxDiff)}, " +
                "${"%.2f".format(msPerSample)} ms per classification on this phone" +
                (if (rawLabelMismatches > 0) " (warning: raw 'label' output wrong in $rawLabelMismatches/$n, not used)" else "")
        }
    }

    private fun runOne(
        env: OrtEnvironment, session: OrtSession, all: FloatArray, row: Int, buf: FloatArray, shape: LongArray,
    ) {
        System.arraycopy(all, row * N_FEATURES, buf, 0, N_FEATURES)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), shape).use { t ->
            session.run(mapOf("features" to t)).close()
        }
    }
}
