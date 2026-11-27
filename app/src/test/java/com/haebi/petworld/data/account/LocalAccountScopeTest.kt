package com.haebi.petworld.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAccountScopeTest {
    @Test
    fun guestKeepsExactLegacyPathsAndSettings() {
        assertEquals("petworld_profiles.db", LocalAccountScope.Guest.databaseFileName)
        assertEquals("pet_photos", LocalAccountScope.Guest.photoDirectoryName)
        assertEquals("selected_pet_id", LocalAccountScope.Guest.selectedPetPreferenceKey)
        assertTrue(LocalAccountScope.Guest.isGuest)
    }

    @Test
    fun accountsAreStableDifferentAndDoNotRevealUidInPaths() {
        val uidA = "firebase-account-a@example"
        val uidB = "firebase-account-b@example"
        val a = LocalAccountScope.fromFirebaseUid(uidA)
        val b = LocalAccountScope.fromFirebaseUid(uidB)
        assertEquals(a, LocalAccountScope.fromFirebaseUid(uidA))
        assertNotEquals(a, b)
        assertNotEquals(a.databaseFileName, b.databaseFileName)
        assertNotEquals(a.photoDirectoryName, b.photoDirectoryName)
        assertNotEquals(a.selectedPetPreferenceKey, b.selectedPetPreferenceKey)
        assertFalse(a.databaseFileName.contains(uidA))
        assertFalse(a.photoDirectoryName.contains(uidA))
        assertTrue(a.databaseFileName.matches(Regex("petworld_account_[0-9a-f]{64}\\.db")))
        assertTrue(a.photoDirectoryName.matches(Regex("pet_photos_accounts/[0-9a-f]{64}")))
        assertFalse(a.isGuest)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyIdentityIsRejected() {
        LocalAccountScope.fromFirebaseUid("")
    }

    @Test
    fun repeatedSwitchesNeverChangeStableScope() {
        val accounts = listOf("account-a", "account-b", "account-c")
        val baseline = accounts.map(LocalAccountScope::fromFirebaseUid)
        repeat(30) { index ->
            assertEquals(baseline[index % baseline.size],
                LocalAccountScope.fromFirebaseUid(accounts[index % accounts.size]))
        }
    }
}
