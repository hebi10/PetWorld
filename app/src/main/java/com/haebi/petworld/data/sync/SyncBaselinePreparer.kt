package com.haebi.petworld.data.sync

import androidx.room.withTransaction
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.backup.PetWorldMutationGate
import com.haebi.petworld.data.migration.FirstImportPayloads
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.sync.withLock

/**
 * Promote only the FIFO head with verified absence or a matching accepted baseline.
 * Never infer revision from the device clock or from an unreviewed divergent remote value.
 */
internal class SyncBaselinePreparer(
    private val db: PetWorldDatabase,
    private val account: LocalAccountScope.SignedIn,
    private val source: SyncDocumentSource,
    private val permission: SyncPermission = SyncPermission { false },
    private val photos: SyncPhotoTransfer? = null,
) {
    private val outbox = db.syncOutboxDao()
    private val inbox = db.syncInboundDao()
    private suspend fun requirePermission() {
        if (!permission.permits(account)) throw SyncReceiveDenied()
    }

    suspend fun prepareHead(): SyncDispatch {
        requirePermission()
        val row = outbox.firstOutstanding(account.key) ?: return SyncDispatch.Idle
        if (row.status != SYNC_WAITING_BASELINE) return when (row.status) {
            "CONFLICT" -> SyncDispatch.Conflict
            "BLOCKED" -> SyncDispatch.Blocked
            "READY", "RETRY", "IN_FLIGHT" -> SyncDispatch.Acknowledged
            else -> SyncDispatch.Blocked
        }
        val document = source.read(account, row.entityKind, row.petId, row.entityId)
        requirePermission()
        val remote = document?.let {
            require(it.kind == row.entityKind && it.petId == row.petId && it.entityId == row.entityId)
            validateRemoteDocument(it)
        }
        val revision = remote?.revision ?: -1L
        val command = try { SyncCommands.forBaseline(row.copy(expectedRevision = revision)) }
            catch (_: IllegalArgumentException) { null }
            catch (_: IllegalStateException) { null }
        val exactMatch = command != null && ! (remote?.tombstone ?: false) &&
            decideSyncCommit(command, null, remote?.fields) is SyncRemoteDecision.Write

        // Re-read local journal in the transaction. Known revision may come from an inbound
        // baseline or the immutable acknowledged request; an old receipt never proves freshness.
        val canPromote = PetWorldMutationGate.mutex.withLock {
            requirePermission()
            db.withTransaction {
                if (outbox.firstOutstanding(account.key) != row) return@withTransaction SyncDispatch.Paused
                val prior = inbox.baseline(account.key, row.entityKind, row.petId, row.entityId)
                val ack = outbox.lastAcknowledged(account.key, row.entityKind, row.petId, row.entityId)
                    ?.let(SyncCommands::forBaseline)
                val knownRevision = maxOf(prior?.revision ?: -1L, ack?.resultingRevision ?: -1L)
                val knownFields = if (ack != null && ack.resultingRevision == knownRevision) ack.next
                    else prior?.let { parseSyncJson(it.remoteJson) }
                val unresolved = inbox.hasUnresolved(account.key, row.entityKind, row.petId, row.entityId)
                val deleted = inbox.hasTombstone(account.key, row.entityKind, row.petId, row.entityId)
                if (unresolved || deleted) return@withTransaction SyncDispatch.Conflict
                if (!exactMatch) return@withTransaction SyncDispatch.Conflict
                val verified = if (row.operation == "CREATE") {
                    remote == null && prior == null && ack == null
                } else {
                    remote != null && knownFields != null && knownRevision == revision &&
                        FirstImportPayloads.sameRemote(
                            knownFields - setOf("lastSyncOpId", "deletedAtMs"),
                            remote.fields - setOf("lastSyncOpId", "deletedAtMs"))
                }
                if (!verified) return@withTransaction SyncDispatch.WaitingBaseline
                if (command?.changesPhoto == true) return@withTransaction SyncDispatch.WaitingPhoto
                requirePermission()
                check(outbox.readyAfterVerifiedBaseline(account.key, row.opId, revision) == 1)
                requirePermission()
                SyncDispatch.Acknowledged
            }
        }
        if (canPromote == SyncDispatch.WaitingPhoto) {
            val transfer = photos ?: return SyncDispatch.WaitingPhoto
            val candidate = command ?: return SyncDispatch.WaitingPhoto
            val name = candidate.newPhotoReference
            try {
                if (name != null) transfer.upload(row, name)
                return PetWorldMutationGate.mutex.withLock {
                    requirePermission()
                    db.withTransaction {
                        if (outbox.firstOutstanding(account.key) != row)
                            return@withTransaction SyncDispatch.Paused
                        if (inbox.hasUnresolved(account.key, row.entityKind, row.petId, row.entityId) ||
                            inbox.hasTombstone(account.key, row.entityKind, row.petId, row.entityId))
                            return@withTransaction SyncDispatch.Conflict
                        if (name != null && transfer.verifiedProofUnderGate(
                                SyncPhotoKey(row.petId, name)) == null)
                            return@withTransaction SyncDispatch.WaitingPhoto
                        // The final server transaction still performs the exact revision/data CAS.
                        check(outbox.readyAfterVerifiedBaseline(account.key, row.opId, revision) == 1)
                        requirePermission()
                        SyncDispatch.Acknowledged
                    }
                }
            } catch (_: SyncPhotoRejected) {
                return SyncDispatch.WaitingPhoto
            }
        }
        if (canPromote != SyncDispatch.Conflict) return canPromote
        // Preserve an actual conflicting server snapshot in the inbound journal before
        // flagging the head. An absent document retains the complete local outbox snapshot.
        if (document != null) {
            SyncReceiver(db, account, permission).applyPage(
                RemoteSyncPage(account.key, if (row.entityKind == "CARE") row.petId else null,
                    listOf(document), document.entityId, false),
            ) {
                check(outbox.firstOutstanding(account.key) == row)
                check(outbox.flagBaselineConflict(account.key, row.opId) == 1)
            }
        } else {
            PetWorldMutationGate.mutex.withLock {
                requirePermission()
                db.withTransaction {
                    check(outbox.firstOutstanding(account.key) == row)
                    check(outbox.flagBaselineConflict(account.key, row.opId) == 1)
                    requirePermission()
                }
            }
        }
        return SyncDispatch.Conflict
    }

    suspend fun next(sender: SyncSender): SyncDispatch =
        when (val result = prepareHead()) {
            SyncDispatch.Acknowledged -> sender.runOnce(account)
            else -> result
        }
}
