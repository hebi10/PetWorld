package com.haebi.petworld.data.sync

import androidx.room.withTransaction
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface SyncTransport {
    suspend fun commit(command: SyncCommand): SyncCommitOutcome
}

/** The future verified-session controller must supply an account/consent/baseline/restore guard. */
internal fun interface SyncPermission {
    suspend fun permits(account: LocalAccountScope.SignedIn): Boolean
}

internal sealed interface SyncDispatch {
    data object Idle : SyncDispatch
    data object WaitingBaseline : SyncDispatch
    data object Paused : SyncDispatch
    data object Conflict : SyncDispatch
    data object Blocked : SyncDispatch
    data object Acknowledged : SyncDispatch
    data class Retry(val delayMillis: Long) : SyncDispatch
}

internal fun retryDelayMillis(attempt: Int): Long =
    minOf(6L * 60 * 60 * 1000, 30_000L * (1L shl (attempt - 1).coerceIn(0, 20)))

/**
 * Process one durable FIFO entry. Nothing schedules or enables this sender in FB-05-B.
 * The network implementation must enforce the same account and atomic remote receipt contract.
 */
internal class SyncSender(
    private val database: PetWorldDatabase,
    private val transport: SyncTransport,
    private val permission: SyncPermission = SyncPermission { false },
) {
    private val dao = database.syncOutboxDao()

    suspend fun runOnce(account: LocalAccountScope): SyncDispatch = gate.withLock {
        val signedIn = account as? LocalAccountScope.SignedIn ?: return@withLock SyncDispatch.Paused
        if (!permission.permits(signedIn)) return@withLock SyncDispatch.Paused
        val row = dao.firstOutstanding(signedIn.key) ?: return@withLock SyncDispatch.Idle
        when (row.status) {
            SYNC_WAITING_BASELINE -> return@withLock SyncDispatch.WaitingBaseline
            "CONFLICT" -> return@withLock SyncDispatch.Conflict
            "BLOCKED" -> return@withLock SyncDispatch.Blocked
            "READY", "RETRY", "IN_FLIGHT" -> Unit
            else -> return@withLock SyncDispatch.Blocked
        }
        if (row.expectedRevision == null || row.attempt >= 100) return@withLock SyncDispatch.Blocked
        val claimed = database.withTransaction {
            if (dao.beginAttempt(signedIn.key, row.opId, row.status, row.attempt) == 1)
                row.copy(status = "IN_FLIGHT", attempt = row.attempt + 1)
            else null
        } ?: return@withLock SyncDispatch.Paused

        // Recheck after durable claim; logout or restore keeps this entry replayable.
        if (!permission.permits(signedIn)) return@withLock SyncDispatch.Paused
        val command = try {
            SyncCommands.from(claimed)
        } catch (_: Exception) {
            if (permission.permits(signedIn)) {
                dao.finishAttempt(signedIn.key, claimed.opId, claimed.attempt, "BLOCKED")
            }
            return@withLock SyncDispatch.Blocked
        }
        val outcome = try {
            transport.commit(command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unknown response: never assume that the server did not commit.
            SyncCommitOutcome.Retry
        }
        if (!permission.permits(signedIn)) return@withLock SyncDispatch.Paused
        val (status, result) = when (outcome) {
            SyncCommitOutcome.Applied -> "ACKNOWLEDGED" to SyncDispatch.Acknowledged
            SyncCommitOutcome.Conflict -> "CONFLICT" to SyncDispatch.Conflict
            SyncCommitOutcome.Blocked -> "BLOCKED" to SyncDispatch.Blocked
            SyncCommitOutcome.Retry -> "RETRY" to SyncDispatch.Retry(retryDelayMillis(claimed.attempt))
        }
        check(dao.finishAttempt(signedIn.key, claimed.opId, claimed.attempt, status) == 1) {
            "Sync attempt changed; do not advance queue"
        }
        result
    }

    private companion object {
        val gate = Mutex()
    }
}
