package com.haebi.petworld.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactPetLayoutTest {
    @Test fun smallPhoneUsesCompactCardsAndSettingsActions() {
        assertTrue(useCompactPetLayout(360, 1.0f))
        assertTrue(useCompactPetLayout(360, 1.6f))
        assertTrue(useCompactPetLayout(399, 1.0f))
    }

    @Test fun enlargedFontUsesCompactLayoutEvenOnWidePhone() {
        assertTrue(useCompactPetLayout(420, 1.3f))
        assertTrue(useCompactPetLayout(760, 1.6f))
    }

    @Test fun normalTabletAndLargePhoneKeepOriginalLayout() {
        assertFalse(useCompactPetLayout(400, 1.0f))
        assertFalse(useCompactPetLayout(600, 1.0f))
        assertFalse(useCompactPetLayout(760, 1.2f))
    }

    @Test fun expandedPaneLayoutRequiresAtLeast600dp() {
        assertFalse(useExpandedPetLayout(360, 1.0f))
        assertFalse(useExpandedPetLayout(599, 1.0f))
        assertTrue(useExpandedPetLayout(600, 1.0f))
        assertTrue(useExpandedPetLayout(840, 1.0f))
        assertTrue(useExpandedPetLayout(1200, 1.2f))
    }

    @Test fun largeFontKeepsSingleColumnOnFoldableAndTablet() {
        assertFalse(useExpandedPetLayout(600, 1.3f))
        assertFalse(useExpandedPetLayout(840, 1.6f))
        assertFalse(useExpandedPetLayout(1200, 2.0f))
    }
}
