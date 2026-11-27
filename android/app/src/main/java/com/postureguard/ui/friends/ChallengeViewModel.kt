package com.postureguard.ui.friends

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.postureguard.account.AccountRepository
import com.postureguard.challenge.ChallengeFrame
import com.postureguard.challenge.ChallengeRecorder
import com.postureguard.cloud.CloudApi
import com.postureguard.cloud.CloudException
import com.postureguard.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A challenge I received and haven't answered yet. */
data class IncomingChallenge(val taskId: Int, val fromName: String)

sealed interface ChallengePhase {
    /** "Bob challenges you" with Start / Not now. */
    data object Offer : ChallengePhase
    data class Countdown(val secondsLeft: Int) : ChallengePhase
    data class Recording(val secondsLeft: Int, val progress: Float, val samples: Int) : ChallengePhase
    data object Sending : ChallengePhase
    /** The cloud's verdict. */
    data class Result(val passed: Boolean, val goodPercent: Int) : ChallengePhase
    /** Not answered yet: [canRetry] = the challenge is still open (e.g. we couldn't see the user, or offline). */
    data class Problem(val message: String, val canRetry: Boolean) : ChallengePhase
}

data class ChallengeUiState(
    /** null = nothing to show. */
    val challenge: IncomingChallenge? = null,
    val phase: ChallengePhase = ChallengePhase.Offer,
    /** Friend side: "Bob held good posture (92%)" when a challenge I sent was answered. */
    val sentResult: String? = null,
)

/**
 * Friend challenge, both sides (Zenon). Until push notifications exist (Nuno, FCM), the phone asks the server
 * every [POLL_MS] for new challenges and for answers to the ones it sent.
 *
 * Monitored side: Start -> 3 s countdown -> 5 s recording of the normalized features the phone's model judged
 * (MonitorViewModel.challengeFrames) -> upload -> the CLOUD classifies them and decides (D3) -> result.
 * [onFrame] is called on the main thread (MainActivity), so the recorder is used from one thread only.
 */
class ChallengeViewModel(application: Application) : AndroidViewModel(application) {

    private val accounts = AccountRepository(application)
    private val settings = SettingsRepository(application)
    private val recorder = ChallengeRecorder()

    private val _state = MutableStateFlow(ChallengeUiState())
    val state: StateFlow<ChallengeUiState> = _state.asStateFlow()

    /** Challenges the user closed with "Not now" or already answered (don't offer them again). */
    private val handled = mutableSetOf<Int>()
    /** Sent challenges still pending at the last check; null = first check (don't report old results). */
    private var sentPending: Set<Int>? = null
    /** All sent challenges seen at the last check. */
    private var knownSent: Set<Int>? = null

