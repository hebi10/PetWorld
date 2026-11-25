package com.haebi.petworld.data.sync

import androidx.room.withTransaction
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class SyncStatusSnapshot(
    val counts: Map<String, Int>,
    val headStatus: String?,
    val scan: SyncScanState?,
    val reviewCount: Int,
    val reviews: List<SyncInboundEntry>,
) {
    val pending: Int get() = counts.filterKeys { it !in setOf("ACKNOWLEDGED", "SUPERSEDED") }.values.sum()
    val acknowledged: Int get() = counts["ACKNOWLEDGED"] ?: 0
    val retry: Int get() = (counts["RETRY"] ?: 0) + (counts["IN_FLIGHT"] ?: 0)
    val blocked: Int get() = (counts["BLOCKED"] ?: 0) + (counts["CONFLICT"] ?: 0)
    val waitingBaseline: Int get() = counts["WAITING_BASELINE"] ?: 0

    fun summary(authorized: Boolean): String = when {
        reviewCount > 0 || blocked > 0 -> "충돌 또는 확인 필요"
        !authorized -> "동기화 비활성"
        retry > 0 -> "재시도 또는 전송 결과 확인 대기"
        waitingBaseline > 0 -> "서버 기준 확인 대기"
        pending > 0 -> "전송 대기"
        scan == null -> "서버 조회 전"
        !scan.complete() -> "서버 조회 진행 중 또는 재개 대기"
        else -> "저장된 대기 작업 없음"
    }
}

internal class SyncStatusReader(
    private val db: PetWorldDatabase,
    private val account: LocalAccountScope.SignedIn,
) {
    fun observe(limit: Int = 20): Flow<SyncStatusSnapshot> {
        require(limit in 1..500)
        return db.invalidationTracker.createFlow("sync_outbox", "sync_inbound", "sync_scan")
            .map { read(limit) }.distinctUntilChanged()
    }

    suspend fun read(limit: Int): SyncStatusSnapshot = db.withTransaction {
        require(limit in 1..500)
        val key = account.key
        val outbox = db.syncOutboxDao()
        val inbox = db.syncInboundDao()
        SyncStatusSnapshot(
            counts = outbox.statusCounts(key).associate { it.status to it.count },
            headStatus = outbox.firstOutstanding(key)?.status,
            scan = db.syncScanDao().find(key),
            reviewCount = inbox.reviewCount(key),
            reviews = inbox.reviewEntries(key, limit),
        )
    }
}

internal data class SyncFieldComparison(val label: String, val local: String, val remote: String)

private val reviewFields = linkedMapOf(
    "name" to "이름", "species" to "종류", "breed" to "품종",
    "birthday" to "생일", "estimatedAgeYears" to "추정 나이",
    "sex" to "성별", "neuterStatus" to "중성화", "notes" to "주의사항",
    "isArchived" to "보관 상태", "type" to "기록 종류",
    "occurredAtMs" to "기록 시각", "timezoneId" to "시간대",
    "foodKind" to "식사 종류", "foodProductName" to "제품명",
    "foodServedMilli" to "급여량 (1/1000 단위)", "foodConsumedMilli" to "섭취량 (1/1000 단위)",
    "foodUnit" to "식사 단위", "waterMl" to "음수량 (mL)",
    "eliminationKind" to "배변 종류", "eliminationCount" to "배변 횟수",
    "activityKind" to "활동 종류", "activityMinutes" to "활동 시간 (분)",
    "weightGrams" to "체중 (g)", "weightInputUnit" to "체중 입력 단위",
    "note" to "메모", "photoFile" to "사진", "createdAtMs" to "생성 시각",
    "updatedAtMs" to "수정 시각", "deletedAtMs" to "서버 삭제 시각",
)

internal fun reviewStatusLabel(status: String): String = when (status) {
    "CONFLICT" -> "기록 충돌"
    "DELETE_REVIEW" -> "삭제 확인 필요"
    "PHOTO_PENDING" -> "사진 동기화 대기"
    "PARENT_PENDING" -> "반려동물 확인 대기"
    else -> "확인 필요"
}

private fun displayValue(key: String, fields: Map<String, Any?>?): String {
    if (fields == null) return "기록 없음"
    val value = fields[key] ?: return "미입력"
    if (key == "photoFile") return "사진 있음"
    if (key.endsWith("AtMs") && value is Number) return runCatching {
        val zone = fields["timezoneId"]?.let { ZoneId.of(it.toString()) } ?: ZoneId.systemDefault()
        Instant.ofEpochMilli(value.toLong()).atZone(zone)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"))
    }.getOrDefault("확인 필요")
    return when (key) {
        "isArchived" -> if (value == true) "보관됨" else "활성"
        "species" -> when (value) {
            "CAT" -> "고양이"
            "DOG" -> "강아지"
            "OTHER" -> "기타"
            else -> value.toString()
        }
        "type" -> when (value) {
            "FOOD" -> "식사"
            "WATER" -> "음수"
            "ELIMINATION" -> "배변"
            "ACTIVITY" -> "활동"
            "WEIGHT" -> "체중"
            else -> value.toString()
        }
        "sex" -> when (value) {
            "FEMALE" -> "암컷"
            "MALE" -> "수컷"
            "UNKNOWN" -> "모름"
            else -> value.toString()
        }
        "neuterStatus" -> when (value) {
            "YES" -> "완료"
            "NO" -> "하지 않음"
            "UNKNOWN" -> "모름"
            else -> value.toString()
        }
        else -> value.toString().ifEmpty { "빈 값" }
    }
}

internal fun reviewComparison(entry: SyncInboundEntry): List<SyncFieldComparison> {
    val remote = parseSyncJson(entry.remoteJson)
    val local = entry.localJson?.let(::parseSyncJson)
    return reviewFields.entries.filter { (key, _) ->
        remote.containsKey(key) || local?.containsKey(key) == true
    }.map { (key, label) ->
        SyncFieldComparison(label, displayValue(key, local), displayValue(key, remote))
    }
}

internal fun canOfferRemoteReview(entry: SyncInboundEntry, authorized: Boolean): Boolean =
    authorized && entry.status == "CONFLICT" && !entry.tombstone &&
        runCatching {
            parseSyncJson(entry.remoteJson)["photoFile"] == null &&
                entry.localJson?.let(::parseSyncJson)?.get("photoFile") == null
        }.getOrDefault(false)
