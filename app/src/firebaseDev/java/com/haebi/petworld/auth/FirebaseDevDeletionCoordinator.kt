package com.haebi.petworld.auth

import android.app.Activity
import com.haebi.petworld.data.account.AccountDeletionReadiness
import com.haebi.petworld.data.account.DeletionRecoveryController
import com.haebi.petworld.data.account.DeletionRecoveryResult
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.deletionJobId
import com.haebi.petworld.sync.FirebaseDevSyncWork
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** No current UI or runtime factory constructs this coordinator.
 * Readiness is trusted bootstrap evidence, not a value read from a user or Intent.
 * Post-sign-out recovery is deliberately not exposed until a device-authenticated
 * ticket-selection policy exists.
 */
internal class FirebaseDevDeletionCoordinator(
    private val activity: Activity,
    private val gateway: FirebaseGoogleAuthGateway,
    private val recovery: DeletionRecoveryController,
    private val readiness: AccountDeletionReadiness,
) {
    private val gate = Mutex()

    private fun currentScope(): LocalAccountScope? =
        gateway.currentAccount()?.uid?.let(LocalAccountScope::fromFirebaseUid)

    suspend fun submit(account: LocalAccountScope.SignedIn, acknowledged: Boolean): DeletionRecoveryResult = gate.withLock {
        if (!readiness.canRequest(account, acknowledged, true) || currentScope() != account)
            return@withLock DeletionRecoveryResult.NotReady
        val uid = gateway.currentAccount()?.uid ?: return@withLock DeletionRecoveryResult.NotReady
        if (LocalAccountScope.fromFirebaseUid(uid) != account) return@withLock DeletionRecoveryResult.NotReady
        FirebaseDevSyncWork.revoke(activity)
        val auth = gateway.reauthenticateForDeletion(activity, uid)
            ?: return@withLock DeletionRecoveryResult.NotReady
        val now = System.currentTimeMillis() / 1000
        if (auth.uid != uid || currentScope() != account ||
            auth.authTimeSeconds > now || now - auth.authTimeSeconds > 300)
            return@withLock DeletionRecoveryResult.NotReady
        recovery.submit(uid, auth.idToken, acknowledged, true, readiness, ::currentScope)
    }

    suspend fun refresh(account: LocalAccountScope.SignedIn): DeletionRecoveryResult = gate.withLock {
        if (!readiness.verifiedEndpoint || !readiness.secureStatusTicket || currentScope() != account)
            return@withLock DeletionRecoveryResult.NotReady
        val result = recovery.refresh(account, verifiedEndpoint = true, expectedJobId =
            gateway.currentAccount()?.uid?.let(::deletionJobId)) { currentScope() == account }
        if (currentScope() != account) DeletionRecoveryResult.StateUnknown else result
    }
}
