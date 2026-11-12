package com.haebi.petworld.data.account

import java.security.MessageDigest

sealed interface LocalAccountScope {
    data object Guest : LocalAccountScope

    data class SignedIn internal constructor(val key: String) : LocalAccountScope {
        init {
            require(key.matches(Regex("[0-9a-f]{64}")))
        }
    }

    companion object {
        fun fromFirebaseUid(uid: String): LocalAccountScope {
            require(uid.isNotBlank() && uid.length <= 4096) {
                "A valid authenticated account is required."
            }
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(uid.toByteArray(Charsets.UTF_8))
            val key = bytes.joinToString(separator = "") {
                "%02x".format(it.toInt() and 0xff)
            }
            return SignedIn(key)
        }
    }
}

val LocalAccountScope.isGuest: Boolean
    get() = this == LocalAccountScope.Guest

val LocalAccountScope.databaseFileName: String
    get() = when (this) {
        LocalAccountScope.Guest -> "petworld_profiles.db"
        is LocalAccountScope.SignedIn -> "petworld_account_${key}.db"
    }

val LocalAccountScope.photoDirectoryName: String
    get() = when (this) {
        LocalAccountScope.Guest -> "pet_photos"
        is LocalAccountScope.SignedIn -> "pet_photos_accounts/${key}"
    }

val LocalAccountScope.selectedPetPreferenceKey: String
    get() = when (this) {
        LocalAccountScope.Guest -> "selected_pet_id"
        is LocalAccountScope.SignedIn -> "selected_pet_id_account_${key}"
    }
