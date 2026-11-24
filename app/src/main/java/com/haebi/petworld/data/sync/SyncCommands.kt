package com.haebi.petworld.data.sync

import com.haebi.petworld.data.migration.FirstImportPayloads
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetEntity
import java.security.MessageDigest
import java.time.ZoneId
import org.json.JSONObject

internal class SyncPhotoPending : IllegalStateException("Photo synchronization must be verified first")

internal data class SyncCommand(
    val accountKey: String,
    val opId: String,
    val entityKind: String,
    val petId: String,
    val entityId: String,
    val operation: String,
    val expectedRevision: Long,
    val previous: Map<String, Any?>?,
    val next: Map<String, Any?>,
    val requestDigest: String,
) {
    val resultingRevision: Long get() = expectedRevision + 1
    fun receipt(): Map<String, Any?> = mapOf(
        "opId" to opId, "entityKind" to entityKind, "petId" to petId,
        "entityId" to entityId, "operation" to operation,
        "expectedRevision" to expectedRevision, "resultingRevision" to resultingRevision,
        "requestDigest" to requestDigest,
    )
}

internal sealed interface SyncCommitOutcome {
    data object Applied : SyncCommitOutcome
    data object Conflict : SyncCommitOutcome
    data object Retry : SyncCommitOutcome
    data object Blocked : SyncCommitOutcome
}

internal sealed interface SyncRemoteDecision {
    data class Write(val document: Map<String, Any?>, val receipt: Map<String, Any?>) : SyncRemoteDecision
    data class Stop(val result: SyncCommitOutcome) : SyncRemoteDecision
}

/** Shared compare-and-set contract. The transport must read and write in one server transaction. */
internal fun decideSyncCommit(
    command: SyncCommand,
    existingReceipt: Map<String, Any?>?,
    current: Map<String, Any?>?,
): SyncRemoteDecision {
    if (existingReceipt != null) {
        return SyncRemoteDecision.Stop(
            if (FirstImportPayloads.sameRemote(command.receipt(), existingReceipt))
                SyncCommitOutcome.Applied else SyncCommitOutcome.Conflict
        )
    }
    if (current?.get("deletedAtMs") != null) return SyncRemoteDecision.Stop(SyncCommitOutcome.Conflict)
    val expected = command.previous
    val matches = if (expected == null) current == null
        else FirstImportPayloads.sameRemote(expected, current)
    return if (matches) SyncRemoteDecision.Write(command.next, command.receipt())
        else SyncRemoteDecision.Stop(SyncCommitOutcome.Conflict)
}

internal object SyncCommands {
    private val opIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun from(entry: SyncOutboxEntry): SyncCommand {
        require(entry.accountKey.matches(Regex("[0-9a-f]{64}")) && opIdPattern.matches(entry.opId))
        require(validId(entry.petId) && validId(entry.entityId))
        require(entry.entityKind in setOf("PET", "CARE"))
        require(entry.entityKind != "PET" || entry.petId == entry.entityId)
        require(entry.createdAt in 1L..9_999_999_999_999L)
        val revision = requireNotNull(entry.expectedRevision)
        require(revision in -1L until Long.MAX_VALUE)
        val before = entry.beforeJson?.let { payload(entry, it) }
        val after = entry.afterJson?.let { payload(entry, it) }
        when (entry.operation) {
            "CREATE" -> require(revision == -1L && before == null && after != null)
            "UPDATE" -> require(revision >= 0 && before != null && after != null)
            "DELETE" -> require(revision >= 0 && before != null && after == null)
            else -> error("Unsupported operation")
        }
        if (before != null && after != null) {
            require(before["createdAtMs"] == after["createdAtMs"])
            require(before["type"] == after["type"])
        }
        // File bytes and their retention are not yet synchronized (FB-06).
        if (before?.get("photoFile") != after?.get("photoFile")) throw SyncPhotoPending()
        val previous = before?.plus("revision" to revision)
        val next = (after ?: requireNotNull(before)).toMutableMap().apply {
            put("revision", revision + 1)
            if (entry.operation == "DELETE") put("deletedAtMs", entry.createdAt)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String?) {
            if (value == null) {
                digest.update(byteArrayOf(0))
                return
            }
            digest.update(byteArrayOf(1))
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        listOf(entry.accountKey, entry.opId, entry.entityKind, entry.petId, entry.entityId,
            entry.operation, revision.toString(), entry.createdAt.toString(), entry.beforeJson,
            entry.afterJson).forEach(::field)
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return SyncCommand(entry.accountKey, entry.opId, entry.entityKind, entry.petId,
            entry.entityId, entry.operation, revision, previous, next, hash)
    }

    private fun validId(id: String): Boolean = id.isNotBlank() && id.length <= 100 &&
        '/' !in id && id != "." && id != ".."

    private fun payload(entry: SyncOutboxEntry, raw: String): Map<String, Any?> {
        val o = JSONObject(raw)
        fun str(k: String): String = o.get(k) as? String ?: error("Invalid snapshot field")
        fun nstr(k: String): String? = if (o.isNull(k)) null else str(k)
        fun number(k: String): Long = when (val value = o.get(k)) {
            is Int -> value.toLong()
            is Long -> value
            else -> error("Invalid snapshot integer")
        }
        fun nlong(k: String): Long? = if (o.isNull(k)) null else number(k)
        fun nint(k: String): Int? = nlong(k)?.let {
            require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            it.toInt()
        }
        require(number("snapshotVersion") == 1L && str("id") == entry.entityId)
        val fields = if (entry.entityKind == "PET") {
            FirstImportPayloads.pet(PetEntity(
                id = str("id"), name = str("name"), species = str("species"),
                breed = str("breed"), birthday = nstr("birthday"),
                estimatedAgeYears = nint("estimatedAgeYears"), sex = str("sex"),
                neuterStatus = str("neuterStatus"), notes = str("notes"),
                photoFile = nstr("photoFile"), isArchived = o.get("isArchived") as Boolean,
                createdAt = number("createdAt"), updatedAt = number("updatedAt"),
            ))
        } else {
            require(str("petId") == entry.petId)
            ZoneId.of(str("timezoneId"))
            FirstImportPayloads.record(CareEntity(
                id = str("id"), petId = str("petId"), type = str("type"),
                occurredAt = number("occurredAt"), timezoneId = str("timezoneId"),
                foodKind = nstr("foodKind"), foodProductName = nstr("foodProductName"),
                foodServedMilli = nlong("foodServedMilli"), foodConsumedMilli = nlong("foodConsumedMilli"),
                foodUnit = nstr("foodUnit"), waterMl = nlong("waterMl"),
                eliminationKind = nstr("eliminationKind"), eliminationCount = nint("eliminationCount"),
                activityKind = nstr("activityKind"), activityMinutes = nint("activityMinutes"),
                weightGrams = nlong("weightGrams"), weightInputUnit = nstr("weightInputUnit"),
                photoFile = nstr("photoFile"), note = str("note"),
                createdAt = number("createdAt"), updatedAt = number("updatedAt"),
            ))
        }
        val expectedKeys = fields.keys.filterNot { it == "schemaVersion" || it == "revision" }
            .map { when (it) {
                "createdAtMs" -> "createdAt"
                "updatedAtMs" -> "updatedAt"
                "occurredAtMs" -> "occurredAt"
                else -> it
            } }.toSet() + "snapshotVersion"
        require(o.keys().asSequence().toSet() == expectedKeys)
        return fields
    }
}
