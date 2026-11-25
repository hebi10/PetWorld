package com.haebi.petworld.data.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeletionRecoveryControllerTest {
    private val uid = "qa-alice"
    private val account = LocalAccountScope.fromFirebaseUid(uid) as LocalAccountScope.SignedIn
    private val other = LocalAccountScope.fromFirebaseUid("qa-bob") as LocalAccountScope.SignedIn
    private val ready = AccountDeletionReadiness(true,true,true,true)

    private class Store : DeletionStateStore {
        val rows = mutableMapOf<LocalAccountScope.SignedIn,DeletionRequestState>()
        var broken = false
        override fun read(account: LocalAccountScope.SignedIn): DeletionRequestState? {
            check(!broken)
            return rows[account]
        }
        override fun write(state: DeletionRequestState) { check(!broken); rows[state.account] = state }
        override fun blocksCloud(account: LocalAccountScope.SignedIn) = broken || rows.containsKey(account)
    }
    @Test fun responseLossKeepsSameTicketAndBlocksOnlyTheTargetAccount() = runBlocking {
        val store = Store()
        val tickets = mutableListOf<String>()
        val transport = object : DeletionRequestTransport {
            override suspend fun request(freshIdToken: String,statusTicket: String): AccountDeletionReceipt {
                assertTrue(store.blocksCloud(account))
                assertFalse(store.blocksCloud(other))
                tickets += statusTicket
                if (tickets.size == 1) error("response unavailable")
                return requireNotNull(AccountDeletionReceipt.fromServer(deletionJobId(uid),"LOCKED"))
            }
            override suspend fun status(jobId: String,statusTicket: String): AccountDeletionReceipt {
                assertEquals(tickets.first(),statusTicket)
                return requireNotNull(AccountDeletionReceipt.fromServer(jobId,"COMPLETE"))
            }
        }
        val controller = DeletionRecoveryController(store,transport)
        assertEquals(DeletionRecoveryResult.StateUnknown,controller.submit(uid,"test-token",true,true,ready){account})
        assertNotNull(store.read(account))
        assertTrue(store.blocksCloud(account))
        assertTrue(controller.submit(uid,"test-token",true,true,ready){account} is DeletionRecoveryResult.Confirmed)
        assertEquals(2,tickets.size)
        assertEquals(tickets[0],tickets[1])
        assertFalse(store.read(account).toString().contains(tickets[0]))
        val result = controller.refresh(account,true) as DeletionRecoveryResult.Confirmed
        assertTrue(result.receipt.phase.serverDeletionComplete)
        assertTrue(store.blocksCloud(account))
    }
    @Test fun absentReadinessWrongAccountCorruptionAndWrongJobFailClosed() = runBlocking {
        val store = Store()
        var calls = 0
        val transport = object : DeletionRequestTransport {
            override suspend fun request(freshIdToken: String,statusTicket: String): AccountDeletionReceipt {
                calls++
                return requireNotNull(AccountDeletionReceipt.fromServer("b".repeat(64),"COMPLETE"))
            }
            override suspend fun status(jobId: String,statusTicket: String): AccountDeletionReceipt = error("not expected")
        }
        val c = DeletionRecoveryController(store,transport)
        assertEquals(DeletionRecoveryResult.NotReady,c.submit(uid,"t",true,true,AccountDeletionReadiness()){account})
        assertEquals(DeletionRecoveryResult.NotReady,c.submit(uid,"t",true,true,ready){other})
        assertEquals(0,calls)
        store.broken = true
        assertEquals(DeletionRecoveryResult.StateUnknown,c.submit(uid,"t",true,true,ready){account})
        assertEquals(0,calls)
        store.broken = false
        assertEquals(DeletionRecoveryResult.StateUnknown,c.submit(uid,"t",true,true,ready){account})
        assertEquals(1,calls)
        assertNull(store.read(account)?.receipt)
        assertTrue(store.blocksCloud(account))
    }
    @Test fun ticketUsesCanonicalRandom256BitAndRecoveryIsAccountBound() {
        val state = DeletionRequestState.new(uid)
        val next = DeletionRequestState.new(uid)
        assertNotEquals(state.statusTicket,next.statusTicket)
        assertEquals(32,java.util.Base64.getUrlDecoder().decode(state.statusTicket).size)
        assertEquals(state.jobId,next.jobId)
        assertEquals(state.account,account)
        assertNotEquals(deletionJobId(uid),deletionJobId("qa-bob"))
        assertThrows(IllegalArgumentException::class.java) {
            DeletionRequestState.restore(account,state.jobId,"a".repeat(43),null)
        }
    }
}
