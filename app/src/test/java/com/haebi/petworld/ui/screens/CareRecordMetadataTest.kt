package com.haebi.petworld.ui.screens

import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CareRecordMetadataTest {
    private val occurred = Instant.parse("2026-11-09T23:30:00Z").toEpochMilli()
    private val created = Instant.parse("2026-11-10T01:00:00Z").toEpochMilli()

    @Test fun occurrenceCreationAndUpdateUseRecordsOwnTimeZone() {
        val record = CareDraft(
            petId = "cat",
            type = CareType.WATER,
            amount = "120",
            occurredAt = occurred,
            timezoneId = "Asia/Seoul",
        ).toEntity(now = created)

        assertEquals("2026-11-10 08:30", record.displayDateTime())
        assertEquals("2026-11-10 10:00", record.displayRecordTime(record.createdAt))
        assertEquals("2026-11-10 10:00", record.displayRecordTime(record.updatedAt))
        assertFalse(record.hasBeenEdited())
        assertEquals("2026-11-09 23:30", record.copy(timezoneId = "UTC").displayDateTime())
        assertEquals(
            "2026-11-10 01:00",
            record.copy(timezoneId = "UTC").displayRecordTime(record.createdAt),
        )
    }

    @Test fun editedRecordPreservesCreationTimeAndExposesUpdatedTime() {
        val original = CareDraft(
            petId = "cat",
            type = CareType.WATER,
            amount = "120",
            occurredAt = occurred,
            timezoneId = "Asia/Seoul",
        ).toEntity(now = created)
        val editedAt = created + 60_000L
        val edited = original.copy(updatedAt = editedAt)

        assertEquals(original.createdAt, edited.createdAt)
        assertTrue(edited.hasBeenEdited())
        assertEquals("2026-11-10 10:01", edited.displayRecordTime(edited.updatedAt))
        assertFalse(original.copy(updatedAt = created - 1L).hasBeenEdited())
    }
}
