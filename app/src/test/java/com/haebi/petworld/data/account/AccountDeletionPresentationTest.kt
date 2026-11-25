package com.haebi.petworld.data.account

import org.junit.Assert.*
import org.junit.Test

class AccountDeletionPresentationTest {
    private val account = LocalAccountScope.fromFirebaseUid("deletion-qa")
    private val ready = AccountDeletionReadiness(true, true, true, true)

    @Test fun requestRequiresSignedInFreshAuthConsentAndEverySafetyGate() {
        assertFalse(AccountDeletionReadiness().canRequest(account, true, true))
        assertFalse(ready.canRequest(LocalAccountScope.Guest, true, true))
        assertFalse(ready.canRequest(account, false, true))
        assertFalse(ready.canRequest(account, true, false))
        assertFalse(ready.copy(verifiedEndpoint = false).canRequest(account, true, true))
        assertFalse(ready.copy(secureStatusTicket = false).canRequest(account, true, true))
        assertFalse(ready.copy(accountSyncBlocked = false).canRequest(account, true, true))
        assertFalse(ready.copy(cacheCleanupVerified = false).canRequest(account, true, true))
        assertTrue(ready.canRequest(account, true, true))
    }

    @Test fun onlyVerifiedCompletePhaseIsPresentedAsServerDeletionComplete() {
        for (phase in AccountDeletionPhase.entries) {
            val receipt = requireNotNull(AccountDeletionReceipt.fromServer("a".repeat(64), phase.name))
            assertEquals(phase == AccountDeletionPhase.COMPLETE, receipt.phase.serverDeletionComplete)
        }
        assertNull(AccountDeletionReceipt.fromServer("a".repeat(64), "SUCCESS"))
        assertNull(AccountDeletionReceipt.fromServer("bad-id", "COMPLETE"))
    }

    @Test fun statusCannotSwitchJobsOrRegress() {
        val id = "a".repeat(64)
        val locked = requireNotNull(AccountDeletionReceipt.fromServer(id, "LOCKED"))
        val verified = requireNotNull(AccountDeletionReceipt.fromServer(id, "VERIFIED"))
        val other = requireNotNull(AccountDeletionReceipt.fromServer("b".repeat(64), "COMPLETE"))
        assertEquals(verified, locked.accept(verified))
        assertNull(verified.accept(locked))
        assertNull(locked.accept(other))
        assertFalse(verified.phase.serverDeletionComplete)
    }
}
