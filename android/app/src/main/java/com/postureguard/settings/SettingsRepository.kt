package com.postureguard.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.postureguard.remote.ClassifierMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App settings chosen by the user. */
data class AppSettings(
    /** Where classification runs. Only ON_DEVICE is wired up until Step 8 (cloud mode). */
    val classifierMode: ClassifierMode = ClassifierMode.ON_DEVICE,
    /** Address of the /predict server (D8: a setting, never hard-coded). */
    val serverUrl: String = DEFAULT_SERVER_URL,
    /** Shows FPS, model timing, test mode and the Step 6 recording tools. */
    val developerTools: Boolean = false,
) {
    companion object {
        /** The host PC as seen from the Android Emulator (Nuno's server on port 8000). */
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8000"
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            classifierMode = prefs[CLASSIFIER_MODE]
                ?.let { name -> ClassifierMode.entries.firstOrNull { it.name == name } }
                ?: ClassifierMode.ON_DEVICE,
            serverUrl = prefs[SERVER_URL] ?: AppSettings.DEFAULT_SERVER_URL,
            developerTools = prefs[DEVELOPER_TOOLS] ?: false,
        )
    }

    suspend fun setClassifierMode(mode: ClassifierMode) = dataStore.edit { it[CLASSIFIER_MODE] = mode.name }

    suspend fun setServerUrl(url: String) = dataStore.edit { it[SERVER_URL] = url.trim() }

    suspend fun setDeveloperTools(enabled: Boolean) = dataStore.edit { it[DEVELOPER_TOOLS] = enabled }

    private companion object {
        val CLASSIFIER_MODE = stringPreferencesKey("classifier_mode")
        val SERVER_URL = stringPreferencesKey("server_url")
        val DEVELOPER_TOOLS = booleanPreferencesKey("developer_tools")
    }
}
