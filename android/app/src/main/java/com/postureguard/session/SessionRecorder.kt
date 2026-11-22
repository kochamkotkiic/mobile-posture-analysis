package com.postureguard.session

import java.io.BufferedWriter
import java.io.File
import java.util.Locale

/** One camera frame of a recorded session (one CSV row). */
data class FrameRecord(
    val tMs: Long,                 // since the session started
    val step: Int,                 // scenario step index, -1 without a scenario
    val segment: String,           // "good" / "mild" / "strong" / "prepare" / "" (no scenario)
    val groundTruth: Int,          // 0 good, 1 bad, -1 unknown / not evaluated
    val personVisible: Boolean,
    val newPrediction: Int,        // model answer made on THIS frame: 0/1, -1 = no model run on this frame
    val confidence: Float,         // of the last model answer
    val smoothedLabel: Int,        // 0/1 after smoothing (14 of 20)
    val signShown: Boolean,        // red "Bad posture" sign visible
    val badSeconds: Float,
    val features: FloatArray?,     // features - calibration (what the model sees), null without a person
)

/** Battery / heat snapshot (one CSV row, every 10 s). */
data class BatteryRecord(
    val tMs: Long,
    val levelPercent: Int,
    val charging: Boolean,
    val temperatureC: Float,
    val currentMicroAmps: Long,   // negative = discharging (device dependent), Long.MIN_VALUE = not available
    val thermalStatus: Int,       // PowerManager thermal status (0 = none ... 6 = shutdown), -1 = not available
)

/**
 * Writes a session into [folder]: frames.csv (one row per camera frame), battery.csv (every 10 s), meta.csv.
 * Plain Kotlin/Java IO (unit-tested on the PC). Use from one thread only.
 */
class SessionRecorder(val folder: File, meta: Map<String, String>) : AutoCloseable {

    private val frames: BufferedWriter
    private val battery: BufferedWriter
    var frameCount = 0
        private set

    init {
        folder.mkdirs()
        File(folder, "meta.csv").bufferedWriter().use { w ->
            w.write("key,value\n")
            meta.forEach { (k, v) -> w.write("${csv(k)},${csv(v)}\n") }
        }
        frames = File(folder, "frames.csv").bufferedWriter()
        frames.write(FRAME_HEADER + "\n")
        battery = File(folder, "battery.csv").bufferedWriter()
        battery.write(BATTERY_HEADER + "\n")
    }

    fun writeFrame(r: FrameRecord) {
        val f = r.features
        val featureCols = if (f == null) List(9) { "" } else f.map { fmt("%.5f", it) }
        frames.write(
            listOf(
                r.tMs.toString(), r.step.toString(), r.segment, r.groundTruth.toString(),
                if (r.personVisible) "1" else "0", r.newPrediction.toString(), fmt("%.4f", r.confidence),
                r.smoothedLabel.toString(), if (r.signShown) "1" else "0", fmt("%.2f", r.badSeconds),
            ).plus(featureCols).joinToString(",") + "\n"
        )
        frameCount++
    }

    fun writeBattery(r: BatteryRecord) {
        battery.write(
            listOf(
                r.tMs.toString(), r.levelPercent.toString(), if (r.charging) "1" else "0",
                fmt("%.1f", r.temperatureC),
                if (r.currentMicroAmps == Long.MIN_VALUE) "" else r.currentMicroAmps.toString(),
                r.thermalStatus.toString(),
            ).joinToString(",") + "\n"
        )
    }

    /** Write buffered rows to the file (call about once per second, so little is lost if the app is killed). */
    fun flush() {
        frames.flush()
        battery.flush()
    }

    override fun close() {
        frames.close()
        battery.close()
    }

    companion object {
        val FEATURE_COLUMNS = listOf(
            "d_nose_x", "d_nose_y", "d_nose_z",
            "d_left_shoulder_x", "d_left_shoulder_y", "d_left_shoulder_z",
            "d_right_shoulder_x", "d_right_shoulder_y", "d_right_shoulder_z",
        )
        val FRAME_HEADER = (listOf(
            "t_ms", "step", "segment", "ground_truth", "person_visible", "new_prediction", "confidence",
            "smoothed", "sign_shown", "bad_seconds",
        ) + FEATURE_COLUMNS).joinToString(",")
        const val BATTERY_HEADER = "t_ms,battery_percent,charging,temperature_c,current_ua,thermal_status"

        private fun fmt(format: String, value: Float) = String.format(Locale.US, format, value)
        private fun csv(s: String) = if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
