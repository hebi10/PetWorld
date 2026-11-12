package com.haebi.petworld.data.migration

import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetEntity
import java.security.MessageDigest

/** Schema v1 must match firebase/firestore.rules exactly; never truncate user data. */
object FirstImportPayloads {
    fun pet(value: PetEntity): Map<String, Any?> {
        require(value.id.isNotBlank() && value.id.length <= 100)
        require(value.name.isNotBlank() && value.name.length <= 40)
        require(value.species in setOf("CAT", "DOG", "OTHER"))
        require(value.breed.length <= 100 && value.notes.length <= 2000)
        require(value.birthday == null || value.birthday.length <= 16)
        require(value.estimatedAgeYears == null || value.estimatedAgeYears in 0..100)
        require(value.sex.length <= 40 && value.neuterStatus.length <= 40)
        require(value.createdAt > 0 && value.updatedAt >= value.createdAt)
        require(value.photoFile == null || value.photoFile.length <= 100)
        return sortedMapOf(
            "id" to value.id,
            "schemaVersion" to 1,
            "revision" to 0,
            "name" to value.name,
            "species" to value.species,
            "isArchived" to value.isArchived,
            "createdAtMs" to value.createdAt,
            "updatedAtMs" to value.updatedAt,
            "breed" to value.breed,
            "birthday" to value.birthday,
            "estimatedAgeYears" to value.estimatedAgeYears,
            "sex" to value.sex,
            "neuterStatus" to value.neuterStatus,
            "notes" to value.notes,
            "photoFile" to value.photoFile,
        )
    }

    fun record(value: CareEntity): Map<String, Any?> {
        require(value.id.isNotBlank() && value.id.length <= 100)
        require(value.petId.isNotBlank() && value.petId.length <= 100)
        require(value.type in setOf("FOOD", "WATER", "ELIMINATION", "ACTIVITY", "WEIGHT"))
        require(value.occurredAt > 0 && value.createdAt > 0 && value.updatedAt >= value.createdAt)
        require(value.timezoneId.isNotBlank() && value.timezoneId.length <= 64)
        require(value.note.length <= 2000)
        require(value.foodKind == null || value.foodKind.length <= 40)
        require(value.foodProductName == null || value.foodProductName.length <= 200)
        require(value.foodUnit == null || value.foodUnit.length <= 8)
        require(value.eliminationKind == null || value.eliminationKind.length <= 40)
        require(value.activityKind == null || value.activityKind.length <= 40)
        require(value.weightInputUnit == null || value.weightInputUnit.length <= 8)
        require(value.photoFile == null || value.photoFile.length <= 100)
        require(value.foodServedMilli == null || value.foodServedMilli in 0..9_999_999_999_999L)
        require(value.foodConsumedMilli == null || value.foodConsumedMilli in 0..9_999_999_999_999L)
        require(value.waterMl == null || value.waterMl in 0..1_000_000_000L)
        require(value.eliminationCount == null || value.eliminationCount in 0..100)
        require(value.activityMinutes == null || value.activityMinutes in 0..1440)
        require(value.weightGrams == null || value.weightGrams in 0..9_999_999_999_999L)
        return sortedMapOf(
            "id" to value.id,
            "petId" to value.petId,
            "schemaVersion" to 1,
            "revision" to 0,
            "type" to value.type,
            "occurredAtMs" to value.occurredAt,
            "timezoneId" to value.timezoneId,
            "note" to value.note,
            "createdAtMs" to value.createdAt,
            "updatedAtMs" to value.updatedAt,
            "foodKind" to value.foodKind,
            "foodProductName" to value.foodProductName,
            "foodServedMilli" to value.foodServedMilli,
            "foodConsumedMilli" to value.foodConsumedMilli,
            "foodUnit" to value.foodUnit,
            "waterMl" to value.waterMl,
            "eliminationKind" to value.eliminationKind,
            "eliminationCount" to value.eliminationCount,
            "activityKind" to value.activityKind,
            "activityMinutes" to value.activityMinutes,
            "weightGrams" to value.weightGrams,
            "weightInputUnit" to value.weightInputUnit,
            "photoFile" to value.photoFile,
        )
    }

    /** Firestore represents integer fields as Long even when the client supplied Int. */
    fun sameRemote(expected: Map<String, Any?>, actual: Map<String, Any?>?): Boolean {
        if (actual == null || expected.keys != actual.keys) return false
        return expected.all { (key, value) ->
            val remote = actual[key]
            when {
                value == null -> remote == null
                value is Number && remote is Number ->
                    (remote is Long || remote is Int) &&
                        (value is Long || value is Int) &&
                        value.toLong() == remote.toLong()
                else -> value == remote
            }
        }
    }

    /**
     * Deterministic typed length-prefixed digest. Includes photos and selectedPetId so a
     * changed Guest source cannot be silently uploaded under an earlier approval.
     */
    fun fingerprint(
        pets: List<PetEntity>,
        records: List<CareEntity>,
        photos: Map<String, String>,
        selectedPetId: String?,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun append(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        fun appendValue(value: Any?) {
            when (value) {
                null -> append("N")
                is String -> { append("S"); append(value) }
                is Boolean -> { append("B"); append(if (value) "1" else "0") }
                is Number -> { append("I"); append(value.toString()) }
                else -> error("Unexpected import field type")
            }
        }
        fun appendDocument(kind: String, fields: Map<String, Any?>) {
            append(kind)
            fields.toSortedMap().forEach { (key, value) ->
                append(key)
                appendValue(value)
            }
        }
        pets.sortedBy { it.id }.forEach { appendDocument("pet", pet(it)) }
        records.sortedBy { it.id }.forEach { appendDocument("record", record(it)) }
        photos.toSortedMap().forEach { (key, sha) ->
            append("photo")
            append(key)
            append(sha)
        }
        append("selectedPet")
        appendValue(selectedPetId)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
