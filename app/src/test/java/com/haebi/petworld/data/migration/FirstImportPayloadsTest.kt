package com.haebi.petworld.data.migration

import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstImportPayloadsTest {
    private val time = 1794441600000L

    private fun pet(id: String = "pet-1") = PetEntity(
        id = id,
        name = "나비",
        species = "CAT",
        breed = "",
        birthday = null,
        estimatedAgeYears = null,
        sex = "UNKNOWN",
        neuterStatus = "UNKNOWN",
        notes = "",
        photoFile = null,
        isArchived = false,
        createdAt = time,
        updatedAt = time,
    )

    private fun record(
        id: String = "record-1",
        kind: String = "FOOD",
        consumedMilli: Long? = null,
    ) = CareEntity(
        id = id,
        petId = "pet-1",
        type = kind,
        occurredAt = time,
        timezoneId = "Asia/Seoul",
        foodKind = "DRY",
        foodProductName = "기본 사료",
        foodServedMilli = 1500,
        foodConsumedMilli = consumedMilli,
        foodUnit = "G",
        waterMl = null,
        eliminationKind = null,
        eliminationCount = null,
        activityKind = null,
        activityMinutes = null,
        weightGrams = null,
        weightInputUnit = null,
        photoFile = null,
        note = "기록",
        createdAt = time,
        updatedAt = time,
    )

    @Test
    fun firestoreSchemaPreservesExactNumbersAndNullVsZero() {
        val missing = FirstImportPayloads.record(record())
        val measuredZero = FirstImportPayloads.record(record(consumedMilli = 0))
        assertEquals(1500L, missing["foodServedMilli"])
        assertNull(missing["foodConsumedMilli"])
        assertEquals(0L, measuredZero["foodConsumedMilli"])
        assertEquals("Asia/Seoul", measuredZero["timezoneId"])
        assertEquals(time, measuredZero["occurredAtMs"])
        assertEquals("pet-1", measuredZero["petId"])
        assertNotEquals(missing, measuredZero)
    }

    @Test
    fun firebaseLongIntegersCompareEqualButConflictNeverOverwrites() {
        val expected = FirstImportPayloads.pet(pet())
        val server = expected.mapValues { (_, value) ->
            if (value is Int) value.toLong() else value
        }
        assertTrue(FirstImportPayloads.sameRemote(expected, server))
        assertFalse(FirstImportPayloads.sameRemote(expected, server + ("name" to "다른 동물")))
        assertFalse(FirstImportPayloads.sameRemote(expected, server + ("revision" to 1L)))
        assertFalse(FirstImportPayloads.sameRemote(expected, server + ("unauthorized" to "x")))
        assertFalse(FirstImportPayloads.sameRemote(expected, server - "photoFile"))
    }

    @Test
    fun fingerprintIsOrderIndependentAndChangesForPhotoSourceOrSelectedPet() {
        val pet = pet()
        val records = listOf(record("record-b"), record("record-a"))
        val base = FirstImportPayloads.fingerprint(
            listOf(pet), records, mapOf("pet-1/pet_abc.jpg" to "deadbeef"), "pet-1"
        )
        assertEquals(
            base,
            FirstImportPayloads.fingerprint(
                listOf(pet), records.reversed(), mapOf("pet-1/pet_abc.jpg" to "deadbeef"), "pet-1"
            )
        )
        assertNotEquals(
            base,
            FirstImportPayloads.fingerprint(
                listOf(pet), records, mapOf("pet-1/pet_abc.jpg" to "cafebabe"), "pet-1"
            )
        )
        assertNotEquals(
            base,
            FirstImportPayloads.fingerprint(
                listOf(pet), records, mapOf("pet-1/pet_abc.jpg" to "deadbeef"), null
            )
        )
    }

    @Test
    fun oneThousandRecordsRetainUniqueIdsAndDeterministicDigest() {
        val thousand = (1..1000).map { record(id = "record-${it}") }
        val payloads = thousand.map(FirstImportPayloads::record)
        assertEquals(1000, payloads.map { it["id"] }.toSet().size)
        val first = FirstImportPayloads.fingerprint(listOf(pet()), thousand, emptyMap(), null)
        val repeated = FirstImportPayloads.fingerprint(listOf(pet()), thousand.reversed(), emptyMap(), null)
        assertEquals(first, repeated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRulesLengthIsRejectedInsteadOfTruncated() {
        FirstImportPayloads.pet(pet().copy(name = "가".repeat(41)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeMeasuredValueIsRejectedBeforeCloudWrite() {
        FirstImportPayloads.record(record(consumedMilli = -1))
    }

    @Test
    fun emptyDataHasStableDigest() {
        val a = FirstImportPayloads.fingerprint(emptyList(), emptyList(), emptyMap(), null)
        val b = FirstImportPayloads.fingerprint(emptyList(), emptyList(), emptyMap(), null)
        assertEquals(64, a.length)
        assertEquals(a, b)
    }
}
