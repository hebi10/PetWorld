package com.haebi.petworld.data.account

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AccountCloudWorkTrackerTest {
    private val a = LocalAccountScope.fromFirebaseUid("qa-a") as LocalAccountScope.SignedIn
    private val b = LocalAccountScope.fromFirebaseUid("qa-b") as LocalAccountScope.SignedIn

    @Test fun blockedAccountRejectsNewWorkWhileOtherAccountContinues() {
        val tracker = AccountCloudWorkTracker()
        tracker.blockNewWork(a)
        for (kind in CloudWorkKind.entries) assertNull(tracker.tryBegin(a, kind))
        val other = requireNotNull(tracker.tryBegin(b, CloudWorkKind.SDK_TASK))
        assertTrue(tracker.snapshot(a).drained)
        assertFalse(tracker.isBlocked(b))
        assertEquals(1, tracker.snapshot(b).sdkTasks)
        other.close()
        assertEquals(0, tracker.snapshot(b).sdkTasks)
    }

    @Test fun allFourKindsMustFinishBeforeDrain() = runBlocking {
        val tracker = AccountCloudWorkTracker()
        val worker = requireNotNull(tracker.tryBegin(a, CloudWorkKind.SYNC_WORKER))
        val importing = requireNotNull(tracker.tryBegin(a, CloudWorkKind.FIRST_IMPORT))
        val review = requireNotNull(tracker.tryBegin(a, CloudWorkKind.SYNC_REVIEW))
        val task = requireNotNull(tracker.tryBegin(a, CloudWorkKind.SDK_TASK))
        tracker.blockNewWork(a)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { tracker.awaitDrained(a, 5000) }
        worker.close()
        importing.close()
        review.close()
        assertFalse(tracker.snapshot(a).drained)
        assertEquals(1, tracker.snapshot(a).sdkTasks)
        task.close()
        assertTrue(waiting.await())
        assertTrue(tracker.snapshot(a).drained)
    }

    @Test fun timeoutOrCancelledWaitDoesNotReleaseOutstandingWork() = runBlocking {
        val tracker = AccountCloudWorkTracker()
        val task = requireNotNull(tracker.tryBegin(a, CloudWorkKind.SDK_TASK))
        tracker.blockNewWork(a)
        assertFalse(tracker.awaitDrained(a, 20))
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { tracker.awaitDrained(a, 5000) }
        waiting.cancelAndJoin()
        assertTrue(tracker.isBlocked(a))
        assertEquals(1, tracker.snapshot(a).sdkTasks)
        assertNull(tracker.tryBegin(a, CloudWorkKind.FIRST_IMPORT))
        task.close()
        assertTrue(tracker.awaitDrained(a, 500))
    }

    @Test fun closingPermitTwiceCannotReleaseAnotherOperation() {
        val tracker = AccountCloudWorkTracker()
        val first = requireNotNull(tracker.tryBegin(a, CloudWorkKind.SDK_TASK))
        val second = requireNotNull(tracker.tryBegin(a, CloudWorkKind.SDK_TASK))
        tracker.blockNewWork(a)
        first.close()
        first.close()
        assertEquals(1, tracker.snapshot(a).sdkTasks)
        assertFalse(tracker.snapshot(a).drained)
        second.close()
        assertTrue(tracker.snapshot(a).drained)
    }

    @Test fun newProcessOrUnblockedAccountDoesNotProveDrain() = runBlocking {
        val tracker = AccountCloudWorkTracker()
        assertFalse(tracker.awaitDrained(a, 10))
        assertFalse(tracker.snapshot(a).drained)
        tracker.blockNewWork(a)
        assertTrue(tracker.awaitDrained(a, 100))
        assertFalse(AccountCloudWorkTracker().snapshot(a).drained)
    }

    @Test fun concurrentAdmissionAndBlockingCannotLoseAnAcquiredPermit() = runBlocking {
        val tracker = AccountCloudWorkTracker()
        val permits = (1..64).map {
            async(Dispatchers.Default) { tracker.tryBegin(a, CloudWorkKind.SDK_TASK) }
        }
        val block = launch(Dispatchers.Default) { tracker.blockNewWork(a) }
        val admitted = permits.awaitAll().filterNotNull()
        block.join()
        assertEquals(admitted.size, tracker.snapshot(a).sdkTasks)
        assertNull(tracker.tryBegin(a, CloudWorkKind.SDK_TASK))
        admitted.forEach { it.close() }
        assertTrue(tracker.snapshot(a).drained)
    }
}
