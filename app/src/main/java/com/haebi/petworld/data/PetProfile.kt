package com.haebi.petworld.data

import java.time.LocalDate
import java.util.UUID

enum class PetSpecies(val label: String) {
    CAT("고양이"), DOG("강아지"), OTHER("기타")
}

enum class PetSex(val label: String) {
    UNKNOWN("모름"), FEMALE("암컷"), MALE("수컷")
}

enum class NeuterStatus(val label: String) {
    UNKNOWN("모름"), YES("완료"), NO("하지 않음")
}

enum class BirthMode(val label: String) {
    UNKNOWN("모름"), EXACT("생일"), ESTIMATED("추정 나이")
}

data class PetProfile(
    val id: String,
    val name: String,
    val species: PetSpecies,
    val breed: String = "",
    val birthday: String? = null,
    val estimatedAgeYears: Int? = null,
    val sex: PetSex = PetSex.UNKNOWN,
    val neuterStatus: NeuterStatus = NeuterStatus.UNKNOWN,
    val notes: String = "",
    val photoFile: String? = null,
    val isArchived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

data class PetDraft(
    val id: String? = null,
    val name: String,
    val species: PetSpecies,
    val breed: String,
    val birthMode: BirthMode,
    val birthday: String?,
    val estimatedAgeYears: String,
    val sex: PetSex,
    val neuterStatus: NeuterStatus,
    val notes: String,
    val newPhotoUri: String? = null,
    val removePhoto: Boolean = false,
)

object PetValidation {
    fun error(draft: PetDraft, today: LocalDate = LocalDate.now()): String? {
        if (draft.name.trim().isBlank()) return "이름을 입력해 주세요."
        if (draft.name.trim().length > 40) return "이름은 40자 이내로 입력해 주세요."
        if (draft.breed.length > 80) return "품종은 80자 이내로 입력해 주세요."
        if (draft.notes.length > 2_000) return "주의사항은 2,000자 이내로 입력해 주세요."
        if (draft.birthMode == BirthMode.EXACT) {
            val date = try { LocalDate.parse(draft.birthday.orEmpty()) } catch (_: Exception) { null }
            if (date == null || date.isAfter(today) || date.year < 1900) {
                return "생일을 올바르게 선택해 주세요."
            }
        }
        if (draft.birthMode == BirthMode.ESTIMATED) {
            val years = draft.estimatedAgeYears.toIntOrNull()
            if (years == null || years !in 0..100) {
                return "추정 나이는 0~100세 사이로 입력해 주세요."
            }
        }
        return null
    }
}

fun PetProfile.birthMode(): BirthMode = when {
    birthday != null -> BirthMode.EXACT
    estimatedAgeYears != null -> BirthMode.ESTIMATED
    else -> BirthMode.UNKNOWN
}

fun PetProfile.ageLabel(today: LocalDate = LocalDate.now()): String {
    if (birthday != null) {
        val birth = runCatching { LocalDate.parse(birthday) }.getOrNull()
            ?: return "나이 미상"
        val years = java.time.Period.between(birth, today).years
        return if (years >= 0) "만 ${years}세" else "나이 미상"
    }
    return if (estimatedAgeYears != null) "약 ${estimatedAgeYears}세 (등록 시점)"
    else "나이 미상"
}

fun chooseActivePet(profiles: List<PetProfile>, preferredId: String?): PetProfile? =
    profiles.firstOrNull { !it.isArchived && it.id == preferredId }
        ?: profiles.firstOrNull { !it.isArchived }
