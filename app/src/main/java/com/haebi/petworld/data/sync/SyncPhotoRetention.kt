package com.haebi.petworld.data.sync

import androidx.room.withTransaction
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.backup.PetWorldMutationGate
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.sync.withLock

internal enum class PhotoRetentionReason {
    ACTIVE_REFERENCE, RETAINED_HISTORY, UNVERIFIED, WAIT_TRUSTED_REMOTE_AUDIT
}
internal data class PhotoRetentionReview(val fileName: String, val petId: String, val reason: PhotoRetentionReason)

/**
 * Classifies candidates, never deletes bytes. Even an unreferenced verified local file
 * requires a trusted all-device remote reference and retention audit before cleanup.
 */
internal class SyncPhotoRetention(
    private val db: PetWorldDatabase,
    private val account: LocalAccountScope.SignedIn,
    private val permission: SyncPermission = SyncPermission { false },
) {
    suspend fun review(limit: Int = 100): List<PhotoRetentionReview> = PetWorldMutationGate.mutex.withLock {
        require(limit in 1..500)
        if (!permission.permits(account)) throw SyncReceiveDenied()
        db.withTransaction {
            val dao = db.syncPhotoDao()
            val rows = dao.retired(account.key, limit).map { row ->
                if (!permission.permits(account)) throw SyncReceiveDenied()
                val name = row.fileName
                val reason = when {
                    !validCloudPhotoName(name) -> PhotoRetentionReason.UNVERIFIED
                    db.petDao().referencesPhoto(name) || db.careDao().referencesPhoto(name) ||
                        dao.hasActiveFile(account.key, name) -> PhotoRetentionReason.ACTIVE_REFERENCE
                    db.syncOutboxDao().retainsPhotoHistory(account.key, name) ||
                        db.syncInboundDao().retainsPhotoHistory(account.key, name) -> PhotoRetentionReason.RETAINED_HISTORY
                    row.status != "REMOTE_VERIFIED" || row.sha256 == null || row.sizeBytes == null ||
                        row.width == null || row.height == null -> PhotoRetentionReason.UNVERIFIED
                    else -> PhotoRetentionReason.WAIT_TRUSTED_REMOTE_AUDIT
                }
                PhotoRetentionReview(name, row.petId, reason)
            }
            if (!permission.permits(account)) throw SyncReceiveDenied()
            rows
        }
    }
}
