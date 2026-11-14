package com.haebi.petworld.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PetWorldBackupRestorerTest {
    @Test fun encryptedBackupRestoresRecordsSettingsAndPhotos() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java)
            .allowMainThreadQueries().build()
        val prefs = ThemePreferences(context)
        val photoName = "pet_22222222-2222-2222-2222-222222222222.jpg"
        val photo = File(context.filesDir, "pet_photos/$photoName")
        photo.parentFile!!.mkdirs()
        val password = "secure-backup-password".toCharArray()
        try {
            photo.writeBytes(byteArrayOf(4, 5, 6, 7))
            database.petDao().insert(pet("cat", photoName))
            database.petDao().insert(pet("dog", null))
            database.careDao().insert(care("entry", "dog"))
            prefs.setThemeMode(ThemeMode.DARK)
            prefs.selectPet("dog")

            val output = ByteArrayOutputStream()
            PetWorldBackupExporter(context, database, prefs).exportToStream(output, password)
            database.petDao().deleteAll()
            prefs.setThemeMode(ThemeMode.SYSTEM)
            prefs.selectPet(null)
            photo.delete()

            PetWorldBackupRestorer(context, database, prefs)
                .restoreFromStream(ByteArrayInputStream(output.toByteArray()), password)
            assertEquals(setOf("cat", "dog"), database.petDao().list().map { it.id }.toSet())
            assertEquals("dog", database.careDao().listAll().single().petId)
            assertEquals(ThemeMode.DARK, prefs.themeMode.first())
            assertEquals("dog", prefs.selectedPetId.first())
            assertTrue(photo.readBytes().contentEquals(byteArrayOf(4, 5, 6, 7)))
        } finally {
            database.close()
            photo.delete()
            prefs.setThemeMode(ThemeMode.SYSTEM)
            prefs.selectPet(null)
        }
    }

    @Test fun wrongPasswordAndTamperedDataDoNotChangeExistingRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java)
            .allowMainThreadQueries().build()
        val password = "correct-password".toCharArray()
        try {
            database.petDao().insert(pet("original", null))
            val output = ByteArrayOutputStream()
            PetWorldBackupExporter(context, database).exportToStream(output, password)
            val restorer = PetWorldBackupRestorer(context, database)
            val encrypted = output.toByteArray()
            fun assertOriginal() = runBlocking {
                assertEquals(listOf("original"), database.petDao().list().map { it.id })
            }
            try {
                restorer.restoreFromStream(
                    ByteArrayInputStream(encrypted), "wrong-password".toCharArray(),
                )
                fail("잘못된 암호로 복원했습니다.")
            } catch (_: Exception) {
                assertOriginal()
            }
            val corrupted = encrypted.copyOf().also {
                it[it.size - 6] = (it[it.size - 6].toInt() xor 1).toByte()
            }
            try {
                restorer.restoreFromStream(ByteArrayInputStream(corrupted), password)
                fail("변조된 파일을 복원했습니다.")
            } catch (_: Exception) {
                assertOriginal()
            }
        } finally {
            database.close()
        }
    }

    @Test fun inspectingEncryptedBackupDoesNotRestoreOrChangeGuestData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java)
            .allowMainThreadQueries().build()
        val prefs = ThemePreferences(context)
        val photoName = "pet_" + UUID.randomUUID().toString() + ".jpg"
        val photo = File(context.filesDir, "pet_photos/" + photoName)
        photo.parentFile!!.mkdirs()
        val password = "qa-backup-verify-only".toCharArray()
        try {
            photo.writeBytes(byteArrayOf(1, 5, 9, 3))
            database.petDao().insert(pet("verify-cat", photoName))
            database.careDao().insert(care("verify-water", "verify-cat"))
            prefs.selectPet("verify-cat")
            val encrypted = ByteArrayOutputStream()
            PetWorldBackupExporter(context, database, prefs).exportToStream(encrypted, password)
            val backup = encrypted.toByteArray()
            val inspector = PetWorldBackupRestorer(context, database, prefs)
            val summary = inspector.inspectStreamWithoutRestore(ByteArrayInputStream(backup), password)
            assertEquals(listOf("verify-cat"), summary.pets.map { it.id })
            assertEquals(listOf("verify-water"), summary.records.map { it.id })
            assertEquals("verify-cat", summary.selectedPetId)
            val expectedHash = MessageDigest.getInstance("SHA-256").digest(photo.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(mapOf(photoName to expectedHash), summary.photoSha256)
            assertEquals(listOf("verify-cat"), database.petDao().list().map { it.id })
            assertEquals("verify-cat", prefs.selectedPetId.first())

            val tampered = backup.copyOf().also {
                it[it.lastIndex - 6] = (it[it.lastIndex - 6].toInt() xor 1).toByte()
            }
            for ((bytes, key) in listOf(
                backup to "wrong-password".toCharArray(),
                tampered to password,
            )) {
                try {
                    inspector.inspectStreamWithoutRestore(ByteArrayInputStream(bytes), key)
                    fail("검증 실패 파일이 승인됐습니다.")
                } catch (_: Exception) {
                    assertEquals(listOf("verify-cat"), database.petDao().list().map { it.id })
                    assertEquals("verify-cat", prefs.selectedPetId.first())
                }
            }
        } finally {
            database.close()
            photo.delete()
            prefs.selectPet(null)
        }
    }

    private fun pet(id: String, photo: String?) = PetEntity(
        id = id, name = id, species = "CAT", breed = "",
        birthday = null, estimatedAgeYears = null, sex = "UNKNOWN",
        neuterStatus = "UNKNOWN", notes = "", photoFile = photo,
        isArchived = false, createdAt = 1L, updatedAt = 1L,
    )

    private fun care(id: String, petId: String) = CareEntity(
        id = id, petId = petId, type = "WATER", occurredAt = 1L,
        timezoneId = "Asia/Seoul", foodKind = null,
        foodServedMilli = null, foodConsumedMilli = null,
        foodUnit = null, waterMl = 120, eliminationKind = null,
        eliminationCount = null, activityKind = null,
        activityMinutes = null, weightGrams = null,
        weightInputUnit = null, photoFile = null, note = "",
        createdAt = 1L, updatedAt = 1L,
    )
}
