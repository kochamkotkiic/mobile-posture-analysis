package com.postureguard.account

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The signed-in user.
 *
 * TODAY: test sign-in for Nuno's server started with DEV_AUTH=true. The token is "dev:<uid>:<email>",
 * nothing is verified and there is no password. LATER (Firebase): keep this class's API, but fill it from
 * FirebaseAuth.currentUser and return the Firebase ID token from [token].
 */
data class Account(
    val uid: String,
    val email: String,
    val displayName: String,
) {
    /** "Authorization: Bearer <token>" value. */
    val token: String get() = "dev:$uid:$email"

    companion object {
        /** Stable uid from the email: "Emilia.U@pw.edu.pl" -> "emilia-u-pw-edu-pl" (no ':' allowed in dev tokens). */
        fun uidFromEmail(email: String): String =
            email.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

        fun isValidEmail(email: String): Boolean = Regex("^[^@\\s:]+@[^@\\s:]+\\.[^@\\s:]+$").matches(email.trim())
    }
}

private val Context.accountDataStore by preferencesDataStore(name = "account")

class AccountRepository(context: Context) {

    private val dataStore = context.applicationContext.accountDataStore

    /** null = not signed in. */
    val account: Flow<Account?> = dataStore.data.map { prefs ->
        val uid = prefs[UID] ?: return@map null
        val email = prefs[EMAIL] ?: return@map null
        Account(uid, email, prefs[NAME] ?: "")
    }

    suspend fun save(account: Account) {
        dataStore.edit {
            it[UID] = account.uid
            it[EMAIL] = account.email
            it[NAME] = account.displayName
        }
    }

    suspend fun signOut() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val UID = stringPreferencesKey("uid")
        val EMAIL = stringPreferencesKey("email")
        val NAME = stringPreferencesKey("display_name")
    }
}
