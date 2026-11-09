package com.haebi.petworld.data.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyRoomMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "petworld_migration_test.db"
    private lateinit var database: PetWorldDatabase

    @Before fun prepareLegacyV1() {
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                """CREATE TABLE pets (
                   id TEXT PRIMARY KEY NOT NULL,
                   name TEXT NOT NULL,
                   species TEXT NOT NULL,
                   breed TEXT NOT NULL DEFAULT '',
                   birthday TEXT,
                   estimated_age_years INTEGER,
                   sex TEXT NOT NULL,
                   neuter_status TEXT NOT NULL,
                   notes TEXT NOT NULL DEFAULT '',
                   photo_file TEXT,
                   is_archived INTEGER NOT NULL DEFAULT 0,
                   created_at INTEGER NOT NULL,
                   updated_at INTEGER NOT NULL
                )""".trimIndent()
            )
            legacy.execSQL(
                "CREATE INDEX idx_pets_archived_updated ON pets(is_archived, updated_at DESC)"
            )
            legacy.execSQL(
                """INSERT INTO pets (
                    id, name, species, breed, birthday, estimated_age_years, sex,
                    neuter_status, notes, photo_file, is_archived, created_at, updated_at
                   ) VALUES (
                    'cat-1','나비','CAT','코리안숏헤어',NULL,4,'FEMALE','YES',
                    '주의사항','pet_test.jpg',0,100,100
                   )""".trimIndent()
            )
            legacy.execSQL(
                """INSERT INTO pets (
                    id, name, species, breed, birthday, estimated_age_years, sex,
                    neuter_status, notes, photo_file, is_archived, created_at, updated_at
                   ) VALUES (
                    'dog-2','초코','DOG','믹스','2020-01-01',NULL,'MALE','NO',
                    '',NULL,1,200,200
                   )""".trimIndent()
            )
            legacy.version = 1
        }
        database = Room.databaseBuilder(context, PetWorldDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    @After fun cleanUp() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test fun existingProfilesSurviveAndRoomCanAddCareRecords() = runBlocking {
        val pets = database.petDao().list()
        assertEquals(2, pets.size)
        val cat = pets.first { it.id == "cat-1" }
        assertEquals("나비", cat.name)
        assertEquals("pet_test.jpg", cat.photoFile)
        assertEquals(4, cat.estimatedAgeYears)
        assertFalse(cat.isArchived)
        assertTrue(pets.first { it.id == "dog-2" }.isArchived)

        database.careDao().insert(
            CareEntity(
                id = "care-1", petId = "cat-1", type = "WATER",
                occurredAt = 1_700_000_000_000L,
                timezoneId = "Asia/Seoul",
                foodKind = null, foodServedMilli = null, foodConsumedMilli = null,
                foodUnit = null, waterMl = 0L,
                eliminationKind = null, eliminationCount = null,
                activityKind = null, activityMinutes = null,
                weightGrams = null, weightInputUnit = null,
                note = "", createdAt = 10L, updatedAt = 10L,
            )
        )
        assertEquals(1, database.careDao().countForPet("cat-1"))
        assertEquals(0, database.careDao().countForPet("dog-2"))
        assertEquals(1, database.petDao().delete("cat-1"))
        assertEquals(0, database.careDao().countForPet("cat-1"))
        assertEquals(1, database.petDao().list().size)
    }

    @Test fun profileUpdateDoesNotDiscardOriginalUuidOrPhoto() = runBlocking {
        val cat = database.petDao().find("cat-1")!!
        assertEquals(1, database.petDao().update(cat.copy(name = "나비 수정")))
        val updated = database.petDao().find("cat-1")!!
        assertEquals("나비 수정", updated.name)
        assertEquals("pet_test.jpg", updated.photoFile)
        assertEquals("cat-1", updated.id)
    }
}
