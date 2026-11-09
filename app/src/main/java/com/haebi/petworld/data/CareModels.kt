package com.haebi.petworld.data

import com.haebi.petworld.data.room.CareEntity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class CareType(val label: String) {
    FOOD("식사"), WATER("음수"), ELIMINATION("배변"), ACTIVITY("활동"), WEIGHT("체중")
}
enum class FoodKind(val label: String) {
    DRY("사료"), WET("습식"), SNACK("간식"), OTHER("기타")
}
enum class PortionUnit(val label: String) { G("g"), ML("mL") }
enum class EliminationKind(val label: String) { URINE("소변"), STOOL("대변") }
enum class ActivityKind(val label: String) {
    WALK("산책"), PLAY("놀이"), SLEEP("수면"), OTHER("기타")
}
enum class WeightUnit(val label: String) { KG("kg"), G("g") }

data class CareDraft(
    val id: String? = null,
    val petId: String,
    val type: CareType,
    val occurredAt: Long,
    val timezoneId: String,
    val amount: String = "",
    val consumedAmount: String = "",
    val foodKind: FoodKind = FoodKind.DRY,
    val foodProductName: String = "",
    val foodUnit: PortionUnit = PortionUnit.G,
    val waterMeasured: Boolean = true,
    val eliminationKind: EliminationKind = EliminationKind.URINE,
    val activityKind: ActivityKind = ActivityKind.PLAY,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val note: String = "",
)

private fun scaled(raw: String, factor: Int): Long? = runCatching {
    BigDecimal(raw.trim())
        .multiply(BigDecimal.valueOf(factor.toLong()))
        .longValueExact()
}.getOrNull()

object CareValidation {
    fun error(draft: CareDraft, now: Long = System.currentTimeMillis()): String? {
        if (draft.petId.isBlank()) return "반려동물을 선택해 주세요."
        if (draft.occurredAt <= 0L || draft.occurredAt > now + 300_000L) {
            return "기록 시각을 확인해 주세요. 미래 시각은 저장할 수 없습니다."
        }
        if (runCatching { ZoneId.of(draft.timezoneId) }.isFailure) return "시간대 정보를 확인해 주세요."
        if (draft.note.length > 2_000) return "메모는 2,000자 이내로 작성해 주세요."
        if (draft.foodProductName.length > 100) return "제품명은 100자 이내로 작성해 주세요."
        return when (draft.type) {
            CareType.FOOD -> {
                val served = scaled(draft.amount, 1_000)
                val consumed = draft.consumedAmount.takeIf { it.isNotBlank() }?.let { scaled(it, 1_000) }
                when {
                    served == null || served !in 1..1_000_000_000L -> "급여량을 올바르게 입력해 주세요."
                    draft.consumedAmount.isNotBlank() && consumed == null ->
                        "실제 섭취량을 올바르게 입력해 주세요."
                    consumed != null && (consumed < 0L || consumed > served) ->
                        "섭취량은 0 이상, 급여량 이하여야 합니다."
                    else -> null
                }
            }
            CareType.WATER -> {
                if (!draft.waterMeasured) null
                else if (scaled(draft.amount, 1) !in 0L..100_000L) "음수량을 0mL 이상으로 입력해 주세요."
                else null
            }
            CareType.ELIMINATION -> if (draft.amount.toIntOrNull() !in 1..100) {
                "횟수를 1~100회 사이로 입력해 주세요."
            } else null
            CareType.ACTIVITY -> if (draft.amount.toIntOrNull() !in 1..1_440) {
                "활동 시간을 1~1,440분 사이로 입력해 주세요."
            } else null
            CareType.WEIGHT -> {
                val grams = scaled(draft.amount, if (draft.weightUnit == WeightUnit.KG) 1_000 else 1)
                if (grams == null || grams !in 1..1_000_000L) "체중을 올바르게 입력해 주세요."
                else null
            }
        }
    }
}

fun CareDraft.toEntity(previous: CareEntity? = null, now: Long = System.currentTimeMillis()): CareEntity {
    require(CareValidation.error(this, now) == null) {
        CareValidation.error(this, now) ?: "잘못된 기록입니다."
    }
    return CareEntity(
        id = previous?.id ?: id ?: UUID.randomUUID().toString(),
        petId = petId,
        type = type.name,
        occurredAt = occurredAt,
        timezoneId = timezoneId,
        foodKind = if (type == CareType.FOOD) foodKind.name else null,
        foodProductName = if (type == CareType.FOOD) foodProductName.trim().ifBlank { null } else null,
        foodServedMilli = if (type == CareType.FOOD) scaled(amount, 1_000) else null,
        foodConsumedMilli = if (type == CareType.FOOD && consumedAmount.isNotBlank())
            scaled(consumedAmount, 1_000) else null,
        foodUnit = if (type == CareType.FOOD) foodUnit.name else null,
        waterMl = if (type == CareType.WATER && waterMeasured) scaled(amount, 1) else null,
        eliminationKind = if (type == CareType.ELIMINATION) eliminationKind.name else null,
        eliminationCount = if (type == CareType.ELIMINATION) amount.toInt() else null,
        activityKind = if (type == CareType.ACTIVITY) activityKind.name else null,
        activityMinutes = if (type == CareType.ACTIVITY) amount.toInt() else null,
        weightGrams = if (type == CareType.WEIGHT) scaled(amount,
            if (weightUnit == WeightUnit.KG) 1_000 else 1) else null,
        weightInputUnit = if (type == CareType.WEIGHT) weightUnit.name else null,
        note = note.trim(),
        createdAt = previous?.createdAt ?: now,
        updatedAt = now,
    )
}

