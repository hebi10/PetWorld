package com.haebi.petworld.data.sync

import com.haebi.petworld.data.account.LocalAccountScope
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Only a later, verified baseline/consent controller may open a session.
 * Leases are process-local: queued work from a previous process never authorizes itself.
 */
internal data class SyncWorkLease(val accountKey: String, val generation: String)

internal data class VerifiedSyncSession(
    val account: LocalAccountScope.SignedIn,
    val baselineDigest: String,
    val consentConfirmed: Boolean,
    val liveServerQaPassed: Boolean,
)

internal class SyncSessionGate {
    private val active = MutableStateFlow<SyncWorkLease?>(null)
    val activeLease = active.asStateFlow()

    @Synchronized
    fun activate(session: VerifiedSyncSession): SyncWorkLease? {
        if (!session.consentConfirmed || !session.liveServerQaPassed ||
            !session.baselineDigest.matches(Regex("[0-9a-f]{64}"))) return null
        return SyncWorkLease(session.account.key, UUID.randomUUID().toString()).also { active.value = it }
    }

    @Synchronized
    fun permits(lease: SyncWorkLease, account: LocalAccountScope): Boolean =
        account is LocalAccountScope.SignedIn && lease.accountKey == account.key && active.value == lease

    @Synchronized
    fun revoke(): SyncWorkLease? = active.value.also { active.value = null }

    @Synchronized
    fun revokeUnlessAccount(account: LocalAccountScope): SyncWorkLease? {
        val key = (account as? LocalAccountScope.SignedIn)?.key
        return if (active.value != null && active.value?.accountKey != key) revoke() else null
    }
}

internal enum class SyncBatchOutcome {
    DRAINED, PAUSED, WAITING_BASELINE, BLOCKED, CONFLICT, RETRY, MORE_WORK
}

/** Bounded drain, no busy loop on a blocked head and no progress past conflict. */
internal suspend fun drainSyncBatch(
    limit: Int = 20,
    allowed: suspend () -> Boolean,
    next: suspend () -> SyncDispatch,
): SyncBatchOutcome {
    require(limit in 1..100)
    repeat(limit) {
        if (!allowed()) return SyncBatchOutcome.PAUSED
        when (next()) {
            SyncDispatch.Idle -> return SyncBatchOutcome.DRAINED
            SyncDispatch.Paused -> return SyncBatchOutcome.PAUSED
            SyncDispatch.WaitingBaseline -> return SyncBatchOutcome.WAITING_BASELINE
            SyncDispatch.Blocked, SyncDispatch.WaitingPhoto -> return SyncBatchOutcome.BLOCKED
            SyncDispatch.Conflict -> return SyncBatchOutcome.CONFLICT
            is SyncDispatch.Retry -> return SyncBatchOutcome.RETRY
            SyncDispatch.Acknowledged -> Unit
        }
    }
    return SyncBatchOutcome.MORE_WORK
}
