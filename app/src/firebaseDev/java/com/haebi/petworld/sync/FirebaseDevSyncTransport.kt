package com.haebi.petworld.sync

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestoreException
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.AccountDeletionJournal
import com.haebi.petworld.auth.awaitTrackedFirebaseTask
import com.haebi.petworld.auth.FirebaseDevAccountSdkSessions
import com.haebi.petworld.auth.FirebaseDevCloudWork
import com.haebi.petworld.data.sync.SyncCommand
import com.haebi.petworld.data.sync.SyncCommitOutcome
import com.haebi.petworld.data.sync.SyncPermission
import com.haebi.petworld.data.sync.SyncRemoteDecision
import com.haebi.petworld.data.sync.SyncTransport
import com.haebi.petworld.data.sync.decideSyncCommit
import com.haebi.petworld.data.migration.FirstImportPayloads
import kotlinx.coroutines.CancellationException

/**
 * Development-only server transaction. No caller or scheduling registration exists yet.
 * A verified consent/baseline/restore guard must explicitly permit each account operation.
 */
internal class FirebaseDevSyncTransport(
    context: Context,
    private val permission: SyncPermission = SyncPermission { false },
) : SyncTransport {
    private val journal = AccountDeletionJournal(context.applicationContext)
    private val binding = FirebaseDevAccountSdkSessions.binding(context.applicationContext)
    private val app = binding.app
    private val auth = binding.auth
    private val firestore get() = binding.firestore

    init {
        check(context.applicationContext.packageName == "com.haebi.petworld.dev")
        check(app.options.projectId == "petworld-dev")
    }

    private fun currentUid(command: SyncCommand): String? {
        val user = auth.currentUser ?: return null
        val scope = LocalAccountScope.fromFirebaseUid(user.uid) as? LocalAccountScope.SignedIn ?: return null
        return binding.currentUid(scope)?.takeIf { scope.key == command.accountKey }
    }

    override suspend fun commit(command: SyncCommand): SyncCommitOutcome {
        val uid = currentUid(command) ?: return SyncCommitOutcome.Blocked
        val account = LocalAccountScope.fromFirebaseUid(uid) as? LocalAccountScope.SignedIn
            ?: return SyncCommitOutcome.Blocked
        if (!permission.permits(account)) return SyncCommitOutcome.Blocked
        val newName = command.newPhotoReference
        val proof = command.photoProof
        if ((newName != null && (proof == null || proof.fileName != newName || proof.petId != command.petId)) ||
            (newName == null && proof != null)) return SyncCommitOutcome.Blocked
        return try {
            // Require online credential refresh before using the server transaction.
            val user = auth.currentUser ?: return SyncCommitOutcome.Blocked
            if (user.uid != uid) return SyncCommitOutcome.Blocked
            sdk(account, command, uid) { user.getIdToken(true) }
            if (currentUid(command) != uid || !permission.permits(account)) {
                return SyncCommitOutcome.Blocked
            }
            val userRef = firestore.collection("users").document(uid)
            val petRef = userRef.collection("pets").document(command.petId)
            val target = when (command.entityKind) {
                "PET" -> petRef
                "CARE" -> petRef.collection("records").document(command.entityId)
                else -> return SyncCommitOutcome.Blocked
            }
            val receipt = userRef.collection("syncReceipts").document(command.opId)
            val outcome = sdk(account, command, uid) { firestore.runTransaction { tx ->
                if (!stillAllowed(account, command, uid)) return@runTransaction SyncCommitOutcome.Blocked
                val owner = tx.get(userRef)
                if (owner.getString("migrationState") != "ready") {
                    return@runTransaction SyncCommitOutcome.Blocked
                }
                val previousReceipt = tx.get(receipt).data
                if (previousReceipt != null) {
                    return@runTransaction (decideSyncCommit(command, previousReceipt, null)
                        as SyncRemoteDecision.Stop).result
                }
                if (command.entityKind == "CARE") {
                    val parent = tx.get(petRef)
                    if (!parent.exists() || parent.get("deletedAtMs") != null) {
                        return@runTransaction SyncCommitOutcome.Conflict
                    }
                }
                val current = tx.get(target).data
                when (val decision = decideSyncCommit(command, null, current)) {
                    is SyncRemoteDecision.Stop -> decision.result
                    is SyncRemoteDecision.Write -> {
                        val manifestRef = proof?.let { petRef.collection("photoManifests").document(it.fileName) }
                        val manifest = proof?.manifest()
                        val existingManifest = manifestRef?.let { tx.get(it).data }
                        if (existingManifest != null && !FirstImportPayloads.sameRemote(
                                requireNotNull(manifest), existingManifest))
                            return@runTransaction SyncCommitOutcome.Conflict
                        if (!stillAllowed(account, command, uid)) {
                            return@runTransaction SyncCommitOutcome.Blocked
                        }
                        if (manifestRef != null && existingManifest == null)
                            tx.set(manifestRef, requireNotNull(manifest))
                        tx.set(target, decision.document)
                        tx.set(receipt, decision.receipt)
                        SyncCommitOutcome.Applied
                    }
                }
            } }
            if (currentUid(command) != uid || !permission.permits(account)) {
                // The transaction may already be committed. Keep the same opId replayable.
                SyncCommitOutcome.Retry
            } else outcome
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: FirebaseFirestoreException) {
            when (failure.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED,
                FirebaseFirestoreException.Code.INVALID_ARGUMENT,
                FirebaseFirestoreException.Code.FAILED_PRECONDITION -> SyncCommitOutcome.Blocked
                else -> SyncCommitOutcome.Retry
            }
        } catch (_: Exception) {
            SyncCommitOutcome.Retry
        }
    }
    private fun stillAllowed(account: LocalAccountScope.SignedIn, command: SyncCommand, uid: String): Boolean =
        currentUid(command) == uid && !journal.blocksCloud(account) && !FirebaseDevCloudWork.tracker.isBlocked(account)

    private suspend fun <T> sdk(account: LocalAccountScope.SignedIn, command: SyncCommand, uid: String,
        start: () -> Task<T>): T {
        check(permission.permits(account))
        return awaitTrackedFirebaseTask(account, permitted = { stillAllowed(account, command, uid) }, start = start)
    }
}
