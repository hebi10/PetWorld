package com.haebi.petworld.data.sync

import androidx.room.withTransaction
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.backup.PetWorldMutationGate
import com.haebi.petworld.data.migration.FirstImportPayloads
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.sync.withLock

internal data class SyncReviewApproval(
    val accountKey: String,
    val kind: String,
    val petId: String,
    val entityId: String,
    val revision: Long,
    val remoteDigest: String,
    val reviewedLocalJson: String?,
    val acceptRemote: Boolean,
)

/** Explicit accept-remote foundation. Never discard an attempted or ambiguous write. */
internal class SyncConflictReview(
    private val db: PetWorldDatabase,
    private val account: LocalAccountScope.SignedIn,
    private val source: SyncDocumentSource,
    private val permission: SyncPermission = SyncPermission { false },
) {
    private suspend fun requirePermission() {
        if (!permission.permits(account)) throw SyncReceiveDenied()
    }

    suspend fun accept(approval: SyncReviewApproval): Boolean {
        requirePermission()
        require(approval.accountKey == account.key)
        if (!approval.acceptRemote) return false
        val document = source.read(account, approval.kind, approval.petId, approval.entityId) ?: return false
        requirePermission()
        val remote = validateRemoteDocument(document)
        require(remote.kind == approval.kind && remote.petId == approval.petId && remote.entityId == approval.entityId)
        if (remote.digest != approval.remoteDigest || remote.revision != approval.revision ||
            remote.tombstone || remote.photoFile != null) return false
        return PetWorldMutationGate.mutex.withLock {
            requirePermission()
            db.withTransaction {
                val inbox = db.syncInboundDao()
                val outbox = db.syncOutboxDao()
                val key = account.key
                val kind = remote.kind
                val pet = remote.petId
                val id = remote.entityId
                val latest = inbox.latest(key, kind, pet, id) ?: return@withTransaction false
                if (latest.revision != remote.revision || latest.payloadDigest != remote.digest ||
                    latest.status != "CONFLICT" ||
                    inbox.hasTombstone(key, kind, pet, id) ||
                    outbox.hasUnsafeToSupersede(key, kind, pet, id)) return@withTransaction false
                val localPet = if (kind == "PET") db.petDao().find(id) else null
                val localCare = if (kind == "CARE") db.careDao().find(id) else null
                val local = localPet?.let(FirstImportPayloads::pet) ?: localCare?.let(FirstImportPayloads::record)
                if (local?.let(::canonicalSyncJson) != approval.reviewedLocalJson ||
                    local?.get("photoFile") != null ||
                    localCare != null && localCare.petId != pet) return@withTransaction false
                if (local != null && ((local["createdAtMs"] as? Number)?.toLong() !=
                        (remote.fields["createdAtMs"] as? Number)?.toLong() ||
                        local["type"] != remote.fields["type"])) return@withTransaction false
                if (kind == "CARE") {
                    val parent = db.petDao().find(pet) ?: return@withTransaction false
                    val parentBaseline = inbox.baseline(key, "PET", pet, pet) ?: return@withTransaction false
                    if (inbox.hasTombstone(key, "PET", pet, pet) ||
                        inbox.hasUnresolved(key, "PET", pet, pet) ||
                        outbox.hasPending(key, "PET", pet, pet) ||
                        !sameSyncDomain(FirstImportPayloads.pet(parent), parseSyncJson(parentBaseline.remoteJson)))
                        return@withTransaction false
                    if (localCare == null) db.careDao().insert(requireNotNull(remote.care))
                    else check(db.careDao().update(requireNotNull(remote.care)) == 1)
                } else {
                    if (localPet == null) db.petDao().insert(requireNotNull(remote.pet))
                    else check(db.petDao().update(requireNotNull(remote.pet)) == 1)
                }
                // Retain every superseded outbox payload and prior remote conflict as audit data.
                outbox.supersedeUnsent(key, kind, pet, id)
                inbox.markReviewed(key, kind, pet, id, remote.revision)
                check(inbox.update(latest.copy(status = "APPLIED",
                    localJson = local?.let(::canonicalSyncJson))) == 1)
                requirePermission()
                true
            }
        }
    }
}
