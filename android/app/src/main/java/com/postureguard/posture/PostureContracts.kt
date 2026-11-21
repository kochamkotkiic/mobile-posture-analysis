package com.postureguard.posture

/*
 * Contracts shared with the team (SKILL.md section 6). Change only together with Zenon / Nuno.
 * Plain Kotlin: no Android imports.
 */

/** Classifies ONE sample of normalized features (9 values, order = FeatureExtractor.FEATURE_NAMES). */
interface PostureClassifier {
    suspend fun classify(normalizedFeatures: FloatArray): Prediction
}

/** label: 0 = good, 1 = bad. confidence: probability of the chosen label (like predict_proba[pred] in Python). */
data class Prediction(val label: Int, val confidence: Float) {
    companion object {
        const val GOOD = 0
        const val BAD = 1

        /**
         * Decision from the model's probabilities, the same rule as sklearn's predict():
         * the class with the higher probability; on a tie the first class (good) wins.
         * We never use the ONNX "label" output (wrong in ONNX Runtime 1.27, see AGENTS.md).
         */
        fun fromProbabilities(pGood: Float, pBad: Float): Prediction =
            if (pBad > pGood) Prediction(BAD, pBad) else Prediction(GOOD, pGood)
    }
}

/** What the rest of the app (UI, friend feature, cloud) listens to. */
data class PostureState(
    val personVisible: Boolean,
    val smoothedLabel: Int,       // 0 good, 1 bad (after smoothing)
    val confidence: Float,        // of the last raw prediction
    val badSeconds: Float,        // how long the smoothed label has been bad without a break
    val badPostureShown: Boolean, // badSeconds >= 3 s -> red "Bad posture" sign
)

sealed interface PostureEvent {
    /** 10 minutes of continuous bad posture. Fired once per bad episode. Later: "notify friend". */
    data object LongBadPostureAlert : PostureEvent

    /** Posture became good again after the "Bad posture" sign had been shown. */
    data object PostureRecovered : PostureEvent
}
