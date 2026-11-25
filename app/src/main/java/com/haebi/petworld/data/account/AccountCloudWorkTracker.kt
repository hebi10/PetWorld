package com.haebi.petworld.data.account

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal enum class CloudWorkKind { SYNC_WORKER, FIRST_IMPORT, SYNC_REVIEW, SDK_TASK }

internal data class AccountCloudWorkSnapshot(
    val blocked: Boolean,
    val workers: Int,
    val imports: Int,
    val sdkTasks: Int,
    val reviews: Int,
) {
    val drained: Boolean get() = blocked && workers == 0 && imports == 0 && sdkTasks == 0 && reviews == 0
}

/**
 * Process-local work admission and drain accounting. Block and acquire share one
 * monitor. A cancellation request is not evidence of an SDK Task completing.
 * A drained snapshot is not proof that legacy persistent caches are empty, or
 * that work in a prior process has ended.
 */
internal class AccountCloudWorkTracker {
    private class Entry {
        var blocked = false
        val counts = IntArray(CloudWorkKind.entries.size)
    }
    private val lock = Any()
    private val entries = mutableMapOf<LocalAccountScope.SignedIn, Entry>()
    private val changes = MutableStateFlow(0L)

    internal class Permit(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    fun tryBegin(account: LocalAccountScope.SignedIn, kind: CloudWorkKind): Permit? =
        synchronized(lock) {
            val entry = entries.getOrPut(account) { Entry() }
            if (entry.blocked) return@synchronized null
            check(entry.counts[kind.ordinal] < Int.MAX_VALUE)
            entry.counts[kind.ordinal]++
            changed()
            Permit {
                synchronized(lock) {
                    check(entry.counts[kind.ordinal] > 0)
                    entry.counts[kind.ordinal]--
                    if (!entry.blocked && entry.counts.all { it == 0 }) entries.remove(account)
                    changed()
                }
            }
        }

    /** Intentionally no unfreeze API. A future verified cleanup coordinator owns
     * a blocked process until its exit; cancellation must not implicitly reopen it. */
    fun blockNewWork(account: LocalAccountScope.SignedIn) = synchronized(lock) {
        val entry = entries.getOrPut(account) { Entry() }
        if (!entry.blocked) {
            entry.blocked = true
            changed()
        }
    }

    fun isBlocked(account: LocalAccountScope.SignedIn): Boolean =
        synchronized(lock) { entries[account]?.blocked == true }

    fun snapshot(account: LocalAccountScope.SignedIn): AccountCloudWorkSnapshot = synchronized(lock) {
        val entry = entries[account]
        AccountCloudWorkSnapshot(entry?.blocked == true,
            entry?.counts?.get(CloudWorkKind.SYNC_WORKER.ordinal) ?: 0,
            entry?.counts?.get(CloudWorkKind.FIRST_IMPORT.ordinal) ?: 0,
            entry?.counts?.get(CloudWorkKind.SDK_TASK.ordinal) ?: 0,
            entry?.counts?.get(CloudWorkKind.SYNC_REVIEW.ordinal) ?: 0)
    }

    suspend fun awaitDrained(account: LocalAccountScope.SignedIn, timeoutMs: Long): Boolean {
        require(timeoutMs in 1L..60_000L)
        if (!isBlocked(account)) return false
        return withTimeoutOrNull(timeoutMs) {
            changes.first { snapshot(account).drained }
            true
        } ?: false
    }

    private fun changed() { changes.value = changes.value + 1 }
}
