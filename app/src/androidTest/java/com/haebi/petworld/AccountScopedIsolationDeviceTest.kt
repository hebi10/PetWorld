package com.haebi.petworld

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.databaseFileName
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountScopedIsolationDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun guestAndTwoAccountsKeepDbPhotosAndSelectionIsolatedAcrossThirtySwitches() = runBlocking {
        val a = LocalAccountScope.fromFirebaseUid("qa-a-${UUID.randomUUID()}")
        val b = LocalAccountScope.fromFirebaseUid("qa-b-${UUID.randomUUID()}")
        val guest = PetWorldDatabase.get(context)
        val guestPetIds = guest.petDao().list().map { it.id }
        val guestCareIds = guest.careDao().listAll().map { it.id }
        val dbA = PetWorldDatabase.get(context, a)
        val dbB = PetWorldDatabase.get(context, b)
        val prefA = ThemePreferences(context, a)
        val prefB = ThemePreferences(context, b)
        val storeA = PetPhotoStore(context, a)
        val storeB = PetPhotoStore(context, b)
        val photoName = "pet_${UUID.randomUUID()}.jpg"
        val fileA = requireNotNull(storeA.fileFor(photoName))
        val fileB = requireNotNull(storeB.fileFor(photoName))
        val petsA = (1..2).map { "account-a-pet-${UUID.randomUUID()}" }
        val petsB = (1..2).map { "account-b-pet-${UUID.randomUUID()}" }
        val types = listOf("FOOD", "WATER", "ELIMINATION", "ACTIVITY", "WEIGHT")

        try {
            val now = System.currentTimeMillis()
            (petsA to dbA).let { (ids, db) ->
                ids.forEach { db.petDao().insert(pet(it, now)) }
            }
            (petsB to dbB).let { (ids, db) ->
                ids.forEach { db.petDao().insert(pet(it, now)) }
            }
            types.forEachIndexed { index, type ->
                dbA.careDao().insert(care(petsA[index % 2], type, now))
                dbB.careDao().insert(care(petsB[index % 2], type, now))
            }

            fileA.writeBytes(byteArrayOf(1, 2, 3, 4))
            fileB.writeBytes(byteArrayOf(8, 7, 6, 5))
            prefA.selectPet(petsA.first())
            prefB.selectPet(petsB.last())

            assertNotEquals(a.databaseFileName, b.databaseFileName)
            assertNotEquals(fileA.absolutePath, fileB.absolutePath)
            assertFalse(fileA.readBytes().contentEquals(fileB.readBytes()))
            assertEquals(petsA.first(), prefA.selectedPetId.first())
            assertEquals(petsB.last(), prefB.selectedPetId.first())
            assertTrue(dbA.petDao().list().all { it.id in petsA })
            assertTrue(dbB.petDao().list().all { it.id in petsB })
            assertEquals(types.toSet(), dbA.careDao().listAll().map { it.type }.toSet())
            assertEquals(types.toSet(), dbB.careDao().listAll().map { it.type }.toSet())

            repeat(30) { index ->
                val selected = if (index % 2 == 0) a else b
                val database = PetWorldDatabase.get(context, selected)
                val allowed = if (index % 2 == 0) petsA else petsB
                assertEquals(2, database.petDao().list().size)
                assertEquals(5, database.careDao().listAll().size)
                assertTrue(database.petDao().list().all { it.id in allowed })
                assertTrue(database.careDao().listAll().all { it.petId in allowed })
                assertEquals(
                    if (index % 2 == 0) petsA.first() else petsB.last(),
                    ThemePreferences(context, selected).selectedPetId.first(),
                )
            }
            assertEquals(guestPetIds, guest.petDao().list().map { it.id })
            assertEquals(guestCareIds, guest.careDao().listAll().map { it.id })
        } finally {
            prefA.selectPet(null)
            prefB.selectPet(null)
            fileA.delete()
            fileB.delete()
            PetWorldDatabase.closeScope(a)
            PetWorldDatabase.closeScope(b)
            context.deleteDatabase(a.databaseFileName)
            context.deleteDatabase(b.databaseFileName)
        }
    }

    private fun pet(id: String, now: Long) = PetEntity(
        id = id,
        name = "계정 분리 테스트",
        species = "CAT",
        breed = "",
        birthday = null,
        estimatedAgeYears = null,
        sex = "UNKNOWN",
        neuterStatus = "UNKNOWN",
        notes = "",
        photoFile = null,
        isArchived = false,
        createdAt = now,
        updatedAt = now,
    )

    private fun care(petId: String, kind: String, now: Long) = CareEntity(
        id = UUID.randomUUID().toString(),
        petId = petId,
        type = kind,
        occurredAt = now,
        timezoneId = "Asia/Seoul",
        foodKind = null,
        foodProductName = null,
        foodServedMilli = null,
        foodConsumedMilli = null,
        foodUnit = null,
        waterMl = null,
        eliminationKind = null,
        eliminationCount = null,
        activityKind = null,
        activityMinutes = null,
        weightGrams = null,
        weightInputUnit = null,
        photoFile = null,
        note = "",
        createdAt = now,
        updatedAt = now,
    )
}
