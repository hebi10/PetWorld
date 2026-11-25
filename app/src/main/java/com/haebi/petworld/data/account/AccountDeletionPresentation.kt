package com.haebi.petworld.data.account

/** Server-confirmed phases only. A transport failure is not a deletion receipt. */
internal enum class AccountDeletionPhase(val title: String, val detail: String) {
    LOCKED("삭제 요청 접수", "클라우드 계정 접근을 차단했습니다."),
    AUTH_REVOKED("인증 접근 차단", "계정 인증 및 토큰 사용을 중단하는 단계입니다."),
    RECORDS_PURGED("서버 기록 삭제", "반려동물 프로필과 생활 기록 삭제 단계가 확인됐습니다."),
    PHOTOS_PURGED("사진 삭제", "클라우드 사진 삭제 단계가 확인됐습니다."),
    AUTH_DELETED("계정 삭제 확인 중", "인증 계정 삭제 이후 남은 서버 데이터와 보존 정책을 검증하고 있습니다."),
    VERIFIED("최종 검증 중", "삭제 결과를 다시 확인하고 있습니다."),
    COMPLETE("서버 삭제 완료", "서버의 삭제 검증이 완료됐습니다. 기기 기록과 외부 백업은 별도로 확인해 주세요.");

    val serverDeletionComplete: Boolean get() = this == COMPLETE

    companion object {
        fun fromServer(value: String): AccountDeletionPhase? = entries.firstOrNull { it.name == value }
    }
}

internal data class AccountDeletionReceipt private constructor(
    val jobId: String,
    val phase: AccountDeletionPhase,
) {
    companion object {
        fun fromServer(jobId: String, phase: String): AccountDeletionReceipt? {
            if (!jobId.matches(Regex("[0-9a-f]{64}"))) return null
            val knownPhase = AccountDeletionPhase.fromServer(phase) ?: return null
            return AccountDeletionReceipt(jobId, knownPhase)
        }
    }

    /** Same job only. Do not move a confirmed status backwards. */
    fun accept(next: AccountDeletionReceipt): AccountDeletionReceipt? =
        next.takeIf { it.jobId == jobId && it.phase.ordinal >= phase.ordinal }
}

/** Fail closed: admission is server-side; this is only the client UX gate.
 * No production caller currently has all the independently verified evidence.
 */
internal data class AccountDeletionReadiness(
    val verifiedEndpoint: Boolean = false,
    val secureStatusTicket: Boolean = false,
    val accountSyncBlocked: Boolean = false,
    val cacheCleanupVerified: Boolean = false,
) {
    fun canRequest(scope: LocalAccountScope, acknowledged: Boolean, recentlyReauthenticated: Boolean): Boolean =
        scope is LocalAccountScope.SignedIn && acknowledged && recentlyReauthenticated &&
            verifiedEndpoint && secureStatusTicket && accountSyncBlocked && cacheCleanupVerified
}
