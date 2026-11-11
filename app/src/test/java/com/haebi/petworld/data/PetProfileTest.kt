package com.haebi.petworld.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PetProfileTest {
    private val today = LocalDate.of(2026, 11, 9)

    private fun draft() = PetDraft(
        name = "나비", species = PetSpecies.CAT, breed = "",
        birthMode = BirthMode.UNKNOWN, birthday = null, estimatedAgeYears = "",
        sex = PetSex.UNKNOWN, neuterStatus = NeuterStatus.UNKNOWN, notes = ""
    )

    private fun pet(id: String, archived: Boolean = false): PetProfile = PetProfile(
        id = id, name = id, species = PetSpecies.CAT,
        isArchived = archived, createdAt = 1L, updatedAt = 1L,
    )

    @Test fun nameIsRequired() {
        assertEquals("이름을 입력해 주세요.", PetValidation.error(draft().copy(name = "   "), today))
    }

    @Test fun profileNameFieldAndSaveShareValidationRules() {
        assertNull(PetValidation.nameError("나비"))
        assertNull(PetValidation.nameError(" 나비 "))
        assertEquals("이름을 입력해 주세요.", PetValidation.nameError(""))
        assertEquals("이름을 입력해 주세요.", PetValidation.nameError("   "))
        assertEquals("이름은 40자 이내로 입력해 주세요.",
            PetValidation.nameError("가".repeat(41)))
        assertEquals(PetValidation.nameError("   "),
            PetValidation.error(draft().copy(name = "   "), today))
        assertEquals(PetValidation.nameError("가".repeat(41)),
            PetValidation.error(draft().copy(name = "가".repeat(41)), today))
    }

    @Test fun birthdayCannotBeInTheFuture() {
        val value = draft().copy(birthMode = BirthMode.EXACT, birthday = "2027-01-01")
        assertEquals("생일을 올바르게 선택해 주세요.", PetValidation.error(value, today))
    }

    @Test fun estimatedAgeIsNumericAndBounded() {
        val value = draft().copy(birthMode = BirthMode.ESTIMATED)
        assertEquals("추정 나이는 0~100세 사이로 입력해 주세요.",
            PetValidation.error(value.copy(estimatedAgeYears = "-1"), today))
        assertNull(PetValidation.error(value.copy(estimatedAgeYears = "4"), today))
    }

    @Test fun selectionNeverReturnsArchivedPet() {
        val profiles = listOf(pet("a", true), pet("b"), pet("c"))
        assertEquals("b", chooseActivePet(profiles, "a")?.id)
        assertEquals("c", chooseActivePet(profiles, "c")?.id)
        assertEquals("b", chooseActivePet(profiles, "missing")?.id)
        assertNull(chooseActivePet(profiles.take(1), "a"))
    }

    @Test fun birthModeRetainsUnknownAndEstimation() {
        assertEquals(BirthMode.UNKNOWN, pet("a").birthMode())
        assertEquals(BirthMode.ESTIMATED, pet("b").copy(estimatedAgeYears = 5).birthMode())
        assertEquals(BirthMode.EXACT, pet("c").copy(birthday = "2020-10-01").birthMode())
    }
}
