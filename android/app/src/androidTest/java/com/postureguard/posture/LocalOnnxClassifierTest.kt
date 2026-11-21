package com.postureguard.posture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 * Runs ON THE PHONE (ONNX Runtime Android needs a device): LocalOnnxClassifier must give the same decision and
 * confidence as the Python model on the 20 cases from src/export/check_onnx.py (assets/onnx_test_cases.json).
 */
@RunWith(AndroidJUnit4::class)
class LocalOnnxClassifierTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sameDecisionsAsPythonOnAllTestCases() {
        val modelBytes = context.assets.open(LocalOnnxClassifier.MODEL_ASSET).use { it.readBytes() }
        val json = JSONObject(context.assets.open("onnx_test_cases.json").bufferedReader().use { it.readText() })
        val tolerance = json.getDouble("tolerance").toFloat()
        val cases = json.getJSONArray("cases")

        LocalOnnxClassifier(modelBytes).use { classifier ->
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val input = case.getJSONArray("input").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                val probabilities = case.getJSONArray("probabilities")
                val expectedConfidence = max(probabilities.getDouble(0), probabilities.getDouble(1)).toFloat()

                val prediction = runBlocking { classifier.classify(input) }

                assertEquals("case $i (row ${case.getInt("row")}) label", case.getInt("label"), prediction.label)
                assertEquals("case $i confidence", expectedConfidence, prediction.confidence, tolerance)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongNumberOfFeaturesIsRejected() {
        val modelBytes = context.assets.open(LocalOnnxClassifier.MODEL_ASSET).use { it.readBytes() }
        LocalOnnxClassifier(modelBytes).use { classifier -> runBlocking { classifier.classify(FloatArray(8)) } }
    }
}
