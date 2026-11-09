package com.haebi.petworld.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun unknownStoredValueFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue("NOT_SUPPORTED"))
    }

    @Test
    fun validStoredValuesRoundTrip() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromStoredValue(mode.name))
        }
    }

    @Test
    fun lightAndDarkOverrideSystem() {
        assertFalse(ThemeMode.LIGHT.shouldUseDark(true))
        assertTrue(ThemeMode.DARK.shouldUseDark(false))
    }

    @Test
    fun systemModeUsesDevicePreference() {
        assertFalse(ThemeMode.SYSTEM.shouldUseDark(false))
        assertTrue(ThemeMode.SYSTEM.shouldUseDark(true))
    }
}
