package com.haebi.petworld.data.sync

import androidx.room.withTransaction
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.backup.PetWorldMutationGate
import com.haebi.petworld.data.migration.FirstImportPayloads
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.sync.withLock

internal enum class InboundResult {
    APPLIED, MATCHED, DUPLICATE, STALE, CONFLICT, DELETE_REVIEW, PHOTO_PENDING, PARENT_PENDING
}

internal class SyncReceiveDenied : IllegalStateException("Verified receive session required")

/**
 * No implicit overwrite. Only new identities or unchanged local rows with an exact prior
 * remote baseline can be applied. Deletions and photo changes are staged for later review.
 */
internal class SyncReceiver(
    private val database: PetWorldDatabase,
    private val account: LocalAccountScope.SignedIn,
    private val permission: SyncPermission = SyncPermission { false },
) {
    private val inbox = database.syncInboundDao()
    private val outbox = database.syncOutboxDao()
    private val key = account.key

    private suspend fun requirePermission() {
        if (!permission.permits(account)) throw SyncReceiveDenied()
    }

    suspend fun applyPage(page: RemoteSyncPage): List<InboundResult> {
        requirePermission()
        require(page.accountKey == key && page.documents.size <= 100)
        require(page.parentPetId == null || validSyncPathId(page.parentPetId))
        require(page.documents.all {
            if (page.parentPetId == null) it.kind == "PET"
            else it.kind == "CARE" && it.petId == page.parentPetId
        })
        require(page.documents.map { it.entityId }.distinct().size == page.documents.size)
        require(!page.hasMore || page.documents.isNotEmpty())
        require(page.nextCursor == page.documents.lastOrNull()?.entityId)
        // Validate the entire page before mutating anything; invalid pages cannot be advanced.
        val decoded = page.documents.map(::validateRemoteDocument)
        return PetWorldMutationGate.mutex.withLock {
            requirePermission()
            database.withTransaction {
                val results = decoded.map {
                    requirePermission()
                    receive(it)
                }
                requirePermission()
                results
            }
        }
    }

    private suspend fun receive(remote: ValidatedRemote): InboundResult {
        val kind = remote.kind
        val petId = remote.petId
        val id = remote.entityId
        val exact = inbox.exact(key, kind, petId, id, remote.revision, remote.digest)
        if (exact != null && exact.status != "PARENT_PENDING") return InboundResult.DUPLICATE
        val latest = inbox.latest(key, kind, petId, id)
        if (latest != null && latest.revision > remote.revision) return InboundResult.STALE

        val localPet = if (kind == "PET") database.petDao().find(id) else null
        val localCare = if (kind == "CARE") database.careDao().find(id) else null
        val local = localPet?.let(FirstImportPayloads::pet) ?: localCare?.let(FirstImportPayloads::record)
        val previous = inbox.baseline(key, kind, petId, id)
        val hasPending = outbox.hasPending(key, kind, petId, id)
        val localPhoto = local?.get("photoFile")
        val equalLocal = local != null && sameSyncDomain(local, remote.fields)
        val result = when {
            latest != null && latest.revision == remote.revision &&
                latest.payloadDigest != remote.digest -> InboundResult.CONFLICT
            !remote.tombstone && inbox.hasTombstone(key, kind, petId, id) -> InboundResult.CONFLICT
            localCare != null && localCare.petId != petId -> InboundResult.CONFLICT
            remote.tombstone -> InboundResult.DELETE_REVIEW
            hasPending || inbox.hasUnresolved(key, kind, petId, id) -> InboundResult.CONFLICT
            remote.photoFile != null || localPhoto != null -> InboundResult.PHOTO_PENDING
            kind == "CARE" && !parentReady(petId) -> InboundResult.PARENT_PENDING
            equalLocal -> InboundResult.MATCHED
            local == null && previous == null && !outbox.hasHistory(key, kind, petId, id) -> {
                if (kind == "PET") database.petDao().insert(requireNotNull(remote.pet))
                else database.careDao().insert(requireNotNull(remote.care))
                InboundResult.APPLIED
            }
            local != null && previous != null &&
                remote.revision > previous.revision &&
                sameSyncDomain(local, parseSyncJson(previous.remoteJson)) -> {
                val previousFields = parseSyncJson(previous.remoteJson)
                if (previousFields["createdAtMs"] != remote.fields["createdAtMs"] ||
                    previousFields["type"] != remote.fields["type"]) {
                    InboundResult.CONFLICT
                } else {
                    if (kind == "PET") check(database.petDao().update(requireNotNull(remote.pet)) == 1)
                    else check(database.careDao().update(requireNotNull(remote.care)) == 1)
                    InboundResult.APPLIED
                }
            }
            else -> InboundResult.CONFLICT
        }
        val entry = SyncInboundEntry(
            accountKey = key, entityKind = kind, petId = petId, entityId = id,
            revision = remote.revision, payloadDigest = remote.digest, remoteJson = remote.json,
            localJson = local?.let(::canonicalSyncJson), tombstone = remote.tombstone, status = result.name,
        )
        if (exact == null) inbox.insert(entry)
        else check(inbox.update(entry.copy(sequence = exact.sequence)) == 1)
        return result
    }

    private suspend fun parentReady(petId: String): Boolean {
        if (outbox.hasPending(key, "PET", petId, petId) ||
            inbox.hasTombstone(key, "PET", petId, petId) ||
            inbox.hasUnresolved(key, "PET", petId, petId)) return false
        val parent = database.petDao().find(petId) ?: return false
        val baseline = inbox.baseline(key, "PET", petId, petId) ?: return false
        return sameSyncDomain(FirstImportPayloads.pet(parent), parseSyncJson(baseline.remoteJson))
    }
}
