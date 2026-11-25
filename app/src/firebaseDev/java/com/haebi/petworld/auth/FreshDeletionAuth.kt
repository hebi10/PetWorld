package com.haebi.petworld.auth

/** Deliberately not a data class: never render the ID token. Server must verify it again. */
internal class FreshDeletionAuth private constructor(
    val uid: String, internal val idToken: String, val authTimeSeconds: Long,
) {
    override fun toString() = "FreshDeletionAuth(redacted)"

    companion object {
        fun create(uid: String, idToken: String, authTimeSeconds: Long, nowSeconds: Long): FreshDeletionAuth? =
            if (uid.isNotBlank() && idToken.isNotBlank() && authTimeSeconds > 0 &&
                authTimeSeconds <= nowSeconds && nowSeconds - authTimeSeconds <= 300)
                FreshDeletionAuth(uid, idToken, authTimeSeconds) else null
    }
}
