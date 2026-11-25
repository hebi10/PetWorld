package com.haebi.petworld.data.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exactly one explicitly enrolled recovery slot. No account discovery or ticket listing. */
internal data class DeletionRecoveryAnchor(
    val account: LocalAccountScope.SignedIn,
    val jobId: String,
) {
    init { require(jobId.matches(Regex("[0-9a-f]{64}"))) }
    override fun toString() = "DeletionRecoveryAnchor(redacted)"
}

internal interface DeletionRecoveryAnchorStore {
    fun read(): DeletionRecoveryAnchor?
    /** Create only if absent, or confirm the exact existing anchor. Never replace another account. */
    fun retain(anchor: DeletionRecoveryAnchor): Boolean
}

internal fun interface DeletionRecoveryEnrollment {
    suspend fun enroll(anchor: DeletionRecoveryAnchor): Boolean
}

/**
 * Device credential is local authority on this device, not proof of Google identity.
 * Enrollment is offered only after an explicitly acknowledged, freshly reauthenticated
 * request. Signed-out recovery can address only that enrolled anchor, after another
 * interactive device credential confirmation. No new request or local deletion.
 */
internal class DeviceDeletionRecoveryAccess(
    private val anchors: DeletionRecoveryAnchorStore,
    private val confirmDeviceCredential: suspend () -> Boolean,
    private val currentScope: () -> LocalAccountScope?,
    private val foreground: () -> Boolean,
    private val sessionVersion: () -> Long,
) : DeletionRecoveryEnrollment {
    private val gate = Mutex()

    private fun allowed(scope: LocalAccountScope, version: Long): Boolean =
        foreground() && sessionVersion() == version && currentScope() == scope

    override suspend fun enroll(anchor: DeletionRecoveryAnchor): Boolean = gate.withLock {
        try {
            val version = sessionVersion()
            if (!allowed(anchor.account, version) || !confirmDeviceCredential() || !allowed(anchor.account, version))
                return@withLock false
            val old = anchors.read()
            if (old != null && old != anchor) return@withLock false
            allowed(anchor.account, version) && anchors.retain(anchor) &&
                anchors.read() == anchor && allowed(anchor.account, version)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    /**
     * Read only the last locally saved receipt. No network, no account list and
     * no credential-bearing value reaches presentation. Absent, denied and
     * corrupt slots are deliberately indistinguishable to the guest UI.
     */
    suspend fun readSaved(store: DeletionStateStore): AccountDeletionStatusView = gate.withLock {
        val guest = LocalAccountScope.Guest
        try {
            val version = sessionVersion()
            if (!allowed(guest, version) || !confirmDeviceCredential() || !allowed(guest, version))
                return@withLock AccountDeletionStatusView.Unknown
            val result = withContext(Dispatchers.IO) {
                val anchor = anchors.read() ?: return@withContext AccountDeletionStatusView.Unknown
                val state = store.read(anchor.account)
                if (state == null || state.account != anchor.account || state.jobId != anchor.jobId ||
                    !store.blocksCloud(anchor.account)) return@withContext AccountDeletionStatusView.Unknown
                state.receipt?.let { AccountDeletionStatusView.Saved(it.phase) }
                    ?: AccountDeletionStatusView.Unknown
            }
            if (allowed(guest, version)) result else AccountDeletionStatusView.Unknown
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { AccountDeletionStatusView.Unknown }
    }

    suspend fun refresh(
        recovery: DeletionRecoveryController,
        verifiedEndpoint: Boolean,
    ): AccountDeletionStatusView {
        val guest = LocalAccountScope.Guest
        return try {
            val version = sessionVersion()
            // Release the enrollment mutex before acquiring the controller mutex.
            // Enrollment is immutable; no other account can replace the selected slot.
            val anchor = gate.withLock {
                if (!verifiedEndpoint || !allowed(guest, version) ||
                    !confirmDeviceCredential() || !allowed(guest, version)) return@withLock null
                anchors.read()?.takeIf { allowed(guest, version) }
            } ?: return AccountDeletionStatusView.Unknown
            val result = recovery.refresh(anchor.account, verifiedEndpoint, anchor.jobId) { allowed(guest, version) }
            if (!allowed(guest, version)) return AccountDeletionStatusView.Unknown
            if (result is DeletionRecoveryResult.Confirmed)
                AccountDeletionStatusView.Saved(result.receipt.phase)
            else AccountDeletionStatusView.Unknown
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { AccountDeletionStatusView.Unknown }
    }
}
