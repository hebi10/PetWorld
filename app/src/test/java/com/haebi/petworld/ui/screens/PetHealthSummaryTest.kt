package com.haebi.petworld.ui.screens

import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class PetHealthSummaryTest {
    private val now = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()

    private fun record(
        id: String,
        type: CareType,
        amount: String,
        occurredAt: Long,
    ): CareEntity = CareDraft(
        id = id,
        petId = "cat",
        type = type,
        amount = amount,
        occurredAt = occurredAt,
        timezoneId = "Asia/Seoul",
    ).toEntity(now = now)

    @Test fun noMeasurementDoesNotInventZeroWeightOrDifference() {
        val water = record("water", CareType.WATER, "120", now - 1_000L)
        val summary = recentWeightSummary(listOf(water))
        assertNull(summary.latest)
        assertNull(summary.previous)
        assertNull(summary.differenceGrams)
    }

    @Test fun singleMeasurementDoesNotInventPreviousValue() {
        val only = record("only", CareType.WEIGHT, "4.5", now - 1_000L)
        val summary = recentWeightSummary(listOf(only))
        assertEquals(only, summary.latest)
        assertNull(summary.previous)
        assertNull(summary.differenceGrams)
    }

    @Test fun recentWeightsFollowObservationTimeAndIgnoreUnmeasuredRows() {
        val older = record("older", CareType.WEIGHT, "4.4", now - 3_000L)
        val latest = record("latest", CareType.WEIGHT, "4.5", now - 1_000L)
        val unrelated = record("water", CareType.WATER, "120", now - 500L)
        val missing = latest.copy(id = "unmeasured", occurredAt = now, weightGrams = null)
        val summary = recentWeightSummary(listOf(latest, missing, unrelated, older))

        assertEquals(latest, summary.latest)
        assertEquals(older, summary.previous)
        assertEquals(100L, summary.differenceGrams)
        assertEquals("+0.1kg", formatWeightDifference(summary.differenceGrams!!))
    }

    @Test fun negativeAndUnchangedWeightsAreFormattedWithoutDiagnosis() {
        val heavier = record("heavy", CareType.WEIGHT, "4.75", now - 3_000L)
        val lighter = record("light", CareType.WEIGHT, "4.5", now - 2_000L)
        val unchanged = record("same", CareType.WEIGHT, "4.5", now - 1_000L)

        val loss = recentWeightSummary(listOf(lighter, heavier))
        assertEquals(-250L, loss.differenceGrams)
        assertEquals("-0.25kg", formatWeightDifference(loss.differenceGrams!!))

        val stable = recentWeightSummary(listOf(unchanged, lighter))
        assertEquals(0L, stable.differenceGrams)
        assertEquals("0kg", formatWeightDifference(stable.differenceGrams!!))
    }

    @Test fun equalOccurrenceTimesUseCreationTimeAndStableId() {
        val first = record("a", CareType.WEIGHT, "4.4", now - 1_000L)
            .copy(createdAt = now - 50_000L)
        val second = record("b", CareType.WEIGHT, "4.5", now - 1_000L)
            .copy(createdAt = now - 40_000L)
        val summary = recentWeightSummary(listOf(second, first))

        assertEquals(second, summary.latest)
        assertEquals(first, summary.previous)
        assertEquals(100L, summary.differenceGrams)
    }
}
