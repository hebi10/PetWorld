package com.haebi.petworld.sync

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.sync.SyncCommand
import com.haebi.petworld.data.sync.SyncCommitOutcome
import com.haebi.petworld.data.sync.SyncPermission
import com.haebi.petworld.data.sync.SyncRemoteDecision
import com.haebi.petworld.data.sync.SyncTransport
import com.haebi.petworld.data.sync.decideSyncCommit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Development-only server transaction. No caller or scheduling registration exists yet.
 * A verified consent/baseline/restore guard must explicitly permit each account operation.
 */
internal class FirebaseDevSyncTransport(
    context: Context,
    private val permission: SyncPermission = SyncPermission { false },
) : SyncTransport {
    private val app = FirebaseApp.getInstance()
    private val auth = FirebaseAuth.getInstance(app)
    private val firestore = FirebaseFirestore.getInstance(app)

    init {
        check(context.applicationContext.packageName == "com.haebi.petworld.dev")
        check(app.options.projectId == "petworld-dev")
    }

    private fun currentUid(command: SyncCommand): String? {
        val user = auth.currentUser ?: return null
        if (user.isAnonymous || user.providerData.none { it.providerId == "google.com" }) return null
        val scope = LocalAccountScope.fromFirebaseUid(user.uid) as? LocalAccountScope.SignedIn ?: return null
        return user.uid.takeIf { scope.key == command.accountKey }
    }

    override suspend fun commit(command: SyncCommand): SyncCommitOutcome {
        val uid = currentUid(command) ?: return SyncCommitOutcome.Blocked
        val account = LocalAccountScope.fromFirebaseUid(uid) as? LocalAccountScope.SignedIn
            ?: return SyncCommitOutcome.Blocked
        if (!permission.permits(account)) return SyncCommitOutcome.Blocked
        return try {
            // Require online credential refresh before using the server transaction.
            val user = auth.currentUser ?: return SyncCommitOutcome.Blocked
            if (user.uid != uid) return SyncCommitOutcome.Blocked
            user.getIdToken(true).awaitSync()
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
            val outcome = firestore.runTransaction { tx ->
                if (currentUid(command) != uid) return@runTransaction SyncCommitOutcome.Blocked
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
                        if (currentUid(command) != uid) {
                            return@runTransaction SyncCommitOutcome.Blocked
                        }
                        tx.set(target, decision.document)
                        tx.set(receipt, decision.receipt)
                        SyncCommitOutcome.Applied
                    }
                }
            }.awaitSync()
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
}

private suspend fun <T> Task<T>.awaitSync(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase task failed"))
    }
}
