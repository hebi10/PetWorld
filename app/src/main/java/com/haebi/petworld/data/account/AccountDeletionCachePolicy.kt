package com.haebi.petworld.data.account

/** Shared persistence is never equivalent to account-scoped erasure. */
internal enum class SharedSdkCacheDisposition {
    UNVERIFIED_SHARED,
    VERIFIED_ACCOUNT_ISOLATED_AND_CLEARED,
    VERIFIED_NO_TARGET_REMAINS,
}

/**
 * Trusted, account-bound evidence from a future lifecycle/SDK drain coordinator.
 * Cancellation of a coroutine, revoking a sync lease or closing Room alone
 * cannot demonstrate that SDK requests, image work and listeners have stopped.
 */
internal data class AccountDeletionQuiescenceEvidence(
    val account: LocalAccountScope.SignedIn,
    val currentScope: LocalAccountScope? = null,
    val newCloudWorkBlocked: Boolean = false,
    val syncWorkerDrained: Boolean = false,
    val firstImportDrained: Boolean = false,
    val sdkRequestsDrained: Boolean = false,
    val listenersDetached: Boolean = false,
    val oldViewModelsReleased: Boolean = false,
    val imageWorkDrained: Boolean = false,
    val roomScopeClosed: Boolean = false,
    val sharedSdkCache: SharedSdkCacheDisposition = SharedSdkCacheDisposition.UNVERIFIED_SHARED,
    val otherAccountsAndGuestPreserved: Boolean = false,
) {
    fun permits(target: LocalAccountScope.SignedIn): Boolean =
        target == account && currentScope == LocalAccountScope.Guest &&
            newCloudWorkBlocked && syncWorkerDrained && firstImportDrained &&
            sdkRequestsDrained && listenersDetached && oldViewModelsReleased &&
            imageWorkDrained && roomScopeClosed && otherAccountsAndGuestPreserved &&
            sharedSdkCache != SharedSdkCacheDisposition.UNVERIFIED_SHARED
}

/** No verified SDK drain evidence exists yet. This is a fail-closed adapter
 * for the existing scoped cleanup; it never clears all Firestore persistence. */
internal class VerifiedAccountStorageQuiescence(
    private val evidence: (LocalAccountScope.SignedIn) -> AccountDeletionQuiescenceEvidence?,
) : AccountStorageQuiescence {
    override fun isQuiescent(account: LocalAccountScope.SignedIn): Boolean =
        runCatching { evidence(account)?.permits(account) == true }.getOrDefault(false)
}
