package com.haebi.petworld.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PetWorldRecoveryJournalTest {
    @Test fun restartRecoversOriginalProfilesAndSettingsOnlyOnce() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java)
            .allowMainThreadQueries().build()
        val prefs = ThemePreferences(context)
        val journal = PetWorldRecoveryJournal(context)
        try {
            db.petDao().insert(pet("before"))
            prefs.setThemeMode(ThemeMode.DARK)
            prefs.selectPet("before")
            PetWorldMutationGate.mutex.withLock {
                journal.begin(db, prefs)
                db.petDao().deleteAll()
                db.petDao().insert(pet("after"))
                prefs.applyRestoredSettings(ThemeMode.LIGHT, "after")
            }
            assertTrue(journal.hasPending())
            assertEquals("after", db.petDao().list().single().id)
            assertTrue(journal.recoverIfPending(db, prefs))
            assertEquals("before", db.petDao().list().single().id)
            assertEquals("before", prefs.selectedPetId.first())
            assertEquals(ThemeMode.DARK, prefs.themeMode.first())
            assertFalse(journal.hasPending())
            assertFalse(journal.recoverIfPending(db, prefs))
        } finally {
            db.close()
            prefs.setThemeMode(ThemeMode.SYSTEM)
            prefs.selectPet(null)
            if (journal.hasPending()) journal.complete()
        }
    }

    private fun pet(id: String) = PetEntity(
        id = id, name = id, species = "CAT", breed = "",
        birthday = null, estimatedAgeYears = null, sex = "UNKNOWN",
        neuterStatus = "UNKNOWN", notes = "", photoFile = null,
        isArchived = false, createdAt = 1L, updatedAt = 1L,
    )
}
