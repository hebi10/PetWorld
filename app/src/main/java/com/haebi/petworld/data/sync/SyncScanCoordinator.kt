package com.haebi.petworld.data.sync

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.backup.PetWorldMutationGate
import com.haebi.petworld.data.room.PetWorldDatabase
import org.json.JSONArray
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "sync_scan")
data class SyncScanState(
    @PrimaryKey @ColumnInfo(name = "account_key") val accountKey: String,
    val round: Long = 1,
    @ColumnInfo(name = "pet_cursor") val petCursor: String? = null,
    @ColumnInfo(name = "pending_parents_json") val pendingParentsJson: String = "[]",
    @ColumnInfo(name = "record_cursor") val recordCursor: String? = null,
    @ColumnInfo(name = "root_exhausted") val rootExhausted: Boolean = false,
) {
    internal fun parents(): List<String> = JSONArray(pendingParentsJson).let { list ->
        (0 until list.length()).map { list.getString(it) }
    }
    internal fun complete(): Boolean = rootExhausted && parents().isEmpty()
}

@Dao
interface SyncScanDao {
    @Query("SELECT * FROM sync_scan WHERE account_key = :accountKey LIMIT 1")
    suspend fun find(accountKey: String): SyncScanState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: SyncScanState)
}

internal fun interface SyncPageSource {
    suspend fun page(account: LocalAccountScope.SignedIn, parent: String?, after: String?, limit: Int): RemoteSyncPage
}

internal fun interface SyncDocumentSource {
    /** Null must mean verified absence on the server, never a cache miss. */
    suspend fun read(account: LocalAccountScope.SignedIn, kind: String, pet: String, id: String): RemoteSyncDocument?
}

internal enum class ScanStep { MORE, COMPLETE }

internal class SyncScanCoordinator(
    private val db: PetWorldDatabase,
    private val account: LocalAccountScope.SignedIn,
    private val source: SyncPageSource,
    private val permission: SyncPermission = SyncPermission { false },
    photos: SyncPhotoTransfer? = null,
) {
    private val scan = db.syncScanDao()
    private val receiver = SyncReceiver(db, account, permission, photos)
    private suspend fun checkPermission() {
        if (!permission.permits(account)) throw SyncReceiveDenied()
    }

    suspend fun step(limit: Int = 50): ScanStep {
        require(limit in 1..100)
        checkPermission()
        val saved = scan.find(account.key)
        val state = saved ?: SyncScanState(account.key)
        if (state.complete()) return ScanStep.COMPLETE
        val parents = state.parents()
        require(parents.size <= 100 && parents.distinct().size == parents.size && parents.all(::validSyncPathId))
        val parent = parents.firstOrNull()
        val after = if (parent == null) state.petCursor else state.recordCursor
        val page = source.page(account, parent, after, limit)
        checkPermission()
        require(page.accountKey == account.key && page.parentPetId == parent)
        require(page.documents.size <= limit && page.documents.none { it.entityId == after })
        require(!page.hasMore || page.nextCursor != null && page.nextCursor != after)
        val next = if (parent == null) {
            state.copy(petCursor = page.nextCursor ?: state.petCursor,
                pendingParentsJson = JSONArray(page.documents.map { it.entityId }).toString(),
                recordCursor = null, rootExhausted = !page.hasMore)
        } else if (page.hasMore) {
            state.copy(recordCursor = requireNotNull(page.nextCursor))
        } else {
            state.copy(pendingParentsJson = JSONArray(parents.drop(1)).toString(), recordCursor = null)
        }

        // Page changes and progress commit together; stale concurrent checkpoints roll back.
        receiver.applyPage(page) {
            checkPermission()
            check(scan.find(account.key) == saved) { "Scan checkpoint changed" }
            scan.save(next)
        }
        return if (next.complete()) ScanStep.COMPLETE else ScanStep.MORE
    }

    /** Explicit next pass: a single scan is not proof of a stable global snapshot. */
    suspend fun restartCompletedRound(): Boolean = PetWorldMutationGate.mutex.withLock {
        checkPermission()
        db.withTransaction {
            val state = scan.find(account.key) ?: return@withTransaction false
            if (!state.complete()) return@withTransaction false
            check(state.round < Long.MAX_VALUE)
            scan.save(SyncScanState(account.key, round = state.round + 1))
            checkPermission()
            true
        }
    }
}
