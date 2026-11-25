package com.haebi.petworld.data.account

/** Explicit installation audit, not an inference from an app version or empty directory. */
internal enum class LegacyFirestoreCacheState {
    UNKNOWN,
    VERIFIED_ABSENT,
    RECONCILED_AND_RETAINED,
}

internal enum class AccountCacheTransitionDecision {
    KEEP_EXISTING,
    PREPARE_ISOLATED_MEMORY_CLIENT,
}

/**
 * Review evidence for a future account-bound SDK bootstrap. Existing shared
 * persistence remains untouched even if a new memory client can be prepared.
 * This evidence is NOT authorization to delete an account or its cached files.
 */
internal data class AccountCacheTransitionEvidence(
    val account: LocalAccountScope.SignedIn,
    val legacyState: LegacyFirestoreCacheState = LegacyFirestoreCacheState.UNKNOWN,
    val allLegacyPendingWritesReconciled: Boolean = false,
    val legacyAndOtherAccountDataPreserved: Boolean = false,
    val durableOutboxVerified: Boolean = false,
    val previousCloudWorkDrained: Boolean = false,
    val isolatedNamespaceVerified: Boolean = false,
    val beforeSdkFirstUse: Boolean = false,
) {
    fun decide(target: LocalAccountScope.SignedIn): AccountCacheTransitionDecision =
        if (target == account && legacyState != LegacyFirestoreCacheState.UNKNOWN &&
            allLegacyPendingWritesReconciled && legacyAndOtherAccountDataPreserved &&
            durableOutboxVerified && previousCloudWorkDrained &&
            isolatedNamespaceVerified && beforeSdkFirstUse)
            AccountCacheTransitionDecision.PREPARE_ISOLATED_MEMORY_CLIENT
        else AccountCacheTransitionDecision.KEEP_EXISTING
}

internal fun accountMemoryAppName(account: LocalAccountScope.SignedIn): String =
    "petworld-account-memory-v1-${account.key}"
