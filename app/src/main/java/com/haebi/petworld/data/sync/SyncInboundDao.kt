package com.haebi.petworld.data.sync

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "sync_inbound",
    indices = [Index(
        value = ["account_key", "entity_kind", "pet_id", "entity_id", "revision", "payload_digest"],
        unique = true, name = "idx_sync_inbound_identity",
    )],
)
data class SyncInboundEntry(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "entity_kind") val entityKind: String,
    @ColumnInfo(name = "pet_id") val petId: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val revision: Long,
    @ColumnInfo(name = "payload_digest") val payloadDigest: String,
    @ColumnInfo(name = "remote_json") val remoteJson: String,
    @ColumnInfo(name = "local_json") val localJson: String?,
    val tombstone: Boolean,
    val status: String,
)

@Dao
interface SyncInboundDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(value: SyncInboundEntry): Long

    @Update
    suspend fun update(value: SyncInboundEntry): Int

    @Query("""
        SELECT * FROM sync_inbound WHERE account_key = :account AND entity_kind = :kind
        AND pet_id = :pet AND entity_id = :entity AND revision = :revision
        AND payload_digest = :digest LIMIT 1
    """)
    suspend fun exact(account: String, kind: String, pet: String, entity: String,
        revision: Long, digest: String): SyncInboundEntry?

    @Query("""
        SELECT * FROM sync_inbound WHERE account_key = :account AND entity_kind = :kind
        AND pet_id = :pet AND entity_id = :entity ORDER BY revision DESC, sequence DESC LIMIT 1
    """)
    suspend fun latest(account: String, kind: String, pet: String, entity: String): SyncInboundEntry?

    @Query("""
        SELECT * FROM sync_inbound WHERE account_key = :account AND entity_kind = :kind
        AND pet_id = :pet AND entity_id = :entity AND status IN ('APPLIED', 'MATCHED')
        ORDER BY revision DESC, sequence DESC LIMIT 1
    """)
    suspend fun baseline(account: String, kind: String, pet: String, entity: String): SyncInboundEntry?

    @Query("""
        SELECT EXISTS(SELECT 1 FROM sync_inbound WHERE account_key = :account
        AND entity_kind = :kind AND pet_id = :pet AND entity_id = :entity AND tombstone = 1)
    """)
    suspend fun hasTombstone(account: String, kind: String, pet: String, entity: String): Boolean

    @Query("""
        SELECT EXISTS(SELECT 1 FROM sync_inbound WHERE account_key = :account
        AND entity_kind = :kind AND pet_id = :pet AND entity_id = :entity
        AND status IN ('CONFLICT', 'DELETE_REVIEW', 'PHOTO_PENDING'))
    """)
    suspend fun hasUnresolved(account: String, kind: String, pet: String, entity: String): Boolean

    @Query("""
        SELECT COUNT(*) FROM sync_inbound WHERE account_key = :account
        AND status IN ('CONFLICT', 'DELETE_REVIEW', 'PHOTO_PENDING', 'PARENT_PENDING')
    """)
    fun observeReviewCount(account: String): Flow<Int>

    @Query("SELECT * FROM sync_inbound WHERE account_key = :account ORDER BY sequence")
    suspend fun list(account: String): List<SyncInboundEntry>
}
