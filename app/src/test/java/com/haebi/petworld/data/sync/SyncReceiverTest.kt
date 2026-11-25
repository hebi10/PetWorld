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
import kotlinx.coroutines.flow.first
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
class SyncReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = LocalAccountScope.fromFirebaseUid("receive-a") as LocalAccountScope.SignedIn
    private val other = LocalAccountScope.fromFirebaseUid("receive-b") as LocalAccountScope.SignedIn
    private lateinit var db: PetWorldDatabase
    private lateinit var receiver: SyncReceiver

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java).build()
        receiver = SyncReceiver(db, account, SyncPermission { it == account })
    }

    @After fun teardown() = db.close()

    private fun pet(id: String = "pet-a", name: String = "나비", revision: Long = 0) =
        RemoteSyncDocument("PET", id, id, FirstImportPayloads.pet(
            PetEntity(id, name, "CAT", "", null, null, "UNKNOWN", "UNKNOWN",
                "", null, false, 1, 1),
        ) + ("revision" to revision))

    private fun care(revision: Long = 0, water: Long? = 0L): RemoteSyncDocument {
        val row = CareEntity(
            id = "care-a", petId = "pet-a", type = "WATER", occurredAt = 1_700_000_000_000L,
            timezoneId = "Asia/Seoul", foodKind = null, foodServedMilli = null,
            foodConsumedMilli = null, foodUnit = null, waterMl = water,
            eliminationKind = null, eliminationCount = null, activityKind = null,
            activityMinutes = null, weightGrams = null, weightInputUnit = null,
            note = "", createdAt = 1, updatedAt = 1,
        )
        return RemoteSyncDocument("CARE", row.petId, row.id,
            FirstImportPayloads.record(row) + ("revision" to revision))
    }

    private fun page(vararg docs: RemoteSyncDocument, key: String = account.key) =
        RemoteSyncPage(key, docs.firstOrNull()?.takeIf { it.kind == "CARE" }?.petId,
            docs.toList(), docs.lastOrNull()?.entityId, false)

    @Test fun newParentChildAndExactBaselineUpdateDoNotCreateOutbox() = runBlocking {
        assertEquals(listOf(InboundResult.PARENT_PENDING), receiver.applyPage(page(care())))
        assertNull(db.careDao().find("care-a"))
        assertEquals(listOf(InboundResult.APPLIED), receiver.applyPage(page(pet())))
        assertEquals(listOf(InboundResult.APPLIED), receiver.applyPage(page(care())))
        assertEquals(0L, db.careDao().find("care-a")?.waterMl)
        assertEquals(listOf(InboundResult.DUPLICATE), receiver.applyPage(page(care())))
        assertEquals(listOf(InboundResult.APPLIED), receiver.applyPage(page(care(1, null))))
        assertNull(requireNotNull(db.careDao().find("care-a")).waterMl)
        assertEquals(listOf(InboundResult.STALE), receiver.applyPage(page(care())))
        assertEquals(0, db.syncOutboxDao().observeCount(account.key).first())
        assertEquals(0, db.syncInboundDao().observeReviewCount(account.key).first())
    }

    @Test fun localPendingConflictPreservesBothValuesAndOutbox() = runBlocking {
        receiver.applyPage(page(pet()))
        receiver.applyPage(page(care()))
        val repository = CareRepository(context, db, account)
        repository.save(CareDraft(id = "care-a", petId = "pet-a", type = CareType.WATER,
            occurredAt = 1_700_000_000_000L, timezoneId = "Asia/Seoul", amount = "25"))
        assertEquals(listOf(InboundResult.CONFLICT), receiver.applyPage(page(care(1, 50))))
        assertEquals(25L, db.careDao().find("care-a")?.waterMl)
        val conflict = db.syncInboundDao().list(account.key).last()
        assertEquals(50, parseSyncJson(conflict.remoteJson)["waterMl"].let { (it as Number).toInt() })
        assertEquals(25, parseSyncJson(requireNotNull(conflict.localJson))["waterMl"].let { (it as Number).toInt() })
        assertEquals(1, db.syncOutboxDao().observeCount(account.key).first())
        assertEquals(0, db.syncInboundDao().observeReviewCount(other.key).first())
    }

    @Test fun tombstonesSameRevisionDivergenceAndPhotosAreNeverAutomaticallyApplied() = runBlocking {
        receiver.applyPage(page(pet()))
        receiver.applyPage(page(care()))
        val deletion = care(1).let { it.copy(fields = it.fields + ("deletedAtMs" to 10L)) }
        assertEquals(listOf(InboundResult.DELETE_REVIEW), receiver.applyPage(page(deletion)))
        assertNotNull(db.careDao().find("care-a"))
        assertEquals(listOf(InboundResult.CONFLICT), receiver.applyPage(page(care(2, 99))))
        assertEquals(0L, db.careDao().find("care-a")?.waterMl)
        assertEquals(listOf(InboundResult.CONFLICT), receiver.applyPage(page(pet(name = "다른 이름"))))
        assertEquals("나비", db.petDao().find("pet-a")?.name)
        val withPhoto = pet("pet-photo").let {
            it.copy(fields = it.fields + ("photoFile" to "pet_test.jpg"))
        }
        assertEquals(listOf(InboundResult.PHOTO_PENDING), receiver.applyPage(page(withPhoto)))
        assertNull(db.petDao().find("pet-photo"))
        assertEquals(4, db.syncInboundDao().observeReviewCount(account.key).first())
    }

    @Test fun unauthorizedInvalidOrRevokedPageCannotPartiallyModifyDatabase() = runBlocking {
        val denied = SyncReceiver(db, account)
        assertTrue(runCatching { denied.applyPage(page(pet())) }.isFailure)
        assertTrue(runCatching { receiver.applyPage(page(pet(), key = other.key)) }.isFailure)
        val invalid = pet("pet-b").let { it.copy(fields = it.fields + ("sex" to "bad")) }
        assertTrue(runCatching { receiver.applyPage(page(pet(), invalid)) }.isFailure)
        assertTrue(db.petDao().list().isEmpty())
        var checks = 0
        val revoked = SyncReceiver(db, account, SyncPermission { ++checks <= 3 })
        assertTrue(runCatching { revoked.applyPage(page(pet(), pet("pet-b"))) }.isFailure)
        assertTrue(db.petDao().list().isEmpty())
        assertTrue(db.syncInboundDao().list(account.key).isEmpty())
    }

    @Test fun differingLocalUuidWithoutBaselineIsNotOverwritten() = runBlocking {
        db.petDao().insert(PetEntity("pet-a", "로컬", "CAT", "", null, null,
            "UNKNOWN", "UNKNOWN", "", null, false, 1, 1))
        assertEquals(listOf(InboundResult.CONFLICT), receiver.applyPage(page(pet())))
        assertEquals("로컬", db.petDao().find("pet-a")?.name)
    }
}
