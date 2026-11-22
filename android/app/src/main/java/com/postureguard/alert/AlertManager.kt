package com.postureguard.alert

import com.postureguard.posture.PostureEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The 10-minute alert (D6): a simple on-screen dialog, no vibration, no sound.
 * Shown on LongBadPostureAlert, stays until the user presses OK.
 * Later (Zenon / Nuno): the same event also notifies the favorite friend.
 */
class AlertManager {
    private val _alertVisible = MutableStateFlow(false)
    val alertVisible: StateFlow<Boolean> = _alertVisible.asStateFlow()

    fun onEvent(event: PostureEvent) {
        if (event == PostureEvent.LongBadPostureAlert) _alertVisible.value = true
    }

    fun dismiss() {
        _alertVisible.value = false
    }
}
