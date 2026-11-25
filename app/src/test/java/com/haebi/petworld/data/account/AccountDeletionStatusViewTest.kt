package com.haebi.petworld.data.account

import org.junit.Assert.*
import org.junit.Test

class AccountDeletionStatusViewTest {
    private val state = DeletionRequestState.new("local-status-fixture")
    private class Store(var state: DeletionRequestState?,var blocked:Boolean,var broken:Boolean=false) : DeletionStateStore {
        override fun read(account: LocalAccountScope.SignedIn): DeletionRequestState? {
            check(!broken)
            return state
        }
        override fun write(state: DeletionRequestState) { this.state=state }
        override fun blocksCloud(account: LocalAccountScope.SignedIn)=blocked
    }
    @Test fun onlySameAccountSavedReceiptIsShownAndUnknownIsNotNone() {
        val store=Store(null,false)
        assertEquals(AccountDeletionStatusView.None,readDeletionStatusView(store,state.account))
        store.blocked=true
        assertEquals(AccountDeletionStatusView.Unknown,readDeletionStatusView(store,state.account))
        store.state=state
        assertEquals(AccountDeletionStatusView.Unknown,readDeletionStatusView(store,state.account))
        store.state=requireNotNull(state.accept(requireNotNull(AccountDeletionReceipt.fromServer(state.jobId,"VERIFIED"))))
        assertEquals(AccountDeletionStatusView.Saved(AccountDeletionPhase.VERIFIED),readDeletionStatusView(store,state.account))
        val other=DeletionRequestState.new("other-status-fixture").account
        assertEquals(AccountDeletionStatusView.Unknown,readDeletionStatusView(store,other))
        store.broken=true
        assertEquals(AccountDeletionStatusView.Unknown,readDeletionStatusView(store,state.account))
        store.broken=false;store.blocked=false
        assertEquals(AccountDeletionStatusView.Unknown,readDeletionStatusView(store,state.account))
    }
}
