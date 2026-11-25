package com.haebi.petworld.data.account

import kotlinx.coroutines.CancellationException
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
) : DeletionRecoveryEnrollment {
    private val gate = Mutex()

    private fun allowed(scope: LocalAccountScope): Boolean =
        foreground() && currentScope() == scope

    override suspend fun enroll(anchor: DeletionRecoveryAnchor): Boolean = gate.withLock {
        try {
            if (!allowed(anchor.account) || !confirmDeviceCredential() || !allowed(anchor.account))
                return@withLock false
            val old = anchors.read()
            if (old != null && old != anchor) return@withLock false
            allowed(anchor.account) && anchors.retain(anchor) &&
                anchors.read() == anchor && allowed(anchor.account)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    suspend fun refresh(
        recovery: DeletionRecoveryController,
        verifiedEndpoint: Boolean,
    ): AccountDeletionStatusView {
        val guest = LocalAccountScope.Guest
        return try {
            // Release the enrollment mutex before acquiring the controller mutex.
            // Enrollment is immutable; no other account can replace the selected slot.
            val anchor = gate.withLock {
                if (!verifiedEndpoint || !allowed(guest) ||
                    !confirmDeviceCredential() || !allowed(guest)) return@withLock null
                anchors.read()?.takeIf { allowed(guest) }
            } ?: return AccountDeletionStatusView.Unknown
            val result = recovery.refresh(anchor.account, verifiedEndpoint, anchor.jobId) { allowed(guest) }
            if (!allowed(guest)) return AccountDeletionStatusView.Unknown
            if (result is DeletionRecoveryResult.Confirmed)
                AccountDeletionStatusView.Saved(result.receipt.phase)
            else AccountDeletionStatusView.Unknown
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { AccountDeletionStatusView.Unknown }
    }
}
