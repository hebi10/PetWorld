package com.haebi.petworld.data.migration

import com.haebi.petworld.data.backup.PetWorldBackupInspection
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FirstImportBackupVerifierTest {
    private val photo = "pet_11111111-1111-4111-8111-111111111111.jpg"
    private val pet = PetEntity(
        id = "pet-a", name = "나비", species = "CAT", breed = "", birthday = null,
        estimatedAgeYears = null, sex = "UNKNOWN", neuterStatus = "UNKNOWN",
        notes = "", photoFile = photo, isArchived = false, createdAt = 10L, updatedAt = 10L,
    )
    private val record = CareEntity(
        id = "record-a", petId = "pet-a", type = "WATER", occurredAt = 10L,
        timezoneId = "Asia/Seoul", foodKind = null, foodProductName = null,
        foodServedMilli = null, foodConsumedMilli = null, foodUnit = null, waterMl = 0,
        eliminationKind = null, eliminationCount = null, activityKind = null,
        activityMinutes = null, weightGrams = null, weightInputUnit = null,
        photoFile = photo, note = "", createdAt = 10L, updatedAt = 10L,
    )
    private val digest = "a".repeat(64)
    private val inspection = PetWorldBackupInspection(
        pets = listOf(pet), records = listOf(record),
        selectedPetId = pet.id, photoSha256 = mapOf(photo to digest),
    )
    private val source = FirstImportPayloads.fingerprint(
        inspection.pets, inspection.records,
        mapOf(pet.id + "/" + photo to digest), pet.id,
    )

    @Test fun verifiedBackupEvidenceMatchesExactGuestAndAccount() {
        val evidence = FirstImportBackupVerifier.verify(
            source, "account-a", source, inspection,
        )
        assertEquals(source, evidence.sourceFingerprint)
        FirstImportBackupVerifier.requireApproved(source, "account-a", evidence)
        rejects { FirstImportBackupVerifier.requireApproved(source, "account-b", evidence) }
        rejects { FirstImportBackupVerifier.requireApproved("outdated", "account-a", evidence) }
    }

    @Test fun changedPhotoOrMissingFileCannotApproveBackup() {
        rejects {
            FirstImportBackupVerifier.verify(
                source, "account-a", source,
                inspection.copy(photoSha256 = mapOf(photo to "b".repeat(64))),
            )
        }
        rejects {
            FirstImportBackupVerifier.verify(
                source, "account-a", source, inspection.copy(photoSha256 = emptyMap()),
            )
        }
    }

    @Test fun oldGuestSnapshotAndMeasuredNullVersusZeroAreRejected() {
        rejects { FirstImportBackupVerifier.verify(source, "account-a", "changed", inspection) }
        rejects {
            FirstImportBackupVerifier.verify(
                source, "account-a", source,
                inspection.copy(records = listOf(record.copy(waterMl = null))),
            )
        }
        rejects {
            FirstImportBackupVerifier.verify(
                source, "account-a", source, inspection.copy(selectedPetId = null),
            )
        }
    }

    private fun rejects(action: () -> Unit) {
        try {
            action()
            fail("서로 다른 원본/백업/계정을 허용했습니다.")
        } catch (_: IllegalArgumentException) {
        }
    }
}
