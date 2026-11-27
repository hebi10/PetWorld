package com.haebi.petworld.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareRepository
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncSenderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = LocalAccountScope.fromFirebaseUid("qa-sync-a") as LocalAccountScope.SignedIn
    private val other = LocalAccountScope.fromFirebaseUid("qa-sync-b") as LocalAccountScope.SignedIn
    private lateinit var db: PetWorldDatabase
    private lateinit var care: CareRepository

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java).build()
        db.petDao().insert(PetEntity("pet-a", "나비", "CAT", "", null, null,
            "UNKNOWN", "UNKNOWN", "", null, false, 1L, 1L))
        care = CareRepository(context, db, account)
    }

    @After fun teardown() = db.close()

    private fun draft(amount: String = "0") = CareDraft(
        petId = "pet-a", type = CareType.WATER, occurredAt = 1_700_000_000_000L,
        timezoneId = "Asia/Seoul", amount = amount,
    )

    private suspend fun prepareFirst(revision: Long): SyncOutboxEntry {
        val row = requireNotNull(db.syncOutboxDao().firstOutstanding(account.key))
        assertEquals(1, db.syncOutboxDao().readyAfterVerifiedBaseline(account.key, row.opId, revision))
        return requireNotNull(db.syncOutboxDao().firstOutstanding(account.key))
    }

    private class MemoryTransport : SyncTransport {
        var document: Map<String, Any?>? = null
        val receipts = mutableMapOf<String, Map<String, Any?>>()
        var writes = 0
        var loseResponse = false
        override suspend fun commit(command: SyncCommand): SyncCommitOutcome {
            return when (val decision = decideSyncCommit(command, receipts[command.opId], document)) {
                is SyncRemoteDecision.Stop -> decision.result
                is SyncRemoteDecision.Write -> {
                    document = decision.document
                    receipts[command.opId] = decision.receipt
                    writes++
                    if (loseResponse) {
                        loseResponse = false
                        throw IllegalStateException("response lost after commit")
                    }
                    SyncCommitOutcome.Applied
                }
            }
        }
    }

    @Test fun noConsentGuestOtherAccountAndUnknownBaselineNeverTransmit() = runBlocking {
        care.save(draft())
        val remote = MemoryTransport()
        val denied = SyncSender(db, remote)
        assertEquals(SyncDispatch.Paused, denied.runOnce(account))
        val sender = SyncSender(db, remote, SyncPermission { it == account })
        assertEquals(SyncDispatch.Paused, sender.runOnce(LocalAccountScope.Guest))
        assertEquals(SyncDispatch.Paused, sender.runOnce(other))
        assertEquals(SyncDispatch.WaitingBaseline, sender.runOnce(account))
        assertEquals(0, remote.writes)
        val row = requireNotNull(db.syncOutboxDao().firstOutstanding(account.key))
        assertEquals(0, db.syncOutboxDao().readyAfterVerifiedBaseline(account.key, row.opId, 0))
        assertEquals(0, db.syncOutboxDao().readyAfterVerifiedBaseline(other.key, row.opId, -1))
    }

    @Test fun lostResponseReplayUsesSameReceiptAndDoesNotDuplicateWrite() = runBlocking {
        care.save(draft())
        val entry = prepareFirst(-1)
        val command = SyncCommands.from(entry)
        val remote = MemoryTransport().apply { loseResponse = true }
        val sender = SyncSender(db, remote, SyncPermission { it == account })
        assertEquals(SyncDispatch.Retry(30_000), sender.runOnce(account))
        assertEquals(1, remote.writes)
        assertEquals(0L, remote.document?.get("waterMl"))
        assertTrue(remote.document!!.containsKey("foodServedMilli"))
        assertNull(remote.document!!["foodServedMilli"])
        assertEquals(command.requestDigest, SyncCommands.from(
            requireNotNull(db.syncOutboxDao().firstOutstanding(account.key))).requestDigest)
        assertEquals(SyncDispatch.Acknowledged, sender.runOnce(account))
        assertEquals(1, remote.writes)
        assertEquals(SyncDispatch.Idle, sender.runOnce(account))
        assertEquals("ACKNOWLEDGED", db.syncOutboxDao().list(account.key, 10).single().status)
    }

    @Test fun changedRemoteRevisionPreservesLocalAndBlocksLaterOperations() = runBlocking {
        val row = care.save(draft("3"))
        prepareFirst(-1)
        val remote = MemoryTransport()
        val sender = SyncSender(db, remote, SyncPermission { it == account })
        assertEquals(SyncDispatch.Acknowledged, sender.runOnce(account))
        care.save(draft("4").copy(id = row.id))
        prepareFirst(0)
        care.save(draft("5").copy(id = row.id))
        remote.document = remote.document!! + ("revision" to 1L) + ("waterMl" to 99L)
        assertEquals(SyncDispatch.Conflict, sender.runOnce(account))
        assertEquals(SyncDispatch.Conflict, sender.runOnce(account))
        assertEquals(99L, remote.document?.get("waterMl"))
        assertEquals(5L, db.careDao().find(row.id)?.waterMl)
        assertEquals(listOf("ACKNOWLEDGED", "CONFLICT", SYNC_WAITING_BASELINE),
            db.syncOutboxDao().list(account.key, 10).map { it.status })
    }

    @Test fun deletionKeepsTombstoneAndReplayCannotResurrect() = runBlocking {
        val row = care.save(draft("6"))
        prepareFirst(-1)
        val remote = MemoryTransport()
        val sender = SyncSender(db, remote, SyncPermission { it == account })
        assertEquals(SyncDispatch.Acknowledged, sender.runOnce(account))
        care.delete(row.id, row.petId)
        val deleted = prepareFirst(0)
        assertEquals(SyncDispatch.Acknowledged, sender.runOnce(account))
        assertEquals(1L, remote.document?.get("revision"))
        assertNotNull(remote.document?.get("deletedAtMs"))
        assertNull(db.careDao().find(row.id))
        val command = SyncCommands.from(deleted)
        assertEquals(SyncRemoteDecision.Stop(SyncCommitOutcome.Applied),
            decideSyncCommit(command, remote.receipts[command.opId], remote.document))
        assertEquals(SyncRemoteDecision.Stop(SyncCommitOutcome.Conflict),
            decideSyncCommit(command, null, remote.document))
    }

    @Test fun logoutAfterRemoteCommitLeavesDurableReplay() = runBlocking {
        care.save(draft())
        prepareFirst(-1)
        val remote = MemoryTransport()
        var signedIn = true
        val transport = SyncTransport { command ->
            remote.commit(command).also { signedIn = false }
        }
        val permission = SyncPermission { signedIn && it == account }
        val sender = SyncSender(db, transport, permission)
        assertEquals(SyncDispatch.Paused, sender.runOnce(account))
        assertEquals("IN_FLIGHT", db.syncOutboxDao().firstOutstanding(account.key)?.status)
        signedIn = true
        assertEquals(SyncDispatch.Acknowledged, SyncSender(db, remote, permission).runOnce(account))
        assertEquals(1, remote.writes)
    }

    @Test fun retryBackoffIsBoundedAndMonotonic() {
        assertEquals(30_000L, retryDelayMillis(1))
        assertEquals(60_000L, retryDelayMillis(2))
        assertEquals(21_600_000L, retryDelayMillis(100))
    }
}
