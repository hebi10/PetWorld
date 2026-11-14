package com.haebi.petworld.data.migration

import com.haebi.petworld.data.backup.PetWorldBackupInspection

data class FirstImportBackupEvidence internal constructor(
    val sourceFingerprint: String,
    val accountFingerprint: String,
)

/** Bind an authenticated, read-only backup inspection to the exact Guest snapshot and account. */
object FirstImportBackupVerifier {
    fun verify(
        approvedSourceFingerprint: String,
        approvedAccountFingerprint: String,
        latestSourceFingerprint: String,
        backup: PetWorldBackupInspection,
    ): FirstImportBackupEvidence {
        require(approvedSourceFingerprint.isNotBlank() && approvedAccountFingerprint.isNotBlank())
        require(approvedSourceFingerprint == latestSourceFingerprint) {
            "백업 확인 중 비회원 기록이 변경됐습니다. 다시 미리보기부터 진행해 주세요."
        }
        val knownPets = backup.pets.map { it.id }.toSet()
        require(backup.records.all { it.petId in knownPets }) {
            "백업 기록의 반려동물 연결이 올바르지 않습니다."
        }
        val references = buildSet {
            backup.pets.forEach { pet -> pet.photoFile?.let { add(pet.id to it) } }
            backup.records.forEach { record ->
                record.photoFile?.let { add(record.petId to it) }
            }
        }
        require(backup.photoSha256.keys == references.map { it.second }.toSet()) {
            "백업 사진 목록이 현재 기록과 일치하지 않습니다."
        }
        val photoEvidence = references.associate { (petId, fileName) ->
            petId + "/" + fileName to backup.photoSha256.getValue(fileName)
        }
        val backupFingerprint = FirstImportPayloads.fingerprint(
            backup.pets, backup.records, photoEvidence, backup.selectedPetId,
        )
        require(approvedSourceFingerprint == backupFingerprint) {
            "백업 파일이 현재 비회원 기록과 일치하지 않습니다. 새 백업을 만들어 주세요."
        }
        return FirstImportBackupEvidence(approvedSourceFingerprint, approvedAccountFingerprint)
    }

    fun requireApproved(
        approvedSourceFingerprint: String,
        approvedAccountFingerprint: String,
        evidence: FirstImportBackupEvidence,
    ) {
        require(evidence.sourceFingerprint == approvedSourceFingerprint &&
            evidence.accountFingerprint == approvedAccountFingerprint &&
            evidence.sourceFingerprint.isNotBlank() &&
            evidence.accountFingerprint.isNotBlank()
        ) { "현재 기록과 계정에 대한 백업 검증을 다시 진행해 주세요." }
    }
}
