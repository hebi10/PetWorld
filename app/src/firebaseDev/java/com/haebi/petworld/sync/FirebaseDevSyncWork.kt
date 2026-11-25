package com.haebi.petworld.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.backup.PetWorldRecoveryJournal
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.data.sync.SyncBatchOutcome
import com.haebi.petworld.data.sync.SyncBaselinePreparer
import com.haebi.petworld.data.sync.SyncDocumentSource
import com.haebi.petworld.data.sync.SyncPageSource
import com.haebi.petworld.data.sync.SyncScanCoordinator
import com.haebi.petworld.data.sync.ScanStep
import com.haebi.petworld.data.sync.SyncPermission
import com.haebi.petworld.data.sync.SyncPhotoTransfer
import com.haebi.petworld.data.sync.SyncSender
import com.haebi.petworld.data.sync.SyncSessionGate
import com.haebi.petworld.data.sync.SyncWorkLease
import com.haebi.petworld.data.sync.drainSyncBatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

private const val KEY_ACCOUNT = "account_key"
private const val KEY_GENERATION = "generation"

/**
 * Development-only scheduler. No production caller activates the lease or enqueues work.
 * WorkManager's persisted Data is a routing hint, not a persisted consent or authentication grant.
 */
internal object FirebaseDevSyncWork {
    internal val gate = SyncSessionGate()

    private fun uniqueName(lease: SyncWorkLease) =
        "petworld-sync-${lease.accountKey}-${lease.generation}"

    internal fun account(context: Context): LocalAccountScope.SignedIn? {
        if (context.applicationContext.packageName != "com.haebi.petworld.dev") return null
        return runCatching {
            val app = FirebaseApp.getInstance()
            if (app.options.projectId != "petworld-dev") return null
            val user = FirebaseAuth.getInstance(app).currentUser ?: return null
            if (user.isAnonymous || user.providerData.none { it.providerId == "google.com" }) return null
            LocalAccountScope.fromFirebaseUid(user.uid) as? LocalAccountScope.SignedIn
        }.getOrNull()
    }

    internal fun allowed(context: Context, lease: SyncWorkLease, account: LocalAccountScope): Boolean =
        gate.permits(lease, account) &&
            account(context) == account &&
            !PetWorldRecoveryJournal(context.applicationContext).hasPending()

    fun enqueue(context: Context, lease: SyncWorkLease): Boolean {
        val app = context.applicationContext
        val account = account(app) ?: return false
        if (!allowed(app, lease, account)) return false
        val request = OneTimeWorkRequestBuilder<FirebaseDevSyncWorker>()
            .setInputData(workDataOf(KEY_ACCOUNT to lease.accountKey, KEY_GENERATION to lease.generation))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP preserves one outstanding work chain per verified account session.
        WorkManager.getInstance(app).enqueueUniqueWork(uniqueName(lease), ExistingWorkPolicy.KEEP, request)
        // A logout racing with enqueue is still denied by the worker's lease checks.
        if (!allowed(app, lease, account)) {
            cancel(app, lease)
            return false
        }
        return true
    }

    fun revoke(context: Context) {
        gate.revoke()?.let { cancel(context.applicationContext, it) }
    }

    fun revokeUnlessAccount(context: Context, account: LocalAccountScope) {
        gate.revokeUnlessAccount(account)?.let { cancel(context.applicationContext, it) }
    }

    private fun cancel(context: Context, lease: SyncWorkLease) {
        // Invalidation is synchronous; cancellation is only a best-effort scheduler cleanup.
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(uniqueName(lease)) }
    }
}

/** WorkManager reflectively constructs this class. The worker cannot grant itself a lease. */
class FirebaseDevSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_ACCOUNT) ?: return Result.success()
        val generation = inputData.getString(KEY_GENERATION) ?: return Result.success()
        val lease = SyncWorkLease(key, generation)
        val app = applicationContext
        val account = FirebaseDevSyncWork.account(app) ?: return Result.success()
        fun allowed() = !isStopped && FirebaseDevSyncWork.allowed(app, lease, account)
        if (!allowed()) return Result.success()
        val permission = SyncPermission { candidate -> candidate == account && allowed() }
        return try {
            val db = PetWorldDatabase.get(app, account)
            val reader = FirebaseDevRemoteReader(app, permission)
            val photos = SyncPhotoTransfer(app, db, account, FirebaseDevPhotoStorage(app, account, permission), permission)
            val sender = SyncSender(db, FirebaseDevSyncTransport(app, permission), permission, photos)
            val preparer = SyncBaselinePreparer(db, account,
                SyncDocumentSource { scope, kind, pet, id -> reader.readDocument(scope, kind, pet, id) },
                permission, photos)
            val outcome = drainSyncBatch(allowed = { allowed() }, next = { preparer.next(sender) })
            if (!allowed() || outcome == SyncBatchOutcome.PAUSED) return Result.success()
            if (outcome == SyncBatchOutcome.RETRY || outcome == SyncBatchOutcome.MORE_WORK) {
                return Result.retry()
            }
            // Receiving may continue while the FIFO sender is blocked; never skip a conflicted send.
            val scan = SyncScanCoordinator(db, account,
                SyncPageSource { scope, parent, after, limit -> reader.readPage(scope, parent, after, limit) },
                permission, photos)
            repeat(10) {
                if (!allowed()) return Result.success()
                if (scan.step() == ScanStep.COMPLETE) return Result.success()
            }
            Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Account switch can close the old Room connection. Never touch the new account DB.
            if (allowed()) Result.retry() else Result.success()
        }
    }
}