fun CareEntity.toDraft(): CareDraft {
    val kind = CareType.valueOf(type)
    val weight = weightInputUnit?.let { WeightUnit.valueOf(it) } ?: WeightUnit.KG
    val amountText = when (kind) {
        CareType.FOOD -> foodServedMilli?.let { milliText(it) }.orEmpty()
        CareType.WATER -> waterMl?.toString().orEmpty()
        CareType.ELIMINATION -> eliminationCount?.toString().orEmpty()
        CareType.ACTIVITY -> activityMinutes?.toString().orEmpty()
        CareType.WEIGHT -> weightGrams?.let {
            if (weight == WeightUnit.KG) milliText(it) else it.toString()
        }.orEmpty()
    }
    return CareDraft(
        id = id, petId = petId, type = kind,
        occurredAt = occurredAt, timezoneId = timezoneId,
        amount = amountText,
        consumedAmount = foodConsumedMilli?.let { milliText(it) }.orEmpty(),
        foodKind = foodKind?.let { FoodKind.valueOf(it) } ?: FoodKind.DRY,
        foodProductName = foodProductName.orEmpty(),
        foodUnit = foodUnit?.let { PortionUnit.valueOf(it) } ?: PortionUnit.G,
        waterMeasured = waterMl != null,
        eliminationKind = eliminationKind?.let { EliminationKind.valueOf(it) } ?: EliminationKind.URINE,
        activityKind = activityKind?.let { ActivityKind.valueOf(it) } ?: ActivityKind.PLAY,
        weightUnit = weight,
        note = note,
    )
}

fun milliText(value: Long): String =
    BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString()

fun CareEntity.localDate(): LocalDate {
    val zone = runCatching { ZoneId.of(timezoneId) }.getOrDefault(ZoneId.systemDefault())
    return Instant.ofEpochMilli(occurredAt).atZone(zone).toLocalDate()
}

fun CareEntity.displayValue(): String = when (CareType.valueOf(type)) {
    CareType.FOOD -> {
        val unit = foodUnit?.let { PortionUnit.valueOf(it).label }.orEmpty()
        val consumed = foodConsumedMilli?.let { "${milliText(it)}$unit" } ?: "미측정"
        val product = foodProductName?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        "${foodKind?.let { FoodKind.valueOf(it).label } ?: "식사"}$product · 제공 ${milliText(foodServedMilli ?: 0)}$unit · 섭취 $consumed"
    }
    CareType.WATER -> waterMl?.let { "${it}mL" } ?: "음수 미측정"
    CareType.ELIMINATION -> "${eliminationKind?.let { EliminationKind.valueOf(it).label } ?: "배변"} ${eliminationCount ?: 0}회"
    CareType.ACTIVITY -> "${activityKind?.let { ActivityKind.valueOf(it).label } ?: "활동"} ${activityMinutes ?: 0}분"
    CareType.WEIGHT -> {
        val unit = weightInputUnit?.let { WeightUnit.valueOf(it) } ?: WeightUnit.KG
        val value = weightGrams ?: 0
        if (unit == WeightUnit.KG) "${milliText(value)}kg" else "${value}g"
    }
}

fun summarizeCare(type: CareType, records: List<CareEntity>): String {
    val items = records.filter { it.type == type.name }
    if (items.isEmpty()) return "기록 없음"
    return when (type) {
        CareType.FOOD -> {
            val sums = items.groupBy { it.foodUnit ?: "G" }
                .map { (unit, entries) ->
                    val label = if (unit == "ML") "mL" else "g"
                    val served = entries.sumOf { it.foodServedMilli ?: 0L }
                    val measured = entries.mapNotNull { it.foodConsumedMilli }
                    val unknown = entries.size - measured.size
                    val consumed = if (measured.isEmpty()) "섭취 미측정"
                        else "섭취 ${milliText(measured.sum())}$label" +
                            if (unknown > 0) " (미측정 ${unknown}건)" else ""
                    "제공 ${milliText(served)}$label · $consumed"
                }
            sums.joinToString(" / ")
        }
        CareType.WATER -> {
            val observed = items.mapNotNull { it.waterMl }
            val unknown = items.size - observed.size
            listOfNotNull(
                if (observed.isNotEmpty()) "${observed.sum()}mL" else null,
                if (unknown > 0) "미측정 ${unknown}건" else null,
            ).joinToString(" · ")
        }
        CareType.ELIMINATION -> {
            val urine = items.filter { it.eliminationKind == "URINE" }.sumOf { it.eliminationCount ?: 0 }
            val stool = items.filter { it.eliminationKind == "STOOL" }.sumOf { it.eliminationCount ?: 0 }
            "소변 ${urine}회 · 대변 ${stool}회"
        }
        CareType.ACTIVITY -> "${items.sumOf { it.activityMinutes ?: 0 }}분"
        CareType.WEIGHT -> items.maxByOrNull { it.occurredAt }?.displayValue() ?: "기록 없음"
    }
}
