package com.postureguard.cloud

/** A user as the cloud shows it to others (cloud/app/routers/me.py public_profile). */
data class UserProfile(
    val uid: String,
    val email: String?,
    val displayName: String?,
) {
    /** Name to show: display name, else the part of the email before "@", else the uid. */
    val shownName: String get() = displayName?.takeIf { it.isNotBlank() } ?: email?.substringBefore('@') ?: uid
}

/** One friendship from MY point of view (cloud/app/routers/friends.py view()). */
data class Friendship(
    val id: Int,
    val friend: UserProfile,
    /** "pending" or "accepted". */
    val status: String,
    /** "outgoing" = I sent the request, "incoming" = they sent it to me. */
    val direction: String,
    /** I marked this friend as favorite: my 10-min alerts notify them. */
    val favorite: Boolean,
) {
    val isAccepted: Boolean get() = status == "accepted"
    val isIncomingRequest: Boolean get() = status == "pending" && direction == "incoming"
    val isOutgoingRequest: Boolean get() = status == "pending" && direction == "outgoing"
}

/**
 * A friend challenge "hold good posture for 5 s" (cloud/app/tables.py Task).
 * status: "pending" | "passed" | "failed" | "expired" (the server expires it after 10 min).
 */
data class ChallengeTask(
    val id: Int,
    val fromUid: String,
    val toUid: String,
    val status: String,
    /** Filled when answered: how many samples the cloud judged and the share judged "good". */
    val nSamples: Int?,
    val goodFraction: Float?,
) {
    val isPending: Boolean get() = status == "pending"
    val passed: Boolean get() = status == "passed"
}

/** The server answered with an error (or could not be reached: [status] = 0). */
class CloudException(val status: Int, message: String) : Exception(message)
