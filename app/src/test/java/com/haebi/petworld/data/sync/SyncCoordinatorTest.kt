package com.haebi.petworld.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareRepository
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.migration.FirstImportPayloads
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = LocalAccountScope.fromFirebaseUid("coordinator-a") as LocalAccountScope.SignedIn
    private val other = LocalAccountScope.fromFirebaseUid("coordinator-b") as LocalAccountScope.SignedIn
    private val permission = SyncPermission { it == account }
    private lateinit var db: PetWorldDatabase
    private lateinit var receiver: SyncReceiver
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java).build()
        receiver = SyncReceiver(db, account, permission)
    }
    @After fun teardown() = db.close()

    private fun pet(id: String = "pet-a") = RemoteSyncDocument("PET", id, id,
        FirstImportPayloads.pet(PetEntity(id, "나비", "CAT", "", null, null,
            "UNKNOWN", "UNKNOWN", "", null, false, 1, 1)))
    private fun record(id: String = "record-a", pet: String = "pet-a", revision: Long = 0, amount: Long = 0) =
        RemoteSyncDocument("CARE", pet, id, FirstImportPayloads.record(
            CareEntity(id, pet, "WATER", 1700000000000L, "Asia/Seoul", null,
                foodServedMilli = null, foodConsumedMilli = null, foodUnit = null, waterMl = amount,
                eliminationKind = null, eliminationCount = null, activityKind = null,
                activityMinutes = null, weightGrams = null, weightInputUnit = null,
                note = "", createdAt = 1, updatedAt = 1),
        ) + ("revision" to revision))
    private fun page(parent: String?, docs: List<RemoteSyncDocument>, more: Boolean = false) =
        RemoteSyncPage(account.key, parent, docs, docs.lastOrNull()?.entityId, more)
    private suspend fun seed() {
        receiver.applyPage(page(null, listOf(pet())))
        receiver.applyPage(page("pet-a", listOf(record())))
    }
    private suspend fun edit(amount: String = "25") = CareRepository(context, db, account).save(
        CareDraft(id = "record-a", petId = "pet-a", type = CareType.WATER,
            occurredAt = 1700000000000L, timezoneId = "Asia/Seoul", amount = amount))

    @Test fun checkpointsResumeChildPagesAndExplicitNextRoundRescans() = runBlocking {
        val calls = mutableListOf<Pair<String?, String?>>()
        val source = SyncPageSource { _, parent, after, _ ->
            calls.add(parent to after)
            when {
                parent == null -> page(null, listOf(pet("pet-a"), pet("pet-b")))
                parent == "pet-a" && after == null -> page(parent, listOf(record()), true)
                parent == "pet-a" -> page(parent, listOf(record("record-b")))
                else -> page(parent, emptyList())
            }
        }
        fun coordinator() = SyncScanCoordinator(db, account, source, permission)
        assertEquals(ScanStep.MORE, coordinator().step())
        assertEquals(ScanStep.MORE, coordinator().step())
        assertEquals("record-a", db.syncScanDao().find(account.key)?.recordCursor)
        assertEquals(ScanStep.MORE, coordinator().step())
        assertEquals(ScanStep.COMPLETE, coordinator().step())
        assertEquals(ScanStep.COMPLETE, coordinator().step())
        assertEquals(listOf(null to null, "pet-a" to null, "pet-a" to "record-a", "pet-b" to null), calls)
        assertEquals(2, db.careDao().countForPet("pet-a"))
        assertNull(db.syncScanDao().find(other.key))
        assertTrue(coordinator().restartCompletedRound())
        assertEquals(2L, db.syncScanDao().find(account.key)?.round)
        assertEquals(ScanStep.MORE, coordinator().step())
        assertEquals(null to null, calls.last())
    }

    @Test fun invalidPageCannotAdvanceCheckpointOrApplyPartialRows() = runBlocking {
        val malformed = pet("pet-b").let { it.copy(fields = it.fields + ("sex" to "BAD")) }
        val source = SyncPageSource { _, _, _, _ -> page(null, listOf(pet(), malformed)) }
        assertTrue(runCatching { SyncScanCoordinator(db, account, source, permission).step() }.isFailure)
        assertNull(db.syncScanDao().find(account.key))
        assertTrue(db.petDao().list().isEmpty())
    }

    @Test fun baselineMatchesAndAcknowledgementSupportsNextLocalEdit() = runBlocking {
        seed()
        edit("25")
        var remote = record()
        val source = SyncDocumentSource { _, _, _, _ -> remote }
        val preparer = SyncBaselinePreparer(db, account, source, permission)
        val transport = SyncTransport { command ->
            val decision = decideSyncCommit(command, null, remote.fields)
            if (decision is SyncRemoteDecision.Write) {
                remote = remote.copy(fields = decision.document)
                SyncCommitOutcome.Applied
            } else (decision as SyncRemoteDecision.Stop).result
        }
        val sender = SyncSender(db, transport, permission)
        assertEquals(SyncDispatch.Acknowledged, preparer.next(sender))
        assertEquals(1L, remote.fields["revision"])
        edit("40")
        assertEquals(SyncDispatch.Acknowledged, preparer.next(sender))
        assertEquals(2L, remote.fields["revision"])
        assertEquals(40L, remote.fields["waterMl"])
        assertEquals(SyncDispatch.Idle, preparer.next(sender))
    }

    @Test fun conflictingBaselinePreservedAndExplicitAcceptSupersedesOnlyUnsent() = runBlocking {
        seed()
        edit()
        val remote = record(revision = 1, amount = 50)
        val source = SyncDocumentSource { _, _, _, _ -> remote }
        val prepare = SyncBaselinePreparer(db, account, source, permission)
        assertEquals(SyncDispatch.Conflict, prepare.prepareHead())
        assertEquals(25L, db.careDao().find("record-a")?.waterMl)
        val review = db.syncInboundDao().latest(account.key, "CARE", "pet-a", "record-a")!!
        val approval = SyncReviewApproval(account.key, "CARE", "pet-a", "record-a", 1,
            review.payloadDigest, review.localJson, true)
        val resolver = SyncConflictReview(db, account, source, permission)
        assertFalse(resolver.accept(approval.copy(acceptRemote = false)))
        assertFalse(resolver.accept(approval.copy(remoteDigest = "bad")))
        assertTrue(resolver.accept(approval))
        assertEquals(50L, db.careDao().find("record-a")?.waterMl)
        assertEquals("SUPERSEDED", db.syncOutboxDao().list(account.key, 10).single().status)
        assertNotNull(db.syncOutboxDao().list(account.key, 10).single().afterJson)
        assertNull(db.syncOutboxDao().firstOutstanding(account.key))
    }

    @Test fun attemptedWriteCannotBeDiscardedByConflictReview() = runBlocking {
        seed()
        edit()
        val entry = db.syncOutboxDao().firstOutstanding(account.key)!!
        assertEquals(1, db.syncOutboxDao().readyAfterVerifiedBaseline(account.key, entry.opId, 0))
        assertEquals(1, db.syncOutboxDao().beginAttempt(account.key, entry.opId, "READY", 0))
        val remote = record(revision = 1, amount = 99)
        receiver.applyPage(page("pet-a", listOf(remote)))
        val conflict = db.syncInboundDao().latest(account.key, "CARE", "pet-a", "record-a")!!
        val approval = SyncReviewApproval(account.key, "CARE", "pet-a", "record-a", 1,
            conflict.payloadDigest, conflict.localJson, true)
        assertFalse(SyncConflictReview(db, account, SyncDocumentSource { _, _, _, _ -> remote },
            permission).accept(approval))
        assertEquals("IN_FLIGHT", db.syncOutboxDao().firstOutstanding(account.key)?.status)
        assertEquals(25L, db.careDao().find("record-a")?.waterMl)
    }
}
