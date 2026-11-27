package com.haebi.petworld.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    showTitle: Boolean = true,
) {
    val summary = recentWeightSummary(records)
    val measured = summary.entries
    if (showTitle) Text("${petName}의 체중·건강", style = MaterialTheme.typography.headlineLarge)

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RectangleShape) {
        Column(modifier = Modifier.padding(PetSpacing.large), verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
            Text("최근 체중", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val latest = summary.latest
            val previous = summary.previous
            val difference = summary.differenceGrams
            if (latest == null) {
                Text("측정된 체중 기록이 없습니다.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
                    Text(milliText(latest.weightGrams!!), style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = (if (LocalDensity.current.fontScale >= 1.3f) 34 else 48).sp,
                        lineHeight = (if (LocalDensity.current.fontScale >= 1.3f) 42 else 56).sp, fontWeight = FontWeight.Bold))
                    Text("kg", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = PetSpacing.xSmall))
                }
                Text("최근 측정 ${latest.displayDateTime()}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (previous == null || difference == null) {
                    Text("이전 측정값이 없어 변화량을 표시할 수 없습니다.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("이전 체중 ${milliText(previous.weightGrams!!)}kg · 변화량 ${formatWeightDifference(difference)}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
    PetPrimaryButton(label = "체중 기록 추가", modifier = Modifier.fillMaxWidth(), onClick = onAddWeight, prominent = true)
    Text("최근 체중 기록", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (measured.isEmpty()) {
        PetEmptyState("체중 기록이 없습니다", "체중을 측정한 뒤 기록을 추가해 주세요.")
    } else {
        Surface(modifier = Modifier.fillMaxWidth(), shape = RectangleShape, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column {
                measured.asReversed().take(5).forEachIndexed { index, record ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = PetSpacing.minTouchTarget)
                            .clickable(role = Role.Button) { onViewRecord(record.id) }.padding(PetSpacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                    ) {
                        Text(record.displayDateTime(), modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${milliText(record.weightGrams!!)}kg", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    Text("체중의 이전 측정값과의 차이를 표시하며, 건강 진단이 아닙니다.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("증상·진료·복약·예방 관리는 후속 단계에서 제공됩니다.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
