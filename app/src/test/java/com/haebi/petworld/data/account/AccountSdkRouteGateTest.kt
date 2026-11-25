package com.haebi.petworld.data.account

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class AccountSdkRouteGateTest {
    private val a = LocalAccountScope.fromFirebaseUid("qa-a") as LocalAccountScope.SignedIn
    private val b = LocalAccountScope.fromFirebaseUid("qa-b") as LocalAccountScope.SignedIn

    @Test fun reserveStopsLegacyWithoutGrantingNamedAccess() {
        val gate = AccountSdkRouteGate()
        val lease = requireNotNull(gate.reserve(a))
        assertFalse(gate.permitsLegacy(a))
        assertFalse(gate.permits(lease,a))
        assertTrue(gate.permitsLegacy(b))
        assertNull(gate.reserve(a))
        assertTrue(gate.activate(lease))
        assertTrue(gate.permits(lease,a))
        assertFalse(gate.permits(lease,b))
        assertFalse(gate.permitsLegacy(a))
    }

    @Test fun retiredOrStaleLeaseNeverReturnsToSharedClient() {
        val gate = AccountSdkRouteGate()
        val lease = requireNotNull(gate.reserve(a))
        val wrong = lease.copy(generation = "wrong")
        assertFalse(gate.activate(wrong))
        gate.retire(wrong)
        assertTrue(gate.activate(lease))
        gate.retire(lease)
        assertFalse(gate.permits(lease,a))
        assertFalse(gate.activate(lease))
        assertFalse(gate.permitsLegacy(a))
        assertNull(gate.reserve(a))
        assertTrue(gate.permitsLegacy(b))
    }

    @Test fun failedPreparationCannotActivateEvenIfAccountReturns() {
        val gate = AccountSdkRouteGate()
        val lease = requireNotNull(gate.reserve(a))
        gate.retire(lease)
        assertFalse(gate.activate(lease))
        assertFalse(gate.permitsLegacy(a))
        val other = requireNotNull(gate.reserve(b))
        assertTrue(gate.activate(other))
        assertTrue(gate.permits(other,b))
        assertFalse(gate.permits(other,a))
    }

    @Test fun shutdownAcknowledgesWritesBeforeTerminationAndCredentialClearing() = runBlocking {
        val events = mutableListOf<String>()
        val closed = drainAndCloseIsolatedSdk(
            sameAccount = { true },
            drain = { events += "drain"; true },
            acknowledgeWrites = { events += "ack" },
            stillDrained = { true },
            terminate = { events += "terminate" },
            signOut = { events += "signout"; true },
        )
        assertTrue(closed)
        assertEquals(listOf("drain","ack","terminate","signout"),events)
    }

    @Test fun unresolvedOrNewWorkPreventsTermination() = runBlocking {
        var terminated = false
        for (drained in listOf(false,true)) {
            assertFalse(drainAndCloseIsolatedSdk(
                sameAccount = { true },
                drain = { drained },
                acknowledgeWrites = {},
                stillDrained = { false },
                terminate = { terminated = true },
                signOut = { error("must not sign out") },
            ))
        }
        assertFalse(terminated)
    }

    @Test fun changedAccountOrFailedTerminationCannotClearCredentials() = runBlocking {
        var current = true
        var cleared = false
        assertFalse(drainAndCloseIsolatedSdk(
            sameAccount = { current },
            drain = { true },
            acknowledgeWrites = { current = false },
            stillDrained = { true },
            terminate = { error("must not terminate") },
            signOut = { cleared = true; true },
        ))
        current = true
        val failed = runCatching {
            drainAndCloseIsolatedSdk(
                sameAccount = { current },
                drain = { true },
                acknowledgeWrites = {},
                stillDrained = { true },
                terminate = { error("uncertain SDK result") },
                signOut = { cleared = true; true },
            )
        }
        assertTrue(failed.isFailure)
        assertFalse(cleared)
    }

    @Test fun pendingWriteTimeoutDoesNotTerminateOrSignOut() = runBlocking {
        val result = withTimeoutOrNull(30) {
            drainAndCloseIsolatedSdk(
                sameAccount = { true },
                drain = { true },
                acknowledgeWrites = { awaitCancellation() },
                stillDrained = { true },
                terminate = { error("must not terminate") },
                signOut = { error("must not sign out") },
            )
        }
        assertNull(result)
    }
}
