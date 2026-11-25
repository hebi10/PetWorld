package com.haebi.petworld.data.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceDeletionRecoveryAccessTest {
    private val uid = "qa-alice"
    private val account = LocalAccountScope.fromFirebaseUid(uid) as LocalAccountScope.SignedIn
    private val other = LocalAccountScope.fromFirebaseUid("qa-bob") as LocalAccountScope.SignedIn
    private val ready = AccountDeletionReadiness(true,true,true,true)

    private class Anchors : DeletionRecoveryAnchorStore {
        var row: DeletionRecoveryAnchor? = null
        var reads = 0
        var broken = false
        override fun read(): DeletionRecoveryAnchor? { reads++; check(!broken); return row }
        override fun retain(anchor: DeletionRecoveryAnchor): Boolean {
            if (row != null && row != anchor) return false
            row = anchor
            return true
        }
    }

    private class Journal : DeletionStateStore {
        val rows = mutableMapOf<LocalAccountScope.SignedIn,DeletionRequestState>()
        override fun read(account: LocalAccountScope.SignedIn) = rows[account]
        override fun write(state: DeletionRequestState) { rows[state.account] = state }
        override fun blocksCloud(account: LocalAccountScope.SignedIn) = rows.containsKey(account)
    }

    private inner class Env {
        val anchors = Anchors()
        val journal = Journal()
        var scope: LocalAccountScope? = account
        var version = 0L
        var visible = true
        var allowCredential = true
        var prompts = 0
        var requests = 0
        var statuses = 0
        var afterPrompt: () -> Unit = {}
        var duringStatus: () -> Unit = {}
        var phase = "COMPLETE"
        val access = DeviceDeletionRecoveryAccess(
            anchors, { prompts++; afterPrompt(); allowCredential },
            { scope }, { visible }, { version },
        )
        val transport = object : DeletionRequestTransport {
            override suspend fun request(freshIdToken: String,statusTicket: String): AccountDeletionReceipt {
                requests++
                assertEquals(account,anchors.row?.account)
                assertTrue(journal.blocksCloud(account))
                return requireNotNull(AccountDeletionReceipt.fromServer(deletionJobId(uid),"LOCKED"))
            }
            override suspend fun status(jobId: String,statusTicket: String): AccountDeletionReceipt {
                statuses++
                assertEquals(journal.rows[account]?.statusTicket,statusTicket)
                duringStatus()
                return requireNotNull(AccountDeletionReceipt.fromServer(jobId,phase))
            }
        }
        fun controller() = DeletionRecoveryController(journal,transport,access)
        suspend fun submit() = controller().submit(uid,"synthetic-token",true,true,ready){scope}
        fun guest() { scope = LocalAccountScope.Guest; version++ }
    }

    @Test fun missingEnrollmentCannotWriteOrSend() = runBlocking {
        val env = Env()
        val controller = DeletionRecoveryController(env.journal,env.transport)
        assertEquals(DeletionRecoveryResult.NotReady,controller.submit(uid,"synthetic",true,true,ready){account})
        assertTrue(env.journal.rows.isEmpty())
        assertEquals(0,env.requests)
    }

    @Test fun signOutAndRestartUseOnlyEnrolledJournalAndDisplayPhase() = runBlocking {
        val env = Env()
        assertTrue(env.submit() is DeletionRecoveryResult.Confirmed)
        env.guest()
        assertEquals(AccountDeletionStatusView.Saved(AccountDeletionPhase.COMPLETE),
            env.access.refresh(env.controller(),true))
        assertEquals(1,env.requests)
        assertEquals(1,env.statuses)
        assertEquals(2,env.prompts)
        assertTrue(env.journal.blocksCloud(account))
        assertFalse(env.journal.blocksCloud(other))
        assertFalse(env.anchors.row.toString().contains(account.key))
    }

    @Test fun cancelledCredentialOrUnverifiedEndpointCannotReadTheSlot() = runBlocking {
        val env = Env()
        env.allowCredential = false
        assertEquals(DeletionRecoveryResult.NotReady,env.submit())
        assertTrue(env.journal.rows.isEmpty())
        assertEquals(0,env.anchors.reads)
        env.guest()
        assertEquals(AccountDeletionStatusView.Unknown,env.access.refresh(env.controller(),false))
        assertEquals(AccountDeletionStatusView.Unknown,env.access.refresh(env.controller(),true))
        assertEquals(0,env.anchors.reads)
        assertEquals(0,env.statuses)
    }

    @Test fun otherAccountCannotReplaceSlotOrReadGuestRecovery() = runBlocking {
        val env = Env()
        assertTrue(env.submit() is DeletionRecoveryResult.Confirmed)
        val first = env.anchors.row
        env.scope = other
        env.version++
        assertFalse(env.access.enroll(DeletionRecoveryAnchor(other,deletionJobId("qa-bob"))))
        assertEquals(first,env.anchors.row)
        assertEquals(AccountDeletionStatusView.Unknown,env.access.refresh(env.controller(),true))
        assertEquals(0,env.statuses)
    }

    @Test fun missingJournalOrMismatchedAnchorDoesNotCreateNewRequest() = runBlocking {
        val env = Env()
        env.guest()
        env.anchors.row = DeletionRecoveryAnchor(account,deletionJobId(uid))
        assertEquals(AccountDeletionStatusView.Unknown,env.access.refresh(env.controller(),true))
        env.journal.write(DeletionRequestState.new(uid))
        env.anchors.row = DeletionRecoveryAnchor(account,"b".repeat(64))
        assertEquals(AccountDeletionStatusView.Unknown,env.access.refresh(env.controller(),true))
        assertEquals(0,env.statuses)
        assertEquals(0,env.requests)
    }

    @Test fun accountSwitchDuringCredentialAndNetworkInvalidatesRecovery() = runBlocking {
        val env = Env()
        env.afterPrompt = { env.scope = other; env.version++ }
        assertEquals(DeletionRecoveryResult.NotReady,env.submit())
        assertTrue(env.journal.rows.isEmpty())
        env.scope = account
        env.afterPrompt = {}
        assertTrue(env.submit() is DeletionRecoveryResult.Confirmed)
        env.guest()
        env.duringStatus = { env.version += 2 } // Even an A→B→A transition invalidates authorization.
        assertEquals(AccountDeletionStatusView.Unknown,env.access.refresh(env.controller(),true))
        assertEquals(AccountDeletionPhase.LOCKED,env.journal.read(account)?.receipt?.phase)
        assertEquals(1,env.statuses)
    }

    @Test fun backgroundCorruptSlotAndRegressingReceiptFailClosed() = runBlocking {
        val env = Env()
        assertTrue(env.submit() is DeletionRecoveryResult.Confirmed)
        env.guest()
        env.visible = false
        assertEquals(AccountDeletionStatusView.Unknown,env.access.refresh(env.controller(),true))
        env.visible = true
        env.anchors.broken = true
        assertEquals(AccountDeletionStatusView.Unknown,env.access.refresh(env.controller(),true))
        env.anchors.broken = false
        assertEquals(AccountDeletionStatusView.Saved(AccountDeletionPhase.COMPLETE),env.access.refresh(env.controller(),true))
        env.phase = "LOCKED"
        assertEquals(AccountDeletionStatusView.Unknown,env.access.refresh(env.controller(),true))
        assertEquals(AccountDeletionPhase.COMPLETE,env.journal.read(account)?.receipt?.phase)
    }

    @Test fun sharedSdkCacheAndIncompleteDrainNeverAuthorizeLocalCleanup() {
        val complete = AccountDeletionQuiescenceEvidence(account,LocalAccountScope.Guest,
            newCloudWorkBlocked=true,syncWorkerDrained=true,firstImportDrained=true,
            sdkRequestsDrained=true,listenersDetached=true,oldViewModelsReleased=true,
            imageWorkDrained=true,roomScopeClosed=true,
            sharedSdkCache=SharedSdkCacheDisposition.VERIFIED_NO_TARGET_REMAINS,
            otherAccountsAndGuestPreserved=true)
        assertTrue(complete.permits(account))
        assertFalse(complete.permits(other))
        assertFalse(complete.copy(sharedSdkCache=SharedSdkCacheDisposition.UNVERIFIED_SHARED).permits(account))
        assertFalse(complete.copy(currentScope=other).permits(account))
        assertFalse(complete.copy(sdkRequestsDrained=false).permits(account))
        assertFalse(complete.copy(firstImportDrained=false).permits(account))
        assertFalse(complete.copy(otherAccountsAndGuestPreserved=false).permits(account))
        assertFalse(VerifiedAccountStorageQuiescence { null }.isQuiescent(account))
        assertFalse(VerifiedAccountStorageQuiescence { error("unavailable") }.isQuiescent(account))
    }
}
