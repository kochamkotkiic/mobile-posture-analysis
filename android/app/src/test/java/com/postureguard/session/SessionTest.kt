package com.postureguard.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionTest {

    // --- scenario -----------------------------------------------------------------------------------

    @Test
    fun `default scenario is 5 min 20 s with good, mild and strong steps`() {
        val scenario = GuidedScenario()
        assertEquals(320_000L, scenario.totalMs)
        assertEquals(
            listOf("prepare", "good", "mild", "good", "strong", "good", "mild", "good"),
            scenario.steps.map { it.kind.csvName },
        )
    }

    @Test
    fun `position follows the step boundaries`() {
        val scenario = GuidedScenario()
        assertEquals(0, scenario.positionAt(0)!!.index)
        assertEquals(0, scenario.positionAt(4_999)!!.index)

        val p = scenario.positionAt(5_000)!! // first "sit straight" step starts
        assertEquals(1, p.index)
        assertEquals(60_000L, p.msLeftInStep)
        assertEquals(0L, p.msIntoStep)

        assertEquals(ScenarioStep.Kind.MILD, scenario.positionAt(65_000)!!.step.kind)
        assertEquals(ScenarioStep.Kind.STRONG, scenario.positionAt(155_000)!!.step.kind)
        assertEquals(7, scenario.positionAt(319_999)!!.index)
    }

    @Test
    fun `scenario is over after the last step`() {
        val scenario = GuidedScenario()
        assertEquals(null, scenario.positionAt(320_000))
        assertEquals(null, scenario.positionAt(-1))
    }

    @Test
    fun `ground truth is good 0, slouching 1, prepare not evaluated`() {
        assertEquals(0, ScenarioStep.Kind.GOOD.groundTruth)
        assertEquals(1, ScenarioStep.Kind.MILD.groundTruth)
        assertEquals(1, ScenarioStep.Kind.STRONG.groundTruth)
        assertEquals(-1, ScenarioStep.Kind.PREPARE.groundTruth)
    }

    // --- recorder -----------------------------------------------------------------------------------

    @Test
    fun `recorder writes frames, battery and meta CSV files`() {
        val dir = Files.createTempDirectory("session").toFile()
        val folder = File(dir, "test_session")
        SessionRecorder(folder, mapOf("phone" to "Samsung SM-A566B", "note" to "a, b")).use { rec ->
            rec.writeFrame(
                FrameRecord(
                    tMs = 1234, step = 2, segment = "mild", groundTruth = 1, personVisible = true, newPrediction = 1,
                    confidence = 0.876f, smoothedLabel = 1, signShown = false, badSeconds = 1.5f,
                    features = FloatArray(9) { it * 0.01f },
                )
            )
            rec.writeFrame(
                FrameRecord(1270, 2, "mild", 1, false, -1, 0.876f, 1, false, 0f, null)
            )
            rec.writeBattery(BatteryRecord(10_000, 83, false, 31.4f, -250_000, 0))
            rec.writeBattery(BatteryRecord(20_000, 83, true, 31.6f, Long.MIN_VALUE, -1))
            assertEquals(2, rec.frameCount)
        }

        val frames = File(folder, "frames.csv").readLines()
        assertEquals(SessionRecorder.FRAME_HEADER, frames[0])
        assertEquals(
            "1234,2,mild,1,1,1,0.8760,1,0,1.50,0.00000,0.01000,0.02000,0.03000,0.04000,0.05000,0.06000,0.07000,0.08000",
            frames[1],
        )
        assertEquals("1270,2,mild,1,0,-1,0.8760,1,0,0.00,,,,,,,,,", frames[2])
        assertEquals(19, frames[0].split(",").size)
        assertEquals(19, frames[2].split(",").size)

        val battery = File(folder, "battery.csv").readLines()
        assertEquals(SessionRecorder.BATTERY_HEADER, battery[0])
        assertEquals("10000,83,0,31.4,-250000,0", battery[1])
        assertEquals("20000,83,1,31.6,,-1", battery[2])

        val meta = File(folder, "meta.csv").readLines()
        assertEquals(listOf("key,value", "phone,Samsung SM-A566B", "note,\"a, b\""), meta)

        assertTrue(dir.deleteRecursively())
    }
}
