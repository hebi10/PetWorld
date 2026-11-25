package com.haebi.petworld.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightChartScaleTest {
    @Test fun fiveLabelsMatchGridAndContainActualValues() {
        val scale = weightChartScale(4400L, 4500L)
        assertEquals(listOf(5000L,4800L,4600L,4400L,4200L),scale.ticks)
        assertTrue(scale.bottom <= 4400L && scale.top >= 4500L)
    }

    @Test fun smallLargeEqualAndWideRangesRemainBounded() {
        listOf(1L to 1L, 10L to 11L, 400L to 400L, 1000L to 9000L,
            100000L to 100000L, 1L to 1000000L).forEach { (low, high) ->
            val scale = weightChartScale(low, high)
            assertEquals(5, scale.ticks.size)
            assertTrue(scale.bottom >= 0 && scale.bottom <= low && scale.top >= high)
            assertTrue(scale.ticks.zipWithNext().all { (a,b) -> a-b == scale.step })
        }
    }
}
