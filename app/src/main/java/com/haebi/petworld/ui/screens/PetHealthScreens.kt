package com.haebi.petworld.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.haebi.petworld.data.milliText
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.ui.components.PetEmptyState
import com.haebi.petworld.ui.components.PetPrimaryButton
import com.haebi.petworld.ui.components.PetSurfaceCard
import com.haebi.petworld.ui.theme.PetSpacing

internal data class RecentWeightSummary(
    val entries: List<CareEntity>,
    val latest: CareEntity?,
    val previous: CareEntity?,
    val differenceGrams: Long?,
)

internal fun recentWeightSummary(records: List<CareEntity>): RecentWeightSummary {
    val measured = weightTrendData(records).entries
    val latest = measured.lastOrNull()
    val previous = measured.getOrNull(measured.size - 2)
    val difference = latest?.weightGrams?.let { current ->
        previous?.weightGrams?.let { current - it }
    }
    return RecentWeightSummary(measured, latest, previous, difference)
}

internal fun formatWeightDifference(grams: Long): String = when {
    grams > 0L -> "+${milliText(grams)}kg"
    grams < 0L -> "-${milliText(-grams)}kg"
    else -> "0kg"
}

@Composable
fun PetHealthScreen(
    petName: String,
    records: List<CareEntity>,
    onAddWeight: () -> Unit,
    onViewRecord: (String) -> Unit,
) {
    val summary = recentWeightSummary(records)
    val measured = summary.entries

    Text("${petName}의 체중·건강", style = MaterialTheme.typography.headlineLarge)

    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text("체중 요약", style = MaterialTheme.typography.titleLarge)
        val latest = summary.latest
        val previous = summary.previous
        val difference = summary.differenceGrams
        if (latest == null) {
            Text(
                "측정된 체중 기록이 없습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "최근 체중 ${milliText(latest.weightGrams!!)}kg",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "최근 측정 ${latest.displayDateTime()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (previous == null || difference == null) {
                Text(
                    "이전 측정값이 없어 변화량을 표시할 수 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "이전 체중 ${milliText(previous.weightGrams!!)}kg",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "이전 측정 ${previous.displayDateTime()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "변화량 ${formatWeightDifference(difference)}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }

    PetPrimaryButton(
        label = "체중 기록",
        modifier = Modifier.fillMaxWidth(),
        onClick = onAddWeight,
    )
    Text("최근 체중 기록", style = MaterialTheme.typography.titleLarge)
    if (measured.isEmpty()) {
        PetEmptyState(
            title = "체중 기록이 없습니다",
            detail = "체중을 측정한 뒤 기록을 추가해 주세요.",
        )
    } else {
        measured.asReversed().take(5).forEach { record ->
            CareRecordRow(record, onClick = { onViewRecord(record.id) })
        }
    }
    Text(
        "체중의 이전 측정값과의 차이를 표시하며, 건강 진단이 아닙니다.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "증상·진료·복약·예방 관리는 후속 단계에서 제공됩니다.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
