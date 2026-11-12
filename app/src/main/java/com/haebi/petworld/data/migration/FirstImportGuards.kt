package com.haebi.petworld.data.migration

import com.haebi.petworld.data.account.LocalAccountScope

/** Pure pre-write approval guards, separate from Firebase SDK and Android UI. */
object FirstImportGuards {
    fun accountFingerprint(firebaseUid: String): String =
        (LocalAccountScope.fromFirebaseUid(firebaseUid) as LocalAccountScope.SignedIn).key

    fun requireSameAccount(approvedAccountFingerprint: String, currentFirebaseUid: String) {
        require(
            approvedAccountFingerprint.isNotBlank() &&
                approvedAccountFingerprint == accountFingerprint(currentFirebaseUid)
        ) { "미리보기 이후 로그인 계정이 변경됐습니다. 다시 확인해 주세요." }
    }

    fun requireFreshReview(
        approvedMatching: Int,
        approvedExtra: Int,
        approvedConflicts: Int,
        latestMatching: Int,
        latestExtra: Int,
        latestConflicts: Int,
        allowCloudExtras: Boolean,
    ) {
        require(
            approvedMatching == latestMatching &&
                approvedExtra == latestExtra &&
                approvedConflicts == latestConflicts
        ) { "미리보기 이후 클라우드 기록이 변경됐습니다. 다시 확인해 주세요." }
        require(latestConflicts == 0) {
            "이미 같은 ID의 다른 클라우드 기록이 있습니다. 기존 기록을 변경하지 않았습니다."
        }
        require(latestExtra == 0 || allowCloudExtras) {
            "클라우드의 다른 기록을 보존한 채 추가하는 방식에 동의해 주세요."
        }
    }

    fun requireFreshSource(approvedFingerprint: String, latestFingerprint: String) {
        require(
            approvedFingerprint.isNotBlank() &&
                approvedFingerprint == latestFingerprint
        ) { "미리보기 이후 기기 원본이 변경됐습니다. 다시 확인해 주세요." }
    }
}
