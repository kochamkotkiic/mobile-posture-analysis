package com.postureguard.cloud

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Client for Nuno's cloud API (cloud/README.md on branch feat/cloud-predict).
 * No extra libraries: HttpURLConnection + org.json (both built into Android).
 *
 * [baseUrl] = the "Server address" setting (D8), e.g. http://10.0.2.2:8000 from the emulator.
 * [token] = what goes after "Authorization: Bearer ". Today a dev token "dev:<uid>:<email>"
 * (server started with DEV_AUTH=true); later the Firebase ID token.
 * All calls run on Dispatchers.IO and throw [CloudException] on any failure.
 */
class CloudApi(private val baseUrl: String, private val token: String?) {

    // --- account ------------------------------------------------------------------------------------

    /** Call once after every sign-in: creates or updates my user row. */
    suspend fun putMe(displayName: String?): UserProfile =
        parseProfile(JSONObject(request("PUT", "/me", JSONObject().put("display_name", displayName ?: JSONObject.NULL))))

    // --- friends ------------------------------------------------------------------------------------

    suspend fun friends(): List<Friendship> {
        val array = JSONArray(request("GET", "/friends"))
        return (0 until array.length()).map { parseFriendship(array.getJSONObject(it)) }
    }

    suspend fun sendFriendRequest(email: String): Friendship =
        parseFriendship(JSONObject(request("POST", "/friends", JSONObject().put("email", email.trim().lowercase()))))

    suspend fun acceptFriend(friendshipId: Int): Friendship =
        parseFriendship(JSONObject(request("POST", "/friends/$friendshipId/accept")))

    suspend fun setFavorite(friendshipId: Int, favorite: Boolean): Friendship =
        parseFriendship(JSONObject(request("PUT", "/friends/$friendshipId/favorite", JSONObject().put("favorite", favorite))))

    /** Unfriend, decline a request or cancel my own request. */
    suspend fun removeFriend(friendshipId: Int) {
        request("DELETE", "/friends/$friendshipId")
    }

    // --- alerts and tasks -----------------------------------------------------------------------------

    /** Reports a 10-minute alert; the server pushes it to my favorite friends. Returns who was notified. */
    suspend fun reportAlert(firedAtMillis: Long): List<String> {
        val body = JSONObject().put("fired_at", Instant.ofEpochMilli(firedAtMillis).toString())
        val notified = JSONObject(request("POST", "/alerts", body)).optJSONArray("notified") ?: JSONArray()
        return (0 until notified.length()).map { notified.getString(it) }
    }

    /** Friend side: challenge [toUid] to hold good posture for 5 s. Returns the task id. */
    suspend fun sendChallenge(toUid: String): Int =
        JSONObject(request("POST", "/tasks", JSONObject().put("to_uid", toUid))).getInt("id")

    // --- plumbing -----------------------------------------------------------------------------------

    private suspend fun request(method: String, path: String, body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + path
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw CloudException(0, "Invalid server address: $baseUrl")
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                Log.w(TAG, "$method $path -> HTTP $status: $text")
                throw CloudException(status, errorMessage(status, text))
            }
            text
        } catch (e: IOException) {
            // e.g. server not running, wrong address, or http:// blocked by the network security config
            Log.w(TAG, "$method $url failed: $e")
            throw CloudException(0, "Can't reach the server at $baseUrl")
        } finally {
            connection.disconnect()
        }
    }

    /** FastAPI errors look like {"detail": "text"} or {"detail": [{"msg": ...}, ...]} (422). */
    private fun errorMessage(status: Int, text: String): String {
        val detail = try {
            when (val d = JSONObject(text).opt("detail")) {
                is String -> d
                is JSONArray -> (0 until d.length()).joinToString("; ") { d.getJSONObject(it).optString("msg") }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
        return when {
            !detail.isNullOrBlank() -> detail.replaceFirstChar { it.uppercase() }
            status == 401 -> "Not signed in on the server"
            else -> "Server error $status"
        }
    }

    private fun parseProfile(o: JSONObject) = UserProfile(
        uid = o.getString("uid"),
        email = o.optStringOrNull("email"),
        displayName = o.optStringOrNull("display_name"),
    )

    private fun parseFriendship(o: JSONObject) = Friendship(
        id = o.getInt("id"),
        friend = parseProfile(o.getJSONObject("friend")),
        status = o.getString("status"),
        direction = o.getString("direction"),
        favorite = o.optBoolean("favorite", false),
    )

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

    private companion object {
        const val TAG = "CloudApi"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}