    init {
        viewModelScope.launch {
            while (isActive) {
                poll()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun api(): CloudApi? {
        val account = accounts.account.first() ?: return null
        return CloudApi(settings.settings.first().serverUrl, account.token)
    }

    private suspend fun poll() {
        val api = api() ?: return
        try {
            // Monitored side: offer the newest open challenge (only when nothing else is shown)
            if (_state.value.challenge == null) {
                val open = api.challenges("received").filter { it.isPending && it.id !in handled }
                open.maxByOrNull { it.id }?.let { task ->
                    val name = api.friends().firstOrNull { it.friend.uid == task.fromUid }?.friend?.shownName ?: "A friend"
                    _state.update { it.copy(challenge = IncomingChallenge(task.id, name), phase = ChallengePhase.Offer) }
                }
            }
            // Friend side: did someone answer a challenge I sent?
            val sent = api.challenges("sent")
            val previous = sentPending
            val known = knownSent
            if (previous != null && known != null) {
                // answered since the last check: was pending then, or is new (answered within one check interval)
                val answered = sent.filter { !it.isPending && (it.id in previous || it.id !in known) }
                answered.lastOrNull()?.let { task ->
                    val name = api.friends().firstOrNull { it.friend.uid == task.toUid }?.friend?.shownName ?: "Your friend"
                    val percent = task.goodFraction?.let { " (${(it * 100).roundToInt()}% good)" } ?: ""
                    val text = when (task.status) {
                        "passed" -> "$name held good posture$percent"
                        "failed" -> "$name didn't manage to hold good posture$percent"
                        else -> "$name didn't answer your challenge in time"
                    }
                    _state.update { it.copy(sentResult = text) }
                }
            }
            sentPending = sent.filter { it.isPending }.map { it.id }.toSet()
            knownSent = sent.map { it.id }.toSet()
        } catch (e: CloudException) {
            // Offline or server down: try again at the next check (CloudApi already logged the reason)
        }
    }

    /** Check right now, e.g. when the monitor appears or after sending a challenge. */
    fun pollNow() {
        viewModelScope.launch { poll() }
    }

    // --- monitored side -------------------------------------------------------------------------------

    fun start() {
        if (_state.value.challenge == null) return
        recorder.start()
        _state.update { it.copy(phase = ChallengePhase.Countdown(3)) }
    }

    /** "Not now" / "Done" / "Close". An unanswered challenge expires on the server after 10 minutes. */
    fun close() {
        _state.value.challenge?.let { handled += it.taskId }
        recorder.cancel()
        _state.update { it.copy(challenge = null, phase = ChallengePhase.Offer) }
    }

    fun onFrame(frame: ChallengeFrame) {
        if (!recorder.isActive) return
        when (val status = recorder.onFrame(frame)) {
            is ChallengeRecorder.Status.Countdown -> setPhase(ChallengePhase.Countdown(status.secondsLeft))
            is ChallengeRecorder.Status.Recording ->
                setPhase(ChallengePhase.Recording(status.secondsLeft, status.progress, status.samples))
            is ChallengeRecorder.Status.Done -> submit(status.samples, status.visibleFraction)
            ChallengeRecorder.Status.Idle -> Unit
        }
    }

    private fun submit(samples: List<FloatArray>, visibleFraction: Float) {
        val challenge = _state.value.challenge ?: return
        Log.i(TAG, "Recorded ${samples.size} samples, in view ${(visibleFraction * 100).roundToInt()}% of the frames")
        if (samples.size < MIN_SAMPLES || visibleFraction < MIN_VISIBLE_FRACTION) {
            // Don't waste the challenge: the server would fail it. Let the user try again.
            setPhase(ChallengePhase.Problem("We couldn't see you clearly. Keep your head and shoulders in view and try again.", canRetry = true))
            return
        }
        setPhase(ChallengePhase.Sending)
        viewModelScope.launch {
            val api = api()
            if (api == null) {
                setPhase(ChallengePhase.Problem("You're signed out.", canRetry = false))
                return@launch
            }
            try {
                val task = api.submitChallengeResult(challenge.taskId, samples)
                handled += challenge.taskId
                Log.i(TAG, "Challenge ${task.id}: ${task.status}, ${task.nSamples} samples, good ${task.goodFraction}")
                setPhase(ChallengePhase.Result(task.passed, ((task.goodFraction ?: 0f) * 100).roundToInt()))
            } catch (e: CloudException) {
                val phase = when (e.status) {
                    0 -> ChallengePhase.Problem("Can't reach the server. Check the connection and try again.", canRetry = true)
                    409 -> ChallengePhase.Problem("This challenge has expired.", canRetry = false)
                    else -> ChallengePhase.Problem(e.message ?: "Something went wrong.", canRetry = false)
                }
                if (!phase.canRetry) handled += challenge.taskId
                setPhase(phase)
            }
        }
    }

    // --- friend side ----------------------------------------------------------------------------------

    fun dismissSentResult() = _state.update { it.copy(sentResult = null) }

    private fun setPhase(phase: ChallengePhase) = _state.update { it.copy(phase = phase) }

    companion object {
        private const val TAG = "Challenge"
        const val POLL_MS = 15_000L
        /** Same minimum as the server (cloud/app/config.py task_min_samples). */
        const val MIN_SAMPLES = 15
        /** The user must be in view for most of the recording (otherwise 1 good second could pass). */
        const val MIN_VISIBLE_FRACTION = 0.7f
    }
}
