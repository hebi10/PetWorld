package com.haebi.petworld.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PetIconsTest {
    @Test
    fun navigationIncludesFiveUniqueOutlinedSymbols() {
        assertEquals(5, PetIcons.navigation.size)
        assertEquals(5, PetIcons.navigation.toSet().size)
    }

    @Test
    fun allFiveDailyRecordsHaveIcons() {
        assertEquals(5, PetIcons.daily.size)
        assertEquals(5, PetIcons.daily.toSet().size)
        assertFalse(PetIcons.daily.any { it.name.isBlank() })
    }
}
