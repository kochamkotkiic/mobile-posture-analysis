package com.postureguard.calibration

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.postureguard.posture.Calibration
import com.postureguard.posture.FeatureExtractor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A saved calibration plus when it was made and from how many frames. */
data class SavedCalibration(
    val calibration: Calibration,
    val calibratedAtMillis: Long,
    val sampleCount: Int,
)

private val Context.calibrationDataStore by preferencesDataStore(name = "calibration")

/**
 * Stores ONE calibration on the phone (DataStore), with the same keys as the Python profiles
 * ("nose_x" ... "right_shoulder_z"), so it can later be synced to the cloud unchanged.
 */
class CalibrationRepository(context: Context) {

    private val dataStore = context.applicationContext.calibrationDataStore

    /** Emits the saved calibration (null if there is none) and again whenever it changes. */
    val calibration: Flow<SavedCalibration?> = dataStore.data.map { prefs -> prefs.toSavedCalibration() }

    suspend fun save(calibration: Calibration, sampleCount: Int, calibratedAtMillis: Long) {
        dataStore.edit { prefs ->
            calibration.toMap().forEach { (name, value) -> prefs[doublePreferencesKey(name)] = value }
            prefs[CALIBRATED_AT] = calibratedAtMillis
            prefs[SAMPLE_COUNT] = sampleCount
        }
    }

    private fun Preferences.toSavedCalibration(): SavedCalibration? {
        val values = FeatureExtractor.FEATURE_NAMES.map { name -> this[doublePreferencesKey(name)] ?: return null }
        return SavedCalibration(
            calibration = Calibration.fromMap(FeatureExtractor.FEATURE_NAMES.zip(values).toMap()),
            calibratedAtMillis = this[CALIBRATED_AT] ?: 0L,
            sampleCount = this[SAMPLE_COUNT] ?: 0,
        )
    }

    private companion object {
        val CALIBRATED_AT = longPreferencesKey("calibrated_at")
        val SAMPLE_COUNT = intPreferencesKey("sample_count")
    }
}
