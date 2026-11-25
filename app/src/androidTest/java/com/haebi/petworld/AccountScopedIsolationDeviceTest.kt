package com.haebi.petworld

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.haebi.petworld.data.CareHistoryRequest
import com.haebi.petworld.data.CareRepository
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.AccountDeletionJournal
import com.haebi.petworld.data.account.DeletionRequestState
import com.haebi.petworld.data.account.AccountDeletionReceipt
import com.haebi.petworld.data.account.AccountDeletionLocalCleanup
import com.haebi.petworld.data.account.AccountCacheCleanupApproval
import com.haebi.petworld.data.account.AccountCacheCleanupResult
import com.haebi.petworld.data.account.AccountStorageQuiescence
import com.haebi.petworld.data.account.photoDirectoryName
import java.io.File
import java.nio.file.Files
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
                val repository = CareRepository(context, database, selected)
                val ownPet = allowed.first()
                val otherPet = if (index % 2 == 0) petsB.first() else petsA.first()
                val history = repository.observeHistory(
                    CareHistoryRequest(ownPet, null, null, null, 2),
                ).first()
                assertEquals(3, history.totalCount)
                assertEquals(2, history.entries.size)
                assertTrue(history.entries.all { it.petId == ownPet })
                assertEquals(0, repository.observeHistory(
                    CareHistoryRequest(otherPet, null, null, null),
                ).first().totalCount)
                val otherDb = if (index % 2 == 0) dbB else dbA
                val foreignRecord = otherDb.careDao().listForPet(otherPet).first()
                assertEquals(null, repository.observeRecord(ownPet, foreignRecord.id).first())
                assertEquals(3, repository.observeRecordCounts().first()[ownPet])
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

    @Test
    fun encryptedDeletionJournalRoundTripsAndRejectsCrossAccountCopies() {
        val a = DeletionRequestState.new("journal-a-${UUID.randomUUID()}")
        val b = DeletionRequestState.new("journal-b-${UUID.randomUUID()}")
        val journal = AccountDeletionJournal(context)
        val root = File(context.noBackupFilesDir, "account-deletion-v1")
        val fileA = File(root, a.account.key + ".bin")
        val fileB = File(root, b.account.key + ".bin")
        try {
            journal.write(a)
            val saved = requireNotNull(AccountDeletionJournal(context).read(a.account))
            assertEquals(a.jobId, saved.jobId)
            assertTrue(a.statusTicket == saved.statusTicket)
            assertTrue(journal.blocksCloud(a.account))
            assertFalse(journal.blocksCloud(b.account))
            assertFalse(String(fileA.readBytes(), Charsets.ISO_8859_1).contains(a.statusTicket))
            val complete = requireNotNull(a.accept(requireNotNull(
                AccountDeletionReceipt.fromServer(a.jobId, "COMPLETE"))))
            journal.write(complete)
            assertEquals(complete.receipt, AccountDeletionJournal(context).read(a.account)?.receipt)
            assertTrue(runCatching { journal.write(a) }.isFailure)
            // A ciphertext copied into another account cannot be decrypted under different AAD.
            check(fileA.copyTo(fileB, overwrite = false).isFile)
            assertTrue(journal.blocksCloud(b.account))
            assertTrue(runCatching { journal.read(b.account) }.isFailure)
            assertTrue(runCatching { journal.write(b) }.isFailure)
            val bytes = fileA.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            fileA.writeBytes(bytes)
            assertTrue(journal.blocksCloud(a.account))
            assertTrue(runCatching { journal.read(a.account) }.isFailure)
        } finally {
            for (file in listOf(fileA, fileB)) {
                file.delete()
                File(file.path + ".bak").delete()
                File(file.path + ".new").delete()
            }
        }
    }

    @Test
    fun scopedCleanupRequiresCompletionAndClosedDbAndPreservesOtherStores() = runBlocking {
        val state = DeletionRequestState.new("cleanup-a-${UUID.randomUUID()}")
        val a = state.account
        val b = LocalAccountScope.fromFirebaseUid("cleanup-b-${UUID.randomUUID()}") as LocalAccountScope.SignedIn
        val guest = PetWorldDatabase.get(context)
        val guestIds = guest.petDao().list().map { it.id }
        val guestSelection = ThemePreferences(context).selectedPetId.first()
        val theme = ThemePreferences(context).themeMode.first()
        val dbA = PetWorldDatabase.get(context, a)
        val dbB = PetWorldDatabase.get(context, b)
        val now = System.currentTimeMillis()
        val petA = "cleanup-pet-a-${UUID.randomUUID()}"
        val petB = "cleanup-pet-b-${UUID.randomUUID()}"
        val photoName = "pet_${UUID.randomUUID()}.jpg"
        val fileA = requireNotNull(PetPhotoStore(context, a).fileFor(photoName))
        val fileB = requireNotNull(PetPhotoStore(context, b).fileFor(photoName))
        val journal = AccountDeletionJournal(context)
        val marker = File(context.noBackupFilesDir, "account-deletion-v1/${a.key}.bin")
        val checkpoint = context.getSharedPreferences("petworld_fb04_import", Context.MODE_PRIVATE)
        val approval = AccountCacheCleanupApproval(a, state.jobId, true)
        var quiet = true
        val cleaner = AccountDeletionLocalCleanup(context, journal, AccountStorageQuiescence { quiet })
        try {
            dbA.petDao().insert(pet(petA, now))
            dbB.petDao().insert(pet(petB, now))
            fileA.writeBytes(byteArrayOf(1,2,3))
            fileB.writeBytes(byteArrayOf(4,5,6))
            ThemePreferences(context,a).selectPet(petA)
            ThemePreferences(context,b).selectPet(petB)
            check(checkpoint.edit().putString(a.key,"qa").putString(b.key,"qa").commit())
            assertEquals(AccountCacheCleanupResult.NOT_READY, cleaner.clear(approval))
            journal.write(state)
            assertEquals(AccountCacheCleanupResult.NOT_READY, cleaner.clear(approval))
            journal.write(requireNotNull(state.accept(requireNotNull(
                AccountDeletionReceipt.fromServer(state.jobId,"COMPLETE")))))
            assertEquals(AccountCacheCleanupResult.NOT_READY, cleaner.clear(approval))
            PetWorldDatabase.closeScope(a)
            quiet = false
            assertEquals(AccountCacheCleanupResult.NOT_READY, cleaner.clear(approval))
            quiet = true
            assertEquals(AccountCacheCleanupResult.NOT_READY, cleaner.clear(approval.copy(explicitlyConfirmed=false)))
            assertEquals(AccountCacheCleanupResult.NOT_READY, cleaner.clear(approval.copy(jobId="0".repeat(64))))
            // A nested symbolic link must not permit deletion outside the account directory.
            val link = File(fileA.parentFile, "foreign")
            Files.createSymbolicLink(link.toPath(), fileB.toPath())
            assertEquals(AccountCacheCleanupResult.RETRY_REQUIRED, cleaner.clear(approval))
            assertTrue(fileA.isFile)
            assertTrue(fileB.isFile)
            check(link.delete())
            assertEquals(AccountCacheCleanupResult.SCOPED_STORAGE_CLEARED, cleaner.clear(approval))
            assertFalse(context.getDatabasePath(a.databaseFileName).exists())
            assertFalse(File(context.filesDir,a.photoDirectoryName).exists())
            assertEquals(null, ThemePreferences(context,a).selectedPetId.first())
            assertFalse(checkpoint.contains(a.key))
            assertTrue(journal.blocksCloud(a))
            assertEquals(AccountCacheCleanupResult.SCOPED_STORAGE_CLEARED, cleaner.clear(approval))
            assertTrue(runCatching { PetWorldDatabase.get(context,a) }.isFailure)
            assertEquals(listOf(petB), dbB.petDao().list().map { it.id })
            assertTrue(fileB.readBytes().contentEquals(byteArrayOf(4,5,6)))
            assertEquals(petB,ThemePreferences(context,b).selectedPetId.first())
            assertTrue(checkpoint.contains(b.key))
            assertEquals(guestIds, guest.petDao().list().map { it.id })
            assertEquals(guestSelection, ThemePreferences(context).selectedPetId.first())
            assertEquals(theme, ThemePreferences(context).themeMode.first())
        } finally {
            ThemePreferences(context,a).selectPet(null)
            ThemePreferences(context,b).selectPet(null)
            checkpoint.edit().remove(a.key).remove(b.key).commit()
            PetWorldDatabase.closeScope(a)
            PetWorldDatabase.closeScope(b)
            context.deleteDatabase(a.databaseFileName)
            context.deleteDatabase(b.databaseFileName)
            File(context.filesDir,a.photoDirectoryName).deleteRecursively()
            File(context.filesDir,b.photoDirectoryName).deleteRecursively()
            marker.delete()
            File(marker.path + ".bak").delete()
            File(marker.path + ".new").delete()
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
