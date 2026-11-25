package com.haebi.petworld.data.sync

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

const val SYNC_WAITING_BASELINE = "WAITING_BASELINE"

@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["op_id"], unique = true),
        Index(value = ["account_key", "sequence"]),
    ],
)
data class SyncOutboxEntry(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    @ColumnInfo(name = "op_id") val opId: String,
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "entity_kind") val entityKind: String,
    @ColumnInfo(name = "pet_id") val petId: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val operation: String,
    @ColumnInfo(name = "before_json") val beforeJson: String?,
    @ColumnInfo(name = "after_json") val afterJson: String?,
    @ColumnInfo(name = "expected_revision") val expectedRevision: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val status: String = SYNC_WAITING_BASELINE,
    val attempt: Int = 0,
)

@Dao
interface SyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: SyncOutboxEntry): Long

    @Query("SELECT * FROM sync_outbox WHERE account_key = :accountKey ORDER BY sequence ASC LIMIT :limit")
    suspend fun list(accountKey: String, limit: Int): List<SyncOutboxEntry>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE account_key = :accountKey")
    fun observeCount(accountKey: String): Flow<Int>

    @Query("""
        SELECT EXISTS(SELECT 1 FROM sync_outbox
        WHERE account_key = :accountKey AND entity_kind = :kind AND pet_id = :petId
          AND entity_id = :entityId AND status != 'ACKNOWLEDGED')
    """)
    suspend fun hasPending(accountKey: String, kind: String, petId: String, entityId: String): Boolean

    @Query("""
        SELECT EXISTS(SELECT 1 FROM sync_outbox
        WHERE account_key = :accountKey AND entity_kind = :kind AND pet_id = :petId
          AND entity_id = :entityId)
    """)
    suspend fun hasHistory(accountKey: String, kind: String, petId: String, entityId: String): Boolean

    @Query("SELECT * FROM sync_outbox WHERE account_key = :accountKey AND status != 'ACKNOWLEDGED' ORDER BY sequence ASC LIMIT 1")
    suspend fun firstOutstanding(accountKey: String): SyncOutboxEntry?

    // Only a reviewed remote baseline may call this. -1 means verified absent, not unknown.
    @Query("""
        UPDATE sync_outbox SET expected_revision = :revision, status = 'READY'
        WHERE account_key = :accountKey AND op_id = :opId
          AND status = 'WAITING_BASELINE' AND expected_revision IS NULL
          AND ((operation = 'CREATE' AND :revision = -1)
            OR (operation IN ('UPDATE', 'DELETE') AND :revision >= 0))
    """)
    suspend fun readyAfterVerifiedBaseline(accountKey: String, opId: String, revision: Long): Int

    @Query("""
        UPDATE sync_outbox SET status = 'IN_FLIGHT', attempt = attempt + 1
        WHERE account_key = :accountKey AND op_id = :opId AND status = :oldStatus
          AND attempt = :oldAttempt AND expected_revision IS NOT NULL
          AND status IN ('READY', 'RETRY', 'IN_FLIGHT') AND attempt < 100
    """)
    suspend fun beginAttempt(accountKey: String, opId: String, oldStatus: String, oldAttempt: Int): Int

    @Query("""
        UPDATE sync_outbox SET status = :status
        WHERE account_key = :accountKey AND op_id = :opId
          AND status = 'IN_FLIGHT' AND attempt = :attempt
          AND :status IN ('ACKNOWLEDGED', 'RETRY', 'CONFLICT', 'BLOCKED')
    """)
    suspend fun finishAttempt(accountKey: String, opId: String, attempt: Int, status: String): Int
}

/**
 * Durable local journal only. No operation can be uploaded until a verified remote baseline
 * resolves the expected revision and a later stage explicitly enables a sender.
 * Call inside the same Room transaction as the entity mutation.
 */
internal class LocalSyncOutbox(
    private val database: PetWorldDatabase,
    scope: LocalAccountScope,
) {
    private val accountKey = (scope as? LocalAccountScope.SignedIn)?.key

    suspend fun petChanged(before: PetEntity?, after: PetEntity?) {
        val key = accountKey ?: return
        val row = requireNotNull(after ?: before)
        require(before == null || after == null || before.id == after.id)
        append(key, "PET", row.id, row.id, before?.let(::petSnapshot), after?.let(::petSnapshot))
    }

    suspend fun recordChanged(before: CareEntity?, after: CareEntity?) {
        val key = accountKey ?: return
        val row = requireNotNull(after ?: before)
        require(before == null || after == null ||
            (before.id == after.id && before.petId == after.petId))
        append(key, "CARE", row.petId, row.id, before?.let(::recordSnapshot), after?.let(::recordSnapshot))
    }

    private suspend fun append(
        key: String, kind: String, petId: String, id: String,
        before: String?, after: String?,
    ) {
        check(database.inTransaction()) { "Outbox and record mutation must share a Room transaction" }
        val operation = when {
            before == null -> "CREATE"
            after == null -> "DELETE"
            else -> "UPDATE"
        }
        database.syncOutboxDao().insert(
            SyncOutboxEntry(
                opId = UUID.randomUUID().toString(), accountKey = key, entityKind = kind,
                petId = petId, entityId = id, operation = operation,
                beforeJson = before, afterJson = after, createdAt = System.currentTimeMillis(),
            )
        )
    }
}

// Local snapshot format is deliberately separate from the cloud payload and its validation rules.
// Preserve original values, including explicit null versus zero, rather than truncate or reinterpret.
private fun snapshot(vararg values: Pair<String, Any?>): String = JSONObject().apply {
    put("snapshotVersion", 1)
    values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}.toString()

private fun petSnapshot(value: PetEntity): String = snapshot(
    "id" to value.id, "name" to value.name, "species" to value.species,
    "breed" to value.breed, "birthday" to value.birthday,
    "estimatedAgeYears" to value.estimatedAgeYears, "sex" to value.sex,
    "neuterStatus" to value.neuterStatus, "notes" to value.notes,
    "photoFile" to value.photoFile, "isArchived" to value.isArchived,
    "createdAt" to value.createdAt, "updatedAt" to value.updatedAt,
)

private fun recordSnapshot(value: CareEntity): String = snapshot(
    "id" to value.id, "petId" to value.petId, "type" to value.type,
    "occurredAt" to value.occurredAt, "timezoneId" to value.timezoneId,
    "foodKind" to value.foodKind, "foodProductName" to value.foodProductName,
    "foodServedMilli" to value.foodServedMilli, "foodConsumedMilli" to value.foodConsumedMilli,
    "foodUnit" to value.foodUnit, "waterMl" to value.waterMl,
    "eliminationKind" to value.eliminationKind, "eliminationCount" to value.eliminationCount,
    "activityKind" to value.activityKind, "activityMinutes" to value.activityMinutes,
    "weightGrams" to value.weightGrams, "weightInputUnit" to value.weightInputUnit,
    "photoFile" to value.photoFile, "note" to value.note,
    "createdAt" to value.createdAt, "updatedAt" to value.updatedAt,
)
