package com.haebi.petworld.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haebi.petworld.data.CARE_HISTORY_PAGE_SIZE
import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareHistoryReader
import com.haebi.petworld.data.CareHistoryRequest
import com.haebi.petworld.data.CareRepository
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.toEntity
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CareHistoryPagingTest {
    private lateinit var db: PetWorldDatabase
    private lateinit var reader: CareHistoryReader
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java).build()
        for (id in listOf("cat-a", "dog-b")) {
            db.petDao().insert(
                PetEntity(id, id, "CAT", "", null, null, "UNKNOWN", "UNKNOWN",
                    "", null, false, 1L, 1L)
            )
        }
        reader = CareHistoryReader(db)
    }

    @After fun teardown() = db.close()

    private fun record(id: String, pet: String, time: Long, zone: String): CareEntity =
        CareDraft(petId = pet, type = CareType.WATER, occurredAt = time,
            timezoneId = zone, amount = "200")
            .toEntity(now = 1_800_000_000_000L).copy(id = id)

    @Test fun exactDateUsesRecordedZoneAndDetailCannotCrossPet() = runBlocking {
        val instant = Instant.parse("2025-04-01T02:00:00Z").toEpochMilli()
        db.careDao().insertAll(listOf(
            record("utc", "cat-a", instant, "UTC"),
            record("la", "cat-a", instant, "America/Los_Angeles"),
            record("seoul", "cat-a", instant, "Asia/Seoul"),
            record("other", "dog-b", instant, "Asia/Seoul"),
        ))
        val day = LocalDate.parse("2025-04-01")
        val request = CareHistoryRequest("cat-a", CareType.WATER, day, day, 1)
        val first = reader.observe(request).first()
        assertEquals(2, first.totalCount)
        assertEquals(listOf("utc"), first.entries.map { it.id })
        val all = reader.read(request.copy(limit = 10))
        assertEquals(listOf("utc", "seoul"), all.entries.map { it.id })
        assertEquals(1, reader.read(request.copy(firstDay = day.minusDays(1),
            lastDay = day.minusDays(1))).totalCount)
        assertEquals(0, reader.read(request.copy(type = CareType.FOOD)).totalCount)
        val repository = CareRepository(context, db)
        assertNull(repository.observeRecord("cat-a", "other").first())
        assertEquals("la", repository.observeRecord("cat-a", "la").first()?.id)
    }

    @Test fun growingHistoryLoadsOnlyRequestedRowsAtOneTenAndFiftyThousand() = runBlocking {
        val origin = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli()
        val template = record("template", "cat-a", origin, "Asia/Seoul")
        val request = CareHistoryRequest("cat-a", CareType.WATER, null, null)
        var previousSize = 0
        for (size in listOf(1_000, 10_000, 50_000)) {
            for (chunk in (previousSize until size).chunked(1_000)) {
                db.careDao().insertAll(chunk.map { n ->
                    template.copy(id = "record-$n", occurredAt = origin + n * 60_000L)
                })
            }
            previousSize = size
            val first = reader.read(request)
            assertEquals(size, first.totalCount)
            assertEquals(CARE_HISTORY_PAGE_SIZE, first.entries.size)
            assertEquals("record-${size - 1}", first.entries.first().id)
            val second = reader.read(request.copy(limit = CARE_HISTORY_PAGE_SIZE * 2))
            assertEquals(size, second.totalCount)
            assertEquals(100, second.entries.size)
            assertEquals(first.entries, second.entries.take(50))
            assertEquals(100, second.entries.map { it.id }.distinct().size)
        }
        val day = LocalDate.parse("2025-01-01")
        val bounded = reader.read(request.copy(firstDay = day, lastDay = day))
        assertEquals(900, bounded.totalCount)
        assertEquals(50, bounded.entries.size)
        assertTrue(bounded.entries.all { it.petId == "cat-a" })
    }
}
