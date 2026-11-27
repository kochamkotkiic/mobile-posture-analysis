package com.postureguard.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.postureguard.calibration.CalibrationRepository
import com.postureguard.remote.ClassifierMode
import com.postureguard.settings.AppSettings
import com.postureguard.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    /** When the current calibration was made, null if there is none. */
    val calibratedAtMillis: Long? = null,
    val calibrationFrames: Int = 0,
)

/** Settings screen + app-wide settings (MainActivity reads [state] for the developer tools switch). */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)
    private val calibrationRepository = CalibrationRepository(application)

    val state: StateFlow<SettingsUiState> =
        combine(repository.settings, calibrationRepository.calibration) { settings, calibration ->
            SettingsUiState(settings, calibration?.calibratedAtMillis, calibration?.sampleCount ?: 0)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setClassifierMode(mode: ClassifierMode) {
        viewModelScope.launch { repository.setClassifierMode(mode) }
    }

    fun setServerUrl(url: String) {
        viewModelScope.launch { repository.setServerUrl(url) }
    }

    fun setDeveloperTools(enabled: Boolean) {
        viewModelScope.launch { repository.setDeveloperTools(enabled) }
    }
}
