package com.haebi.petworld.data.account

import org.junit.Assert.*
import org.junit.Test

class AccountCacheTransitionPolicyTest {
    private val a = LocalAccountScope.fromFirebaseUid("qa-a") as LocalAccountScope.SignedIn
    private val b = LocalAccountScope.fromFirebaseUid("qa-b") as LocalAccountScope.SignedIn
    private val reviewed = AccountCacheTransitionEvidence(a,
        legacyState = LegacyFirestoreCacheState.RECONCILED_AND_RETAINED,
        allLegacyPendingWritesReconciled = true,
        legacyAndOtherAccountDataPreserved = true,
        durableOutboxVerified = true,
        previousCloudWorkDrained = true,
        isolatedNamespaceVerified = true,
        beforeSdkFirstUse = true)

    @Test fun unknownLegacyStateDoesNotAuthorizeTransition() {
        assertEquals(AccountCacheTransitionDecision.KEEP_EXISTING,
            AccountCacheTransitionEvidence(a).decide(a))
        assertEquals(AccountCacheTransitionDecision.KEEP_EXISTING,
            reviewed.copy(legacyState = LegacyFirestoreCacheState.UNKNOWN).decide(a))
    }

    @Test fun pendingWritesAndOtherAccountDataMustBePreserved() {
        for (evidence in listOf(
            reviewed.copy(allLegacyPendingWritesReconciled = false),
            reviewed.copy(legacyAndOtherAccountDataPreserved = false),
            reviewed.copy(durableOutboxVerified = false),
            reviewed.copy(previousCloudWorkDrained = false),
            reviewed.copy(isolatedNamespaceVerified = false),
            reviewed.copy(beforeSdkFirstUse = false),
        )) assertEquals(AccountCacheTransitionDecision.KEEP_EXISTING, evidence.decide(a))
    }

    @Test fun verifiedInstallationAuditAllowsOnlyNewClientPreparationForExactAccount() {
        assertEquals(AccountCacheTransitionDecision.PREPARE_ISOLATED_MEMORY_CLIENT, reviewed.decide(a))
        assertEquals(AccountCacheTransitionDecision.KEEP_EXISTING, reviewed.decide(b))
        assertEquals(AccountCacheTransitionDecision.PREPARE_ISOLATED_MEMORY_CLIENT,
            reviewed.copy(legacyState = LegacyFirestoreCacheState.VERIFIED_ABSENT).decide(a))
        assertNotEquals(accountMemoryAppName(a), accountMemoryAppName(b))
        assertFalse(accountMemoryAppName(a).contains("qa-a"))
        // Configuration evidence does not prove removal of the retained shared cache.
        assertFalse(AccountDeletionQuiescenceEvidence(a).permits(a))
    }
}
