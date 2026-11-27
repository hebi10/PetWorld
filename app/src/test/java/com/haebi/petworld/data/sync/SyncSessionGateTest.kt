package com.haebi.petworld.data.sync

import com.haebi.petworld.data.account.LocalAccountScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSessionGateTest {
    private val a = LocalAccountScope.fromFirebaseUid("qa-a") as LocalAccountScope.SignedIn
    private val b = LocalAccountScope.fromFirebaseUid("qa-b") as LocalAccountScope.SignedIn
    private val evidence = VerifiedSyncSession(a, "a".repeat(64), true, true)

    @Test fun defaultDeniedAndIncompleteEvidenceCannotActivate() {
        val gate = SyncSessionGate()
        assertFalse(gate.permits(SyncWorkLease(a.key, "stale"), a))
        assertNull(gate.activate(evidence.copy(consentConfirmed = false)))
        assertNull(gate.activate(evidence.copy(liveServerQaPassed = false)))
        assertNull(gate.activate(evidence.copy(baselineDigest = "")))
    }

    @Test fun guestOtherAccountAndProcessRestartCannotReuseLease() {
        val gate = SyncSessionGate()
        val lease = requireNotNull(gate.activate(evidence))
        assertTrue(gate.permits(lease, a))
        assertFalse(gate.permits(lease, b))
        assertFalse(gate.permits(lease, LocalAccountScope.Guest))
        assertFalse(SyncSessionGate().permits(lease, a))
        assertNull(gate.revokeUnlessAccount(a))
        assertEquals(lease, gate.revokeUnlessAccount(b))
        assertFalse(gate.permits(lease, a))
        val newLease = requireNotNull(gate.activate(evidence))
        assertNotEquals(lease, newLease)
        assertFalse(gate.permits(lease, a))
        assertEquals(newLease, gate.revoke())
        assertFalse(gate.permits(newLease, a))
    }

    @Test fun batchNeverCallsSenderWhenDenied() = runBlocking {
        var calls = 0
        assertEquals(SyncBatchOutcome.PAUSED, drainSyncBatch(
            allowed = { false }, next = { calls++; SyncDispatch.Acknowledged },
        ))
        assertEquals(0, calls)
    }

    @Test fun permissionRevocationStopsBeforeNextMutation() = runBlocking {
        var enabled = true
        var calls = 0
        val result = drainSyncBatch(
            allowed = { enabled },
            next = { calls++; enabled = false; SyncDispatch.Acknowledged },
        )
        assertEquals(SyncBatchOutcome.PAUSED, result)
        assertEquals(1, calls)
    }

    @Test fun terminalStatesDoNotSkipHeadOrBusyLoop() = runBlocking {
        for ((dispatch, expected) in listOf(
            SyncDispatch.WaitingBaseline to SyncBatchOutcome.WAITING_BASELINE,
            SyncDispatch.Conflict to SyncBatchOutcome.CONFLICT,
            SyncDispatch.Blocked to SyncBatchOutcome.BLOCKED,
            SyncDispatch.Retry(30_000) to SyncBatchOutcome.RETRY,
            SyncDispatch.Idle to SyncBatchOutcome.DRAINED,
        )) {
            var calls = 0
            assertEquals(expected, drainSyncBatch(
                allowed = { true }, next = { calls++; dispatch },
            ))
            assertEquals(1, calls)
        }
    }

    @Test fun batchBoundPreventsUnboundedBackgroundExecution() = runBlocking {
        var calls = 0
        assertEquals(SyncBatchOutcome.MORE_WORK, drainSyncBatch(3,
            allowed = { true }, next = { calls++; SyncDispatch.Acknowledged },
        ))
        assertEquals(3, calls)
    }
}
