package com.haebi.petworld.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CareModelsTest {
    private val now = 1_800_000_000_000L

    private fun draft(type: CareType, amount: String = "1") = CareDraft(
        petId = "pet-a",
        type = type,
        occurredAt = now - 1_000L,
        timezoneId = "Asia/Seoul",
        amount = amount,
    )

    @Test fun foodSeparatesServedConsumedAndUnknown() {
        val offered = draft(CareType.FOOD, "50.125").toEntity(now = now)
        assertEquals(50_125L, offered.foodServedMilli)
        assertNull(offered.foodConsumedMilli)
        assertEquals("제공 50.125g · 섭취 미측정", summarizeCare(CareType.FOOD, listOf(offered)))

        val confirmedNone = draft(CareType.FOOD, "50")
            .copy(consumedAmount = "0").toEntity(now = now)
        assertEquals(0L, confirmedNone.foodConsumedMilli)
        assertTrue(confirmedNone.displayValue().contains("섭취 0g"))
    }

    @Test fun foodMlPortionsPreserveUnitAndMeasuredConsumption() {
        val meal = draft(CareType.FOOD, "55.75")
            .copy(foodUnit = PortionUnit.ML, consumedAmount = "20.5")
            .toEntity(now = now)

        assertEquals("ML", meal.foodUnit)
        assertEquals(55_750L, meal.foodServedMilli)
        assertEquals(20_500L, meal.foodConsumedMilli)
        assertEquals("55.75", meal.toDraft().amount)
        assertEquals("20.5", meal.toDraft().consumedAmount)
        assertEquals(PortionUnit.ML, meal.toDraft().foodUnit)
        assertTrue(meal.displayValue().contains("제공 55.75mL · 섭취 20.5mL"))
    }

    @Test fun rejectsExcessConsumptionAndNegativeQuantity() {
        assertNotNull(CareValidation.error(
            draft(CareType.FOOD, "40").copy(consumedAmount = "50"), now
        ))
        assertNotNull(CareValidation.error(draft(CareType.FOOD, "-3"), now))
        assertNotNull(CareValidation.error(draft(CareType.FOOD, "1.1234"), now))
    }

    @Test fun waterMissingAndMeasuredZeroStayDifferent() {
        val unknown = draft(CareType.WATER, "").copy(waterMeasured = false).toEntity(now = now)
        val measuredZero = draft(CareType.WATER, "0").toEntity(now = now)
        assertNull(unknown.waterMl)
        assertEquals(0L, measuredZero.waterMl)
        assertEquals("0mL · 미측정 1건", summarizeCare(CareType.WATER, listOf(unknown, measuredZero)))
    }

    @Test fun weightUsesWholeGramsAndRestoresInputUnit() {
        val weight = draft(CareType.WEIGHT, "4.615").toEntity(now = now)
        assertEquals(4_615L, weight.weightGrams)
        assertEquals("4.615kg", weight.displayValue())
        assertEquals("4.615", weight.toDraft().amount)
        assertNotNull(CareValidation.error(draft(CareType.WEIGHT, "0"), now))
    }

    @Test fun extraTimeOrInvalidUnitsDoNotWrite() {
        val future = draft(CareType.ACTIVITY, "30").copy(occurredAt = now + 400_000L)
        assertNotNull(CareValidation.error(future, now))
        assertNull(CareValidation.error(draft(CareType.ACTIVITY, "30"), now))
        assertNotNull(CareValidation.error(draft(CareType.ELIMINATION, "0"), now))
    }

    @Test fun summaryUsesOnlyCorrectRecordTypeAndDate() {
        val food = draft(CareType.FOOD, "25").toEntity(now = now)
        val water = draft(CareType.WATER, "120").toEntity(now = now)
        val all = listOf(food, water)
        assertEquals("120mL", summarizeCare(CareType.WATER, all))
        assertEquals("기록 없음", summarizeCare(CareType.WEIGHT, all))
        val expectedDay = java.time.Instant.ofEpochMilli(food.occurredAt)
            .atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        assertEquals(expectedDay, food.localDate())
    }
}
