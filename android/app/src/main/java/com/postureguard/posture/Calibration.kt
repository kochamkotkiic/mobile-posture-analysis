package com.postureguard.posture

/**
 * The user's "sitting straight" position: the mean of the 9 features during calibration.
 * Stored with the same keys as the Python profiles (FeatureExtractor.FEATURE_NAMES).
 */
class Calibration(values: FloatArray) {
    val values: FloatArray = values.copyOf()

    init {
        require(values.size == FeatureExtractor.N_FEATURES) { "calibration needs 9 values, got ${values.size}" }
    }

    /**
     * features - calibration, element by element (= normalize_features() in normalizer.py).
     * The result is what the model expects as input.
     */
    fun normalize(features: FloatArray): FloatArray {
        require(features.size == FeatureExtractor.N_FEATURES) { "expected 9 features, got ${features.size}" }
        return FloatArray(FeatureExtractor.N_FEATURES) { i -> features[i] - values[i] }
    }

    /** For saving: {"nose_x": ..., ..., "right_shoulder_z": ...} like the Python profile JSON. */
    fun toMap(): Map<String, Double> =
        FeatureExtractor.FEATURE_NAMES.mapIndexed { i, name -> name to values[i].toDouble() }.toMap()

    override fun equals(other: Any?): Boolean = other is Calibration && values.contentEquals(other.values)
    override fun hashCode(): Int = values.contentHashCode()
    override fun toString(): String = "Calibration(${toMap()})"

    companion object {
        /** Missing keys count as 0.0, like calibration.get(key, 0.0) in normalizer.py. */
        fun fromMap(map: Map<String, Number>): Calibration =
            Calibration(FloatArray(FeatureExtractor.N_FEATURES) { i ->
                map[FeatureExtractor.FEATURE_NAMES[i]]?.toFloat() ?: 0f
            })
    }
}
