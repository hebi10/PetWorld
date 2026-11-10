package com.haebi.petworld.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import java.io.File
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
        val dbName = "recovery-test-${java.util.UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, PetWorldDatabase::class.java, dbName)
            .allowMainThreadQueries().build()
        var reopened: PetWorldDatabase? = null
        val prefs = ThemePreferences(context)
        val journal = PetWorldRecoveryJournal(context)
        val photos = File(context.filesDir, "pet_photos").apply { mkdirs() }
        val originalPhoto = File(photos, "pet_${java.util.UUID.randomUUID()}.jpg")
        val interruptedPhoto = File(photos, "pet_${java.util.UUID.randomUUID()}.jpg")
        try {
            originalPhoto.writeBytes(byteArrayOf(1, 2, 3))
            db.petDao().insert(pet("before"))
            prefs.setThemeMode(ThemeMode.DARK)
            prefs.selectPet("before")
            PetWorldMutationGate.mutex.withLock {
                journal.begin(db, prefs)
                interruptedPhoto.writeBytes(byteArrayOf(4, 5, 6))
                db.petDao().deleteAll()
                db.petDao().insert(pet("after"))
                prefs.applyRestoredSettings(ThemeMode.LIGHT, "after")
            }
            assertTrue(journal.hasPending())
            assertEquals("after", db.petDao().list().single().id)
            db.close()
            reopened = Room.databaseBuilder(context, PetWorldDatabase::class.java, dbName)
                .allowMainThreadQueries().build()
            assertTrue(journal.recoverIfPending(reopened!!, prefs))
            assertEquals("before", reopened!!.petDao().list().single().id)
            assertEquals("before", prefs.selectedPetId.first())
            assertEquals(ThemeMode.DARK, prefs.themeMode.first())
            assertFalse(journal.hasPending())
            assertTrue(originalPhoto.exists())
            assertFalse(interruptedPhoto.exists())
            assertFalse(journal.recoverIfPending(reopened!!, prefs))
        } finally {
            reopened?.close()
            db.close()
            context.deleteDatabase(dbName)
            originalPhoto.delete()
            interruptedPhoto.delete()
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
