package com.haebi.petworld.ui.screens

import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.EliminationKind
import com.haebi.petworld.data.displayValue
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.toDraft
import com.haebi.petworld.data.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CareRecordFilterScopeTest {
    private val today = LocalDate.of(2026, 11, 11)
    private val now = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()

    private fun record(
        type: CareType,
        timestamp: Long = now - 60_000L,
    ): CareEntity = CareDraft(
        id = "entry-${type.name}-${timestamp}",
        petId = "cat",
        type = type,
        occurredAt = timestamp,
        timezoneId = "Asia/Seoul",
        amount = when (type) {
            CareType.FOOD -> "40"
            CareType.WATER -> "120"
            CareType.ELIMINATION -> "2"
            CareType.ACTIVITY -> "15"
            CareType.WEIGHT -> "4.5"
        },
    ).toEntity(now = now)

    @Test fun currentMvpSupportsOneTypeOrAllFiveWithoutMutatingRecords() {
        val original = CareType.entries.map { record(it) }
        val onlyWater = filterCareRecords(original, CareType.WATER, CareRange.SEVEN, today)
        assertEquals(listOf(CareType.WATER.name), onlyWater.map { it.type })
        assertEquals(original, filterCareRecords(original, null, CareRange.SEVEN, today))
        assertEquals(5, original.size)
    }

    @Test fun dateWindowsIncludeTheirBoundariesAndExcludeOlderEntries() {
        val todayEntry = record(CareType.WATER)
        val sevenDaysBack = record(
            CareType.WATER,
            Instant.parse("2026-11-05T12:00:00Z").toEpochMilli(),
        )
        val thirtyDaysBack = record(
            CareType.WATER,
            Instant.parse("2026-10-13T12:00:00Z").toEpochMilli(),
        )
        val earlier = record(
            CareType.WATER,
            Instant.parse("2026-10-12T12:00:00Z").toEpochMilli(),
        )
        val entries = listOf(earlier, thirtyDaysBack, sevenDaysBack, todayEntry)

        assertEquals(listOf(todayEntry), filterCareRecords(entries, null, CareRange.TODAY, today))
        assertEquals(
            listOf(sevenDaysBack, todayEntry),
            filterCareRecords(entries, CareType.WATER, CareRange.SEVEN, today),
        )
        assertEquals(
            listOf(thirtyDaysBack, sevenDaysBack, todayEntry),
            filterCareRecords(entries, CareType.WATER, CareRange.THIRTY, today),
        )
        assertEquals(entries, filterCareRecords(entries, null, CareRange.ALL, today))
    }

    @Test fun allIncludesValidRecordCrossingMidnightInItsOwnTimeZone() {
        val justBeforeMidnight = Instant.parse("2026-11-11T14:59:00Z").toEpochMilli()
        val justAfterMidnight = Instant.parse("2026-11-11T15:01:00Z").toEpochMilli()
        val tomorrow = CareDraft(
            id = "tomorrow",
            petId = "cat",
            type = CareType.WATER,
            amount = "120",
            occurredAt = justAfterMidnight,
            timezoneId = "Asia/Seoul",
        ).toEntity(now = justBeforeMidnight)

        assertEquals(LocalDate.of(2026, 11, 12), tomorrow.localDate())
        assertTrue(CareRange.ALL.accepts(tomorrow, today))
        assertFalse(CareRange.TODAY.accepts(tomorrow, today))
        assertEquals(listOf(tomorrow), filterCareRecords(listOf(tomorrow), null, CareRange.ALL, today))
    }

    @Test fun eliminationUsesOptionalPhotoAndObservationMemoWithoutNewSchema() {
        val original = CareDraft(
            id = "elimination",
            petId = "cat",
            type = CareType.ELIMINATION,
            occurredAt = now - 60_000L,
            timezoneId = "Asia/Seoul",
            eliminationKind = EliminationKind.STOOL,
            amount = "2",
            note = "평소보다 무른 상태",
        ).toEntity(now = now, photoFile = "local-elimination.jpg")

        assertEquals("STOOL", original.eliminationKind)
        assertEquals(2, original.eliminationCount)
        assertEquals("local-elimination.jpg", original.photoFile)
        assertEquals("평소보다 무른 상태", original.note)
        assertEquals("평소보다 무른 상태", original.toDraft().note)
        assertEquals(EliminationKind.STOOL, original.toDraft().eliminationKind)
        assertTrue(original.displayValue().contains("대변 2회"))
    }
}
