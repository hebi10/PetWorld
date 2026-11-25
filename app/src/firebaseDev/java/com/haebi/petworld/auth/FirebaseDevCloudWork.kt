package com.haebi.petworld.auth

import com.google.android.gms.tasks.Task
import com.google.firebase.storage.StorageTask
import com.haebi.petworld.data.account.AccountCloudWorkTracker
import com.haebi.petworld.data.account.CloudWorkKind
import com.haebi.petworld.data.account.LocalAccountScope
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal object FirebaseDevCloudWork {
    val tracker = AccountCloudWorkTracker()
}

/** Start lazily after acquiring the account permit. Only the actual SDK completion
 * callback releases the permit. A cancelled coroutine may leave the SDK running. */
internal suspend fun <T> awaitTrackedFirebaseTask(
    account: LocalAccountScope.SignedIn,
    tracker: AccountCloudWorkTracker = FirebaseDevCloudWork.tracker,
    permitted: () -> Boolean,
    cancelStorage: Boolean = false,
    start: () -> Task<T>,
): T = suspendCancellableCoroutine { continuation ->
    val permit = tracker.tryBegin(account, CloudWorkKind.SDK_TASK)
    if (permit == null) {
        continuation.resumeWithException(IllegalStateException("Account cloud work is blocked"))
        return@suspendCancellableCoroutine
    }
    val allowed = runCatching { permitted() }.getOrDefault(false)
    if (!continuation.isActive || !allowed || tracker.isBlocked(account)) {
        permit.close()
        if (continuation.isActive)
            continuation.resumeWithException(IllegalStateException("Account cloud work is unavailable"))
        return@suspendCancellableCoroutine
    }

    // Once start is attempted, a synchronous exception or failed listener
    // registration leaves a conservative unresolved permit: never infer drain.
    try {
        val task = start()
        task.addOnCompleteListener(Executor { it.run() }) { completed ->
            permit.close()
            if (continuation.isActive) {
                if (completed.isSuccessful) continuation.resume(completed.result)
                else continuation.resumeWithException(
                    completed.exception ?: IllegalStateException("Firebase task did not complete successfully"))
            }
        }
        if (cancelStorage && task is StorageTask<*>) {
            continuation.invokeOnCancellation { runCatching { task.cancel() } }
        }
    } catch (failure: Exception) {
        if (continuation.isActive) continuation.resumeWithException(failure)
    }
}
