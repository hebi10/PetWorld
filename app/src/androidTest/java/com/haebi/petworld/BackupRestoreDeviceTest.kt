package com.haebi.petworld

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.backup.PetWorldBackupExporter
import com.haebi.petworld.data.backup.PetWorldBackupRestorer
import com.haebi.petworld.data.backup.PetWorldMutationGate
import com.haebi.petworld.data.backup.PetWorldRecoveryJournal
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupRestoreDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: PetWorldDatabase
    private lateinit var name: String
    private val settings by lazy { ThemePreferences(context) }

    @Before fun initialize() = runBlocking {
        name = "petworld-device-qa-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(context, PetWorldDatabase::class.java, name).build()
        settings.setThemeMode(ThemeMode.SYSTEM)
        settings.selectPet(null)
    }

    @After fun cleanup() = runBlocking {
        database.close()
        context.deleteDatabase(name)
        settings.setThemeMode(ThemeMode.SYSTEM)
        settings.selectPet(null)
        val pending = PetWorldRecoveryJournal(context)
        if (pending.hasPending()) pending.complete()
    }

    @Test fun encryptedRoundTripAndRefreshFromRoom() = runBlocking {
        val id = UUID.randomUUID().toString()
        val photoName = "pet_${UUID.randomUUID()}.jpg"
        val photo = File(context.filesDir, "pet_photos/$photoName")
        photo.parentFile!!.mkdirs()
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
        }
        val originalPhoto = photo.readBytes()
        database.petDao().insert(pet(id, photoName))
        settings.selectPet(id)
        settings.setThemeMode(ThemeMode.DARK)
        val bytes = ByteArrayOutputStream().also {
            PetWorldBackupExporter(context, database, settings)
                .exportToStream(it, "qa-secret-password".toCharArray())
        }.toByteArray()

        database.petDao().deleteAll()
        photo.delete()
        settings.selectPet(null)
        settings.setThemeMode(ThemeMode.LIGHT)
        PetWorldBackupRestorer(context, database, settings)
            .restoreFromStream(ByteArrayInputStream(bytes), "qa-secret-password".toCharArray())

        assertEquals(id, database.petDao().list().single().id)
        assertEquals(id, settings.selectedPetId.first())
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
        assertTrue(photo.readBytes().contentEquals(originalPhoto))
        photo.delete()
        assertFalse(PetWorldRecoveryJournal(context).hasPending())
    }

    @Test fun interruptedRestoreRecoversPreviousStateAfterDatabaseReopen() = runBlocking {
        val original = UUID.randomUUID().toString()
        database.petDao().insert(pet(original))
        settings.selectPet(original)
        val journal = PetWorldRecoveryJournal(context)
        PetWorldMutationGate.mutex.withLock {
            journal.begin(database, settings)
            database.petDao().deleteAll()
            database.petDao().insert(pet(UUID.randomUUID().toString()))
            settings.applyRestoredSettings(ThemeMode.LIGHT, null)
        }
        assertTrue(journal.hasPending())
        database.close()
        database = Room.databaseBuilder(context, PetWorldDatabase::class.java, name).build()

        assertTrue(journal.recoverIfPending(database, settings))
        assertEquals(original, database.petDao().list().single().id)
        assertEquals(original, settings.selectedPetId.first())
        assertFalse(journal.hasPending())
    }

    @Test fun wrongPasswordDoesNotReplaceData() = runBlocking {
        val original = UUID.randomUUID().toString()
        database.petDao().insert(pet(original))
        val output = ByteArrayOutputStream()
        PetWorldBackupExporter(context, database, settings)
            .exportToStream(output, "correct-password".toCharArray())
        val result = runCatching {
            PetWorldBackupRestorer(context, database, settings)
                .restoreFromStream(
                    ByteArrayInputStream(output.toByteArray()),
                    "incorrect-password".toCharArray(),
                )
        }
        assertTrue(result.isFailure)
        assertEquals(original, database.petDao().list().single().id)
        assertFalse(PetWorldRecoveryJournal(context).hasPending())
    }

    private fun pet(id: String, photo: String? = null) = PetEntity(
        id = id, name = "QA 고양이", species = "CAT", breed = "",
        birthday = null, estimatedAgeYears = null, sex = "UNKNOWN",
        neuterStatus = "UNKNOWN", notes = "", photoFile = photo,
        isArchived = false, createdAt = 1L, updatedAt = 1L,
    )
}
