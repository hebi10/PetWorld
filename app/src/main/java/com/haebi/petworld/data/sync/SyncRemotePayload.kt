package com.haebi.petworld.data.sync

import com.haebi.petworld.data.CareValidation
import com.haebi.petworld.data.toDraft
import com.haebi.petworld.data.migration.FirstImportPayloads
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.toDomain
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

internal data class RemoteSyncDocument(
    val kind: String,
    val petId: String,
    val entityId: String,
    val fields: Map<String, Any?>,
)

internal data class RemoteSyncPage(
    val accountKey: String,
    val parentPetId: String?,
    val documents: List<RemoteSyncDocument>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

internal fun validSyncPathId(id: String): Boolean =
    id.isNotBlank() && id.length <= 100 && '/' !in id && id != "." && id != ".."

internal fun canonicalSyncJson(fields: Map<String, Any?>): String = JSONObject().apply {
    fields.toSortedMap().forEach { (key, value) ->
        require(value == null || value is String || value is Boolean || value is Int || value is Long)
        put(key, value ?: JSONObject.NULL)
    }
}.toString()

internal fun parseSyncJson(json: String): Map<String, Any?> = JSONObject(json).let { obj ->
    obj.keys().asSequence().associateWith { key -> obj.get(key).takeUnless { it == JSONObject.NULL } }
}

internal fun domainSyncFields(fields: Map<String, Any?>): Map<String, Any?> =
    fields - setOf("revision", "lastSyncOpId", "deletedAtMs")

internal fun sameSyncDomain(first: Map<String, Any?>, second: Map<String, Any?>): Boolean =
    FirstImportPayloads.sameRemote(domainSyncFields(first), domainSyncFields(second))

internal data class ValidatedRemote(
    val kind: String,
    val petId: String,
    val entityId: String,
    val revision: Long,
    val tombstone: Boolean,
    val fields: Map<String, Any?>,
    val json: String,
    val digest: String,
    val pet: PetEntity?,
    val care: CareEntity?,
) {
    val photoFile: String? get() = pet?.photoFile ?: care?.photoFile
}

/** Strict round-trip against the existing FB-04 schema; unknown or incomplete records cannot modify Room. */
internal fun validateRemoteDocument(doc: RemoteSyncDocument): ValidatedRemote {
    require(doc.kind in setOf("PET", "CARE") && validSyncPathId(doc.petId) && validSyncPathId(doc.entityId))
    require(doc.kind != "PET" || doc.petId == doc.entityId)
    val f = doc.fields
    fun integer(key: String): Long = when (val value = f[key]) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("Invalid remote integer")
    }
    fun optionalInteger(key: String): Long? = f[key]?.let { integer(key) }
    fun optionalInt(key: String): Int? = optionalInteger(key)?.let {
        require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()); it.toInt()
    }
    fun string(key: String): String = f[key] as? String ?: error("Invalid remote text")
    fun optionalString(key: String): String? = f[key]?.let { string(key) }
    val revision = integer("revision")
    require(revision >= 0 && integer("schemaVersion") == 1L)
    require(string("id") == doc.entityId)
    val created = integer("createdAtMs")
    val updated = integer("updatedAtMs")
    require(created in 1L..9_999_999_999_999L && updated in created..9_999_999_999_999L)
    val deleted = optionalInteger("deletedAtMs")
    require(deleted == null || deleted in 1L..9_999_999_999_999L)
    f["lastSyncOpId"]?.let {
        require(it is String && it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }
    val photo = optionalString("photoFile")
    require(photo == null || photo.matches(Regex("pet_[a-z0-9-]+\\.jpg")))
    val pet: PetEntity?
    val care: CareEntity?
    val roundTrip: Map<String, Any?>
    if (doc.kind == "PET") {
        care = null
        pet = PetEntity(
            id = doc.entityId, name = string("name"), species = string("species"),
            breed = string("breed"), birthday = optionalString("birthday"),
            estimatedAgeYears = optionalInt("estimatedAgeYears"), sex = string("sex"),
            neuterStatus = string("neuterStatus"), notes = string("notes"),
            photoFile = photo, isArchived = f["isArchived"] as? Boolean ?: error("Invalid archive flag"),
            createdAt = created, updatedAt = updated,
        )
        pet.toDomain()
        pet.birthday?.let { LocalDate.parse(it) }
        require(pet.birthday == null || pet.estimatedAgeYears == null)
        roundTrip = FirstImportPayloads.pet(pet)
    } else {
        pet = null
        require(string("petId") == doc.petId)
        val occurred = integer("occurredAtMs")
        require(occurred in 1L..9_999_999_999_999L)
        ZoneId.of(string("timezoneId"))
        care = CareEntity(
            id = doc.entityId, petId = doc.petId, type = string("type"), occurredAt = occurred,
            timezoneId = string("timezoneId"), foodKind = optionalString("foodKind"),
            foodProductName = optionalString("foodProductName"),
            foodServedMilli = optionalInteger("foodServedMilli"),
            foodConsumedMilli = optionalInteger("foodConsumedMilli"), foodUnit = optionalString("foodUnit"),
            waterMl = optionalInteger("waterMl"), eliminationKind = optionalString("eliminationKind"),
            eliminationCount = optionalInt("eliminationCount"), activityKind = optionalString("activityKind"),
            activityMinutes = optionalInt("activityMinutes"), weightGrams = optionalInteger("weightGrams"),
            weightInputUnit = optionalString("weightInputUnit"), photoFile = photo,
            note = string("note"), createdAt = created, updatedAt = updated,
        )
        require(photo == null || care.type == "ELIMINATION")
        require(CareValidation.error(care.toDraft(), now = occurred) == null)
        roundTrip = FirstImportPayloads.record(care)
    }
    require(FirstImportPayloads.sameRemote(roundTrip + ("revision" to revision),
        f - setOf("lastSyncOpId", "deletedAtMs")))
    val json = canonicalSyncJson(f)
    val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return ValidatedRemote(doc.kind, doc.petId, doc.entityId, revision, deleted != null,
        f.toMap(), json, digest, pet, care)
}
