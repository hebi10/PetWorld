package com.haebi.petworld.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareRepository
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.PetRepository
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncStatusReaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = LocalAccountScope.fromFirebaseUid("status-a") as LocalAccountScope.SignedIn
    private val other = LocalAccountScope.fromFirebaseUid("status-b") as LocalAccountScope.SignedIn
    private lateinit var db: PetWorldDatabase

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java).build()
        db.petDao().insert(PetEntity("pet-a", "나비", "CAT", "", null, null,
            "UNKNOWN", "UNKNOWN", "", null, false, 1, 1))
    }
    @After fun teardown() = db.close()

    private fun review(key: String, revision: Long = 1) = SyncInboundEntry(
        accountKey = key, entityKind = "CARE", petId = "pet-a", entityId = "care-a",
        revision = revision, payloadDigest = "digest-$revision",
        remoteJson = """{"type":"WATER","waterMl":0,"photoFile":null}""",
        localJson = """{"type":"WATER","waterMl":null,"photoFile":null}""",
        tombstone = false, status = "CONFLICT",
    )

    @Test fun statusCountsAndReviewRowsRemainAccountScoped() = runBlocking {
        val draft = CareDraft(petId = "pet-a", type = CareType.WATER,
            occurredAt = 1700000000000L, timezoneId = "Asia/Seoul", amount = "0")
        val repo = CareRepository(context, db, account)
        repo.save(draft)
        repo.save(draft)
        val first = db.syncOutboxDao().firstOutstanding(account.key)!!
        assertEquals(1, db.syncOutboxDao().readyAfterVerifiedBaseline(account.key, first.opId, -1))
        assertEquals(1, db.syncOutboxDao().beginAttempt(account.key, first.opId, "READY", 0))
        assertEquals(1, db.syncOutboxDao().finishAttempt(account.key, first.opId, 1, "ACKNOWLEDGED"))
        db.syncInboundDao().insert(review(account.key))
        db.syncInboundDao().insert(review(account.key, 2))
        db.syncInboundDao().insert(review(other.key))
        val reader = SyncStatusReader(db, account)
        val status = reader.observe(1).first()
        assertEquals(1, status.pending)
        assertEquals(1, status.acknowledged)
        assertEquals(1, status.waitingBaseline)
        assertEquals(2, status.reviewCount)
        assertEquals(1, status.reviews.size)
        assertEquals(2L, status.reviews.single().revision)
        assertEquals("충돌 또는 확인 필요", status.summary(false))
        val otherStatus = SyncStatusReader(db, other).read(20)
        assertEquals(0, otherStatus.pending)
        assertEquals(1, otherStatus.reviewCount)
        assertEquals(other.key, otherStatus.reviews.single().accountKey)
    }

    @Test fun displayPreservesZeroVsNullAndDefaultPermissionNeverOffersApply() {
        val item = review(account.key)
        val measurement = reviewComparison(item).single { it.label == "음수량 (mL)" }
        assertEquals("미입력", measurement.local)
        assertEquals("0", measurement.remote)
        val freeText = reviewComparison(item.copy(
            remoteJson = """{"note":"WATER","type":"WATER"}""",
            localJson = """{"note":"CAT","type":"WATER"}""",
        )).single { it.label == "메모" }
        assertEquals("CAT", freeText.local)
        assertEquals("WATER", freeText.remote)
        assertFalse(canOfferRemoteReview(item, false))
        assertTrue(canOfferRemoteReview(item, true))
        assertFalse(canOfferRemoteReview(item.copy(tombstone = true), true))
        assertFalse(canOfferRemoteReview(item.copy(status = "PHOTO_PENDING"), true))
        assertFalse(canOfferRemoteReview(item.copy(remoteJson = "{"), true))
        val empty = SyncStatusSnapshot(emptyMap(), null, null, 0, emptyList())
        assertEquals("동기화 비활성", empty.summary(false))
        assertEquals("서버 조회 전", empty.summary(true))
    }

    @Test fun remoteProfileChangeRefreshesExistingRepository() = runBlocking {
        val repo = PetRepository(context, db, account)
        val observer = launch { repo.observeAccountProfileChanges() }
        try {
            withTimeout(10000) { repo.profiles.first { it.any { p -> p.name == "나비" } } }
            val pet = requireNotNull(db.petDao().find("pet-a"))
            db.petDao().update(pet.copy(name = "서버에서 변경"))
            withTimeout(10000) { repo.profiles.first { it.any { p -> p.name == "서버에서 변경" } } }
            assertTrue(db.syncOutboxDao().list(account.key, 10).isEmpty())
        } finally {
            observer.cancel()
            observer.join()
        }
    }
}
