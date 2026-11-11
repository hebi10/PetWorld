package com.haebi.petworld.ui.screens

import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.summarizeCare
import com.haebi.petworld.data.toEntity
import com.haebi.petworld.data.room.CareEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CareStatisticsScreenTest {
    private val now = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()

    private fun record(type: CareType, amount: String, timestamp: Long): CareEntity =
        CareDraft(
            petId = "cat", type = type, amount = amount,
            occurredAt = timestamp, timezoneId = "Asia/Seoul",
        ).toEntity(now = now)

    @Test fun weightSummaryUsesOnlyActualMeasuredWeightAndTimeOrder() {
        val older = record(CareType.WEIGHT, "4.4", now - 2 * 86_400_000L)
        val newer = record(CareType.WEIGHT, "4.5", now - 86_400_000L)
        val water = record(CareType.WATER, "120", now - 1_000L)
        val unmeasured = older.copy(id = "missing", weightGrams = null)
        val summary = weightTrendData(listOf(newer, water, unmeasured, older))

        assertEquals(listOf(older, newer), summary.entries)
        assertEquals(newer, summary.latest)
        assertEquals(4_400L, summary.minimumGrams)
        assertEquals(4_500L, summary.maximumGrams)
    }

    @Test fun emptyAndSingleReadingStayUnknownNotZero() {
        val empty = weightTrendData(emptyList())
        assertTrue(empty.entries.isEmpty())
        assertNull(empty.latest)
        assertNull(empty.minimumGrams)
        assertNull(empty.maximumGrams)

        val single = record(CareType.WEIGHT, "4.25", now - 1_000L)
        val summary = weightTrendData(listOf(single))
        assertEquals(single, summary.latest)
        assertEquals(4_250L, summary.minimumGrams)
        assertEquals(4_250L, summary.maximumGrams)
    }

    @Test fun axisDatesUseRecordedTimezone() {
        val instant = Instant.parse("2026-11-09T23:30:00Z").toEpochMilli()
        val record = record(CareType.WEIGHT, "4.5", instant)
        assertEquals("11/10", weightAxisDate(record))
        assertEquals("11/9", weightAxisDate(record.copy(timezoneId = "UTC")))
    }

    @Test fun changingPeriodFiltersWeightHistoryWithoutChangingOtherSummaries() {
        val today = LocalDate.of(2026, 11, 11)
        val recent = record(CareType.WEIGHT, "4.5", now - 2 * 86_400_000L)
        val old = record(CareType.WEIGHT, "4.2", now - 20 * 86_400_000L)
        val water = record(CareType.WATER, "120", now - 30_000L)
        val records = listOf(old, recent, water)
        val seven = records.filter { CareRange.SEVEN.accepts(it, today) }
        val thirty = records.filter { CareRange.THIRTY.accepts(it, today) }

        assertEquals(1, weightTrendData(seven).entries.size)
        assertEquals(2, weightTrendData(thirty).entries.size)
        assertEquals("120mL", summarizeCare(CareType.WATER, seven))
        assertEquals("120mL", summarizeCare(CareType.WATER, thirty))
        assertFalse(seven.contains(old))
    }
}
