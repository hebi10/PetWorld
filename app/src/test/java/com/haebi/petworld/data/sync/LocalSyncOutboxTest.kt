package com.haebi.petworld.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareRepository
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.PetRepository
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalSyncOutboxTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = LocalAccountScope.fromFirebaseUid("qa-account-a") as LocalAccountScope.SignedIn
    private val other = LocalAccountScope.fromFirebaseUid("qa-account-b") as LocalAccountScope.SignedIn
    private lateinit var db: PetWorldDatabase

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java).build()
        db.petDao().insert(PetEntity(
            "pet-a", "나비", "CAT", "", null, null, "UNKNOWN", "UNKNOWN",
            "", null, false, 1L, 1L,
        ))
    }

    @After fun cleanup() { db.close() }

    private fun draft(amount: String = "0") = CareDraft(
        petId = "pet-a", type = CareType.WATER, occurredAt = 1_700_000_000_000L,
        timezoneId = "Asia/Seoul", amount = amount,
    )

    @Test fun accountMutationsKeepImmutableOrderedSnapshotsAndDeleteJournal() = runBlocking {
        val care = CareRepository(context, db, scope)
        val created = care.save(draft())
        val updated = care.save(draft("12").copy(id = created.id))
        care.delete(updated.id, updated.petId)
        val entries = db.syncOutboxDao().list(scope.key, 50)
        assertEquals(listOf("CREATE", "UPDATE", "DELETE"), entries.map { it.operation })
        assertEquals(3, entries.map { it.opId }.distinct().size)
        assertEquals(entries.map { it.sequence }.sorted(), entries.map { it.sequence })
        assertTrue(entries.all { it.status == SYNC_WAITING_BASELINE && it.expectedRevision == null })
        assertTrue(entries.all { it.entityId == created.id && it.petId == "pet-a" })
        val first = JSONObject(requireNotNull(entries[0].afterJson))
        assertEquals(0L, first.getLong("waterMl"))
        assertTrue(first.has("foodServedMilli") && first.isNull("foodServedMilli"))
        assertEquals("Asia/Seoul", first.getString("timezoneId"))
        assertEquals(0L, JSONObject(requireNotNull(entries[1].beforeJson)).getLong("waterMl"))
        assertEquals(12L, JSONObject(requireNotNull(entries[1].afterJson)).getLong("waterMl"))
        assertEquals(12L, JSONObject(requireNotNull(entries[2].beforeJson)).getLong("waterMl"))
        assertNull(entries[2].afterJson)
        assertNull(db.careDao().find(created.id))
        assertEquals(0, db.syncOutboxDao().observeCount(other.key).first())
        assertEquals(3, db.syncOutboxDao().observeCount(scope.key).first())
    }

    @Test fun guestRemainsLocalAndRejectedSaveDoesNotQueue() = runBlocking {
        val guest = CareRepository(context, db)
        guest.save(draft("4"))
        assertEquals(0, db.syncOutboxDao().observeCount(scope.key).first())
        val signedIn = CareRepository(context, db, scope)
        assertTrue(runCatching { signedIn.save(draft("-1")) }.isFailure)
        assertEquals(0, db.syncOutboxDao().observeCount(scope.key).first())
        assertEquals(1, db.careDao().countForPet("pet-a"))
    }

    @Test fun profileCascadeQueuesChildrenBeforeParentAndKeepsSnapshots() = runBlocking {
        val care = CareRepository(context, db, scope)
        val row = care.save(draft("10"))
        val pet = PetRepository(context, db, scope)
        pet.setArchived("pet-a", true)
        pet.setArchived("pet-a", false)
        pet.deleteForever("pet-a")
        val entries = db.syncOutboxDao().list(scope.key, 50)
        assertEquals(listOf("CREATE", "UPDATE", "UPDATE", "DELETE", "DELETE"),
            entries.map { it.operation })
        val deleted = entries.takeLast(2)
        assertEquals(listOf("CARE", "PET"), deleted.map { it.entityKind })
        assertEquals(row.id, deleted.first().entityId)
        assertEquals("나비", JSONObject(requireNotNull(deleted.last().beforeJson)).getString("name"))
        assertNull(db.petDao().find("pet-a"))
        assertEquals(0, db.careDao().countForPet("pet-a"))
    }

    @Test fun rollbackRemovesBothDomainMutationAndJournal() = runBlocking {
        val before = requireNotNull(db.petDao().find("pet-a"))
        val changed = before.copy(name = "rollback")
        val outbox = LocalSyncOutbox(db, scope)
        val result = runCatching {
            db.withTransaction {
                db.petDao().update(changed)
                outbox.petChanged(before, changed)
                error("forced rollback")
            }
        }
        assertTrue(result.isFailure)
        assertEquals(before, db.petDao().find("pet-a"))
        assertEquals(0, db.syncOutboxDao().observeCount(scope.key).first())
    }
}
