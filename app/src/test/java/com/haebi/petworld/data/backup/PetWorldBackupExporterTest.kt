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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
class PetWorldBackupExporterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exportsBothPetsEntriesPhotosAndSettingsInOneAuthenticatedFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java)
            .allowMainThreadQueries().build()
        val photoName = "pet_11111111-1111-1111-1111-111111111111.jpg"
        val photoDirectory = File(context.filesDir, "pet_photos").apply { mkdirs() }
        val photo = File(photoDirectory, photoName)
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        photo.writeBytes(bytes)
        val preferences = ThemePreferences(context)
        val password = "백업 확인용 비밀번호".toCharArray()

        try {
            database.petDao().insert(pet("cat", photoName))
            database.petDao().insert(pet("dog", null))
            database.careDao().insert(care("entry-1", "cat", photoName))
            database.careDao().insert(care("entry-2", "dog", null))
            preferences.setThemeMode(ThemeMode.DARK)
            preferences.selectPet("dog")

            val encrypted = ByteArrayOutputStream()
            PetWorldBackupExporter(context, database, preferences)
                .exportToStream(encrypted, password)
            assertTrue(encrypted.size() > 0)
            val verified = EncryptedBackupCodec().decryptToVerifiedFile(
                ByteArrayInputStream(encrypted.toByteArray()), temporary.root, password,
            )
            val archive = mutableMapOf<String, ByteArray>()
            ZipInputStream(verified.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    archive[entry.name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
            verified.delete()

            val data = JSONObject(String(archive.getValue("data.json"), Charsets.UTF_8))
            assertEquals(2, data.getJSONArray("pets").length())
            assertEquals(2, data.getJSONArray("careEntries").length())
            assertEquals("dog", data.getJSONObject("settings").getString("selectedPetId"))
            assertEquals("DARK", data.getJSONObject("settings").getString("themeMode"))
            assertTrue(bytes.contentEquals(archive.getValue("photos/$photoName")))

            val manifest = JSONObject(String(archive.getValue("manifest.json"), Charsets.UTF_8))
            assertEquals(1, manifest.getInt("formatVersion"))
            assertEquals(2, manifest.getInt("schemaVersion"))
            val items = manifest.getJSONArray("files")
            assertEquals(2, items.length())
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val file = archive.getValue(item.getString("path"))
                assertEquals(file.size.toLong(), item.getLong("bytes"))
                val hash = MessageDigest.getInstance("SHA-256").digest(file)
                    .joinToString("") { "%02x".format(it) }
                assertEquals(hash, item.getString("sha256"))
            }
        } finally {
            photo.delete()
            database.close()
            preferences.setThemeMode(ThemeMode.SYSTEM)
            preferences.selectPet(null)
        }
    }

    @Test fun rejectsMissingReferencedPhotoRatherThanCreatingIncompleteBackup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            database.petDao().insert(pet("cat", "pet_missing.jpg"))
            try {
                PetWorldBackupExporter(context, database).exportToStream(
                    ByteArrayOutputStream(), "password".toCharArray(),
                )
                org.junit.Assert.fail("누락된 사진을 포함하는 백업을 허용했습니다.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("사진"))
            }
        } finally {
            database.close()
        }
    }

    private fun pet(id: String, photo: String?) = PetEntity(
        id = id, name = id, species = "CAT", breed = "",
        birthday = null, estimatedAgeYears = null, sex = "UNKNOWN",
        neuterStatus = "UNKNOWN", notes = "", photoFile = photo,
        isArchived = false, createdAt = 1L, updatedAt = 1L,
    )

    private fun care(id: String, petId: String, photo: String?) = CareEntity(
        id = id, petId = petId, type = "WATER", occurredAt = 1L,
        timezoneId = "Asia/Seoul", foodKind = null,
        foodServedMilli = null, foodConsumedMilli = null,
        foodUnit = null, waterMl = 120, eliminationKind = null,
        eliminationCount = null, activityKind = null,
        activityMinutes = null, weightGrams = null,
        weightInputUnit = null, photoFile = photo, note = "",
        createdAt = 1L, updatedAt = 1L,
    )
}
