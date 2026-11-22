package com.postureguard.session

/**
 * Step 6: the scripted test. The app tells the user what to do and when, so it knows the truth ("ground truth")
 * for every moment, like the hand-labelled GROUND_TRUTH segments in src/core/add_ground_truth.py.
 * Plain Kotlin, unit-tested.
 */
data class ScenarioStep(
    val kind: Kind,
    val durationMs: Long,
    val instruction: String,
) {
    enum class Kind(val groundTruth: Int, val csvName: String) {
        PREPARE(-1, "prepare"), // not evaluated
        GOOD(0, "good"),
        MILD(1, "mild"),
        STRONG(1, "strong"),
    }
}

class GuidedScenario(val steps: List<ScenarioStep> = DEFAULT_STEPS) {

    /** Where the scenario is at [elapsedMs] after its start. */
    data class Position(
        val index: Int,          // 0-based step index
        val step: ScenarioStep,
        val msLeftInStep: Long,
        val msIntoStep: Long,
    )

    val totalMs: Long = steps.sumOf { it.durationMs }

    /** null when the scenario is over. */
    fun positionAt(elapsedMs: Long): Position? {
        if (elapsedMs < 0) return null
        var stepStart = 0L
        steps.forEachIndexed { index, step ->
            val stepEnd = stepStart + step.durationMs
            if (elapsedMs < stepEnd) return Position(index, step, stepEnd - elapsedMs, elapsedMs - stepStart)
            stepStart = stepEnd
        }
        return null
    }

    companion object {
        private const val STRAIGHT = "Sit up STRAIGHT, like during calibration."
        private const val MILD = "Slouch SLIGHTLY: head a little forward and down, like when you are tired."
        private const val STRONG = "Slouch STRONGLY: round your back, head forward and down."

        /** ~5.5 min: good / mild / good / strong / good / mild / good (testp6 idea + mild slouching, SKILL.md). */
        val DEFAULT_STEPS = listOf(
            ScenarioStep(ScenarioStep.Kind.PREPARE, 5_000, "Get into position and sit straight. Don't touch the phone."),
            ScenarioStep(ScenarioStep.Kind.GOOD, 60_000, STRAIGHT),
            ScenarioStep(ScenarioStep.Kind.MILD, 45_000, MILD),
            ScenarioStep(ScenarioStep.Kind.GOOD, 45_000, STRAIGHT),
            ScenarioStep(ScenarioStep.Kind.STRONG, 45_000, STRONG),
            ScenarioStep(ScenarioStep.Kind.GOOD, 45_000, STRAIGHT),
            ScenarioStep(ScenarioStep.Kind.MILD, 45_000, MILD),
            ScenarioStep(ScenarioStep.Kind.GOOD, 30_000, STRAIGHT),
        )
    }
}
