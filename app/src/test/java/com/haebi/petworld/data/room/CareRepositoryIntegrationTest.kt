package com.haebi.petworld.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareRepository
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.displayValue
import com.haebi.petworld.data.summarizeCare
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CareRepositoryIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: PetWorldDatabase
    private lateinit var care: CareRepository

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java).build()
        database.petDao().insert(
            PetEntity(
                "cat-a", "나비", "CAT", "", null, null, "UNKNOWN", "UNKNOWN",
                "", null, false, 1L, 1L,
            )
        )
        database.petDao().insert(
            PetEntity(
                "dog-b", "초코", "DOG", "", null, null, "UNKNOWN", "UNKNOWN",
                "", null, false, 2L, 2L,
            )
        )
        care = CareRepository(database)
    }

    @After fun teardown() { database.close() }

    private fun water(petId: String, amount: String = "200") = CareDraft(
        petId = petId,
        type = CareType.WATER,
        occurredAt = 1_700_000_000_000L,
        timezoneId = "Asia/Seoul",
        amount = amount,
    )

    @Test fun createEditDeleteAreScopedToOriginalPet() = runBlocking {
        val first = care.save(water("cat-a"))
        val second = care.save(water("dog-b", "120"))
        assertEquals(1, database.careDao().countForPet("cat-a"))
        assertEquals(1, database.careDao().countForPet("dog-b"))
        assertEquals(2, care.observeAll().first().size)
        assertEquals("200mL", first.displayValue())

        val changed = care.save(water("cat-a", "250").copy(id = first.id))
        assertEquals(first.id, changed.id)
        assertEquals(first.createdAt, changed.createdAt)
        assertEquals(250L, database.careDao().find(first.id)?.waterMl)

        val crossPet = runCatching {
            care.save(water("dog-b", "500").copy(id = first.id))
        }
        assertTrue(crossPet.isFailure)
        assertEquals(250L, database.careDao().find(first.id)?.waterMl)
        assertEquals(120L, database.careDao().find(second.id)?.waterMl)

        care.delete(first.id, "cat-a")
        assertEquals(0, database.careDao().countForPet("cat-a"))
        assertEquals(1, database.careDao().countForPet("dog-b"))
        assertEquals("기록 없음", summarizeCare(CareType.WATER, care.observeAll().first().filter { it.petId == "cat-a" }))
    }

    @Test fun deleteOnlyWithMatchingPetIdAndArchivedPetCannotRecord() = runBlocking {
        val entry = care.save(water("cat-a"))
        assertTrue(runCatching { care.delete(entry.id, "dog-b") }.isFailure)
        assertNotNull(database.careDao().find(entry.id))
        val cat = database.petDao().find("cat-a")!!
        database.petDao().update(cat.copy(isArchived = true))
        assertTrue(runCatching { care.save(water("cat-a", "300")) }.isFailure)
        assertEquals(1, database.careDao().countForPet("cat-a"))
    }
}
