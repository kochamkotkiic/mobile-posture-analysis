package com.postureguard.ui.friends

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.postureguard.account.Account
import com.postureguard.account.AccountRepository
import com.postureguard.cloud.CloudApi
import com.postureguard.cloud.CloudException
import com.postureguard.cloud.Friendship
import com.postureguard.settings.AppSettings
import com.postureguard.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FriendsUiState(
    /** null while loading from storage. */
    val accountLoaded: Boolean = false,
    val account: Account? = null,
    val serverUrl: String = AppSettings.DEFAULT_SERVER_URL,
    val friendships: List<Friendship> = emptyList(),
    val loading: Boolean = false,
    /** Friendship ids with a request in flight (their buttons are disabled). */
    val busy: Set<Int> = emptySet(),
    val signingIn: Boolean = false,
    val addingFriend: Boolean = false,
    val error: String? = null,
    /** Short confirmation, e.g. "Challenge sent to Emilia". */
    val info: String? = null,
) {
    val incoming: List<Friendship> get() = friendships.filter { it.isIncomingRequest }
    val friends: List<Friendship> get() = friendships.filter { it.isAccepted }.sortedByDescending { it.favorite }
    val outgoing: List<Friendship> get() = friendships.filter { it.isOutgoingRequest }
}

/**
 * Multi-user feature (Zenon): sign-in, friends, favorites, challenges, and reporting the 10-minute alert
 * so the cloud can notify favorite friends. Talks to Nuno's API through [CloudApi].
 */
class FriendsViewModel(application: Application) : AndroidViewModel(application) {

    private val accounts = AccountRepository(application)
    private val settings = SettingsRepository(application)

    private val _state = MutableStateFlow(FriendsUiState())
    val state: StateFlow<FriendsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(accounts.account, settings.settings) { account, s -> account to s.serverUrl }
                .distinctUntilChanged()
                .collect { (account, url) ->
                    val changed = account?.uid != _state.value.account?.uid || url != _state.value.serverUrl
                    _state.update {
                        it.copy(
                            accountLoaded = true, account = account, serverUrl = url,
                            friendships = if (changed) emptyList() else it.friendships,
                        )
                    }
                    if (account != null && changed) refresh()
                }
        }
    }

    private fun api(account: Account? = _state.value.account) = CloudApi(_state.value.serverUrl, account?.token)

    // --- sign-in --------------------------------------------------------------------------------------

    /** Test sign-in (DEV_AUTH server): registers the user on the server, then remembers them on the phone. */
    fun signIn(name: String, email: String) {
        val cleanEmail = email.trim().lowercase()
        if (!Account.isValidEmail(cleanEmail)) {
            _state.update { it.copy(error = "Enter a valid email address") }
            return
        }
        val account = Account(Account.uidFromEmail(cleanEmail), cleanEmail, name.trim())
        _state.update { it.copy(signingIn = true, error = null) }
        viewModelScope.launch {
            try {
                api(account).putMe(account.displayName.ifBlank { null })
                accounts.save(account) // triggers refresh() through the collector above
            } catch (e: CloudException) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(signingIn = false) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { accounts.signOut() }
        _state.update { it.copy(friendships = emptyList(), error = null, info = null) }
    }

    // --- friends --------------------------------------------------------------------------------------

    fun refresh() {
        if (_state.value.account == null) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val list = api().friends()
                _state.update { it.copy(friendships = list, error = null) }
            } catch (e: CloudException) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** Returns through state: on success the email field can be cleared ([FriendsUiState.info] is set). */
    fun addFriend(email: String) {
        val clean = email.trim().lowercase()
        if (!Account.isValidEmail(clean)) {
            _state.update { it.copy(error = "Enter your friend's email address") }
            return
        }
        _state.update { it.copy(addingFriend = true, error = null, info = null) }
        viewModelScope.launch {
            try {
                val f = api().sendFriendRequest(clean)
                _state.update { it.copy(friendships = it.friendships + f, info = "Request sent to ${f.friend.shownName}") }
            } catch (e: CloudException) {
                val message = if (e.status == 404) "No one with that email uses PostureGuard yet" else e.message
                _state.update { it.copy(error = message) }
            } finally {
                _state.update { it.copy(addingFriend = false) }
            }
        }
    }

    fun accept(f: Friendship) = act(f) { replace(api().acceptFriend(f.id)) }

    fun toggleFavorite(f: Friendship) = act(f) { replace(api().setFavorite(f.id, !f.favorite)) }

    /** Decline an incoming request, cancel an outgoing one, or unfriend. */
    fun remove(f: Friendship) = act(f) {
        api().removeFriend(f.id)
        _state.update { s -> s.copy(friendships = s.friendships.filterNot { it.id == f.id }) }
    }

    fun challenge(f: Friendship) = act(f) {
        api().sendChallenge(f.friend.uid)
        _state.update { it.copy(info = "Challenge sent to ${f.friend.shownName}") }
    }

    fun clearMessages() = _state.update { it.copy(error = null, info = null) }

    // --- alerts ---------------------------------------------------------------------------------------

    /** Called on LongBadPostureAlert. Silent: the user is not interrupted if it fails (e.g. offline). */
    fun reportAlert(firedAtMillis: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            val account = accounts.account.first() ?: return@launch
            try {
                val notified = CloudApi(settings.settings.first().serverUrl, account.token).reportAlert(firedAtMillis)
                Log.i(TAG, "Alert reported, notified: $notified")
            } catch (e: CloudException) {
                Log.w(TAG, "Could not report the alert: ${e.message}")
            }
        }
    }

    // --- helpers --------------------------------------------------------------------------------------

    private fun replace(updated: Friendship) =
        _state.update { s -> s.copy(friendships = s.friendships.map { if (it.id == updated.id) updated else it }) }

    private fun act(f: Friendship, block: suspend () -> Unit) {
        if (f.id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + f.id, error = null, info = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CloudException) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(busy = it.busy - f.id) }
            }
        }
    }

    private companion object {
        const val TAG = "Friends"
    }
}
