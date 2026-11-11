package com.haebi.petworld.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.displayValue
import com.haebi.petworld.data.localDate
import com.haebi.petworld.data.milliText
import com.haebi.petworld.data.summarizeCare
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.ui.components.PetAvatar
import com.haebi.petworld.ui.components.PetEmptyState
import com.haebi.petworld.ui.components.PetPrimaryButton
import com.haebi.petworld.ui.components.PetSecondaryButton
import com.haebi.petworld.ui.components.PetSurfaceCard
import com.haebi.petworld.ui.icons.PetIcons
import com.haebi.petworld.ui.theme.PetSpacing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CareRange(val label: String) {
    TODAY("오늘"), SEVEN("7일"), THIRTY("30일"), ALL("전체")
}

fun CareRange.accepts(record: CareEntity, today: LocalDate = LocalDate.now()): Boolean {
    val date = record.localDate()
    return when (this) {
        CareRange.TODAY -> date == today
        CareRange.SEVEN -> date >= today.minusDays(6) && date <= today
        CareRange.THIRTY -> date >= today.minusDays(29) && date <= today
        CareRange.ALL -> true
    }
}

internal fun filterCareRecords(
    records: List<CareEntity>,
    selectedType: CareType?,
    selectedRange: CareRange,
    today: LocalDate = LocalDate.now(),
): List<CareEntity> = records.filter {
    (selectedType == null || it.type == selectedType.name) && selectedRange.accepts(it, today)
}

internal fun CareEntity.displayRecordTime(instantMillis: Long): String {
    val zone = runCatching { ZoneId.of(timezoneId) }.getOrDefault(ZoneId.systemDefault())
    return Instant.ofEpochMilli(instantMillis).atZone(zone)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.KOREAN))
}

fun CareEntity.displayDateTime(): String = displayRecordTime(occurredAt)

internal fun CareEntity.hasBeenEdited(): Boolean = updatedAt > createdAt

@Composable
fun CareRecordList(
    petName: String,
    records: List<CareEntity>,
    selectedType: CareType?,
    selectedRange: CareRange,
    onTypeChange: (CareType?) -> Unit,
    onRangeChange: (CareRange) -> Unit,
    onAdd: () -> Unit,
    onView: (String) -> Unit,
) {
    var showRangePicker by remember { mutableStateOf(false) }
    var showTypePicker by remember { mutableStateOf(false) }
    val filtered = filterCareRecords(records, selectedType, selectedRange)

    Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
        ) {
            Text(
                "${petName}의 생활 기록",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            PetPrimaryButton(label = "기록 추가", onClick = onAdd)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
        ) {
            PetSecondaryButton(
                label = "기간: ${selectedRange.label}",
                onClick = { showRangePicker = true },
                modifier = Modifier.weight(1f),
            )
            PetSecondaryButton(
                label = "유형: ${selectedType?.label ?: "전체"}",
                onClick = { showTypePicker = true },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "기록 ${filtered.size}건",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (filtered.isEmpty()) {
            PetEmptyState(
                title = "해당 기간에 기록이 없습니다",
                detail = "기간 또는 종류를 변경하거나 새로운 기록을 추가해 주세요.",
            )
        } else {
            filtered.forEach { record ->
                CareRecordCompactRow(record = record, onClick = { onView(record.id) })
            }
        }
    }

    if (showRangePicker) {
        AlertDialog(
            onDismissRequest = { showRangePicker = false },
            title = { Text("조회 기간") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall)) {
                    CareRange.entries.forEach { range ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onRangeChange(range)
                                showRangePicker = false
                            },
                        ) {
                            Text(
                                if (selectedRange == range) "${range.label} · 선택됨" else range.label,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRangePicker = false }) { Text("닫기") }
            },
        )
    }

    if (showTypePicker) {
        AlertDialog(
            onDismissRequest = { showTypePicker = false },
            title = { Text("기록 유형") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall)) {
                    (listOf<CareType?>(null) + CareType.entries).forEach { type ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onTypeChange(type)
                                showTypePicker = false
                            },
                        ) {
                            val label = type?.label ?: "전체 유형"
                            Text(
                                if (selectedType == type) "$label · 선택됨" else label,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTypePicker = false }) { Text("닫기") }
            },
        )
    }
}

@Composable
private fun CareRecordCompactRow(record: CareEntity, onClick: () -> Unit) {
    val type = CareType.valueOf(record.type)
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        shape = androidx.compose.ui.graphics.RectangleShape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = PetSpacing.minTouchTarget)
                .padding(horizontal = PetSpacing.medium, vertical = PetSpacing.small),
            verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(type.label, style = MaterialTheme.typography.labelLarge)
                Text(
                    record.displayDateTime(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    maxLines = 1,
                )
            }
            Text(
                record.displayValue(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (record.note.isNotBlank()) {
                Text(
                    record.note,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun CareRecordRow(record: CareEntity, onClick: () -> Unit) {
    val type = CareType.valueOf(record.type)
    PetSurfaceCard(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
            Text(type.label, style = MaterialTheme.typography.labelLarge)
            Text(
                record.displayDateTime(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(record.displayValue(), style = MaterialTheme.typography.bodyLarge)
        if (record.note.isNotBlank()) {
            Text(
                record.note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
fun CareRecordDetail(
    record: CareEntity,
    photoStore: PetPhotoStore,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: suspend (String, String) -> Result<Unit>,
    onDeleted: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Text("${CareType.valueOf(record.type).label} 기록", style = MaterialTheme.typography.headlineMedium)
    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "발생 시각 · ${record.displayDateTime()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(record.displayValue(), style = MaterialTheme.typography.titleLarge)
        if (record.photoFile != null) {
            Text("관찰 사진", style = MaterialTheme.typography.labelLarge)
            PetAvatar(photoStore, "관찰 사진", record.photoFile, size = 112.dp)
        }
        if (record.note.isNotBlank()) {
            HorizontalDivider()
            Text("관찰 메모", style = MaterialTheme.typography.labelLarge)
            Text(record.note, style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider()
        Text(
            "작성 시각 · ${record.displayRecordTime(record.createdAt)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (record.hasBeenEdited())
                "수정 시각 · ${record.displayRecordTime(record.updatedAt)}"
            else "수정 시각 · 수정 내역 없음",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (error != null) {
        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium)
    }
    PetPrimaryButton(
        label = "수정", enabled = !deleting,
        onClick = onEdit, modifier = Modifier.fillMaxWidth(),
    )
    PetSecondaryButton(
        label = "기록 삭제", enabled = !deleting,
        onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = onBack, enabled = !deleting) { Text("목록으로") }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("이 기록을 삭제할까요?") },
            text = { Text("삭제한 기록은 복구할 수 없으며, 홈 집계에서도 제외됩니다.") },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        scope.launch {
                            onDelete(record.id, record.petId).fold(
                                onSuccess = { onDeleted() },
                                onFailure = {
                                    error = it.message ?: "삭제하지 못했습니다."
                                },
                            )
                            deleting = false
                            confirmDelete = false
                        }
                    },
                ) { Text(if (deleting) "삭제 중" else "삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, enabled = !deleting) {
                    Text("취소")
                }
            },
        )
    }
}

internal data class WeightTrendData(
    val entries: List<CareEntity>,
    val latest: CareEntity?,
    val minimumGrams: Long?,
    val maximumGrams: Long?,
)

internal fun weightTrendData(records: List<CareEntity>): WeightTrendData {
    val measured = records.filter {
        it.type == CareType.WEIGHT.name && it.weightGrams != null
    }.sortedWith(
        compareBy<CareEntity> { it.occurredAt }
            .thenBy { it.createdAt }
            .thenBy { it.id }
    )
    return WeightTrendData(
        entries = measured,
        latest = measured.lastOrNull(),
        minimumGrams = measured.minOfOrNull { it.weightGrams!! },
        maximumGrams = measured.maxOfOrNull { it.weightGrams!! },
    )
}

internal fun weightAxisDate(record: CareEntity): String {
    val zone = runCatching { ZoneId.of(record.timezoneId) }.getOrDefault(ZoneId.systemDefault())
    return Instant.ofEpochMilli(record.occurredAt).atZone(zone)
        .format(DateTimeFormatter.ofPattern("M/d", Locale.KOREAN))
}

@Composable
fun CareStatisticsScreen(
    petName: String,
    records: List<CareEntity>,
    period: CareRange,
    onPeriodChange: (CareRange) -> Unit,
    expandedLayout: Boolean = false,
) {
    val selected = records.filter { period.accepts(it) }
    val weights = weightTrendData(selected)
    val summaryTypes = CareType.entries.filter { it != CareType.WEIGHT }
    val twoColumns = !expandedLayout && LocalConfiguration.current.screenWidthDp >= 360 &&
        LocalDensity.current.fontScale < 1.3f

    Text("${petName}의 기록 통계", style = MaterialTheme.typography.headlineLarge)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
    ) {
        listOf(CareRange.SEVEN, CareRange.THIRTY, CareRange.ALL).forEach { range ->
            FilterChip(
                selected = period == range,
                onClick = { onPeriodChange(range) },
                label = { Text(range.label) },
            )
        }
    }

    val weightSection: @Composable () -> Unit = {
        PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
            Text("체중 변화", style = MaterialTheme.typography.titleLarge)
            val latest = weights.latest
            if (latest == null) {
                Text(
                    "선택한 기간에 체중 기록이 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "체중 추이는 측정값 2개 이상부터 표시됩니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "최근 측정 ${milliText(latest.weightGrams!!)}kg",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "${latest.displayDateTime()} · 측정 ${weights.entries.size}건",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (weights.entries.size >= 2) {
                    WeightTrend(weights.entries)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            weightAxisDate(weights.entries.first()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            weightAxisDate(weights.entries.last()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Text(
                        "체중 추이는 측정값 2개 이상부터 표시됩니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "최저 ${milliText(weights.minimumGrams!!)}kg · 최고 ${milliText(weights.maximumGrams!!)}kg",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    val summarySection: @Composable () -> Unit = {
        Text("생활 기록 요약", style = MaterialTheme.typography.titleLarge)
        if (twoColumns) {
            summaryTypes.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                ) {
                    pair.forEach { type ->
                        CareStatisticsSummaryCard(
                            type = type,
                            records = selected,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        } else {
            summaryTypes.forEach { type ->
                CareStatisticsSummaryCard(
                    type = type,
                    records = selected,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text(
            "미측정값과 기록이 없는 날짜는 0으로 계산하지 않습니다. 입력된 자료의 요약이며 건강 진단이 아닙니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (expandedLayout) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.large),
        ) {
            Column(modifier = Modifier.weight(1.15f)) { weightSection() }
            Column(modifier = Modifier.weight(1f)) { summarySection() }
        }
    } else {
        weightSection()
        summarySection()
    }
}

@Composable
private fun CareStatisticsSummaryCard(
    type: CareType,
    records: List<CareEntity>,
    modifier: Modifier,
) {
    PetSurfaceCard(modifier = modifier) {
        Text(type.label, style = MaterialTheme.typography.titleMedium)
        Text(summarizeCare(type, records), style = MaterialTheme.typography.bodyMedium)
        Text(
            "${records.count { it.type == type.name }}건의 기록",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeightTrend(weights: List<CareEntity>) {
    val first = weights.first()
    val last = weights.last()
    val lowest = weights.minOf { it.weightGrams!! }
    val highest = weights.maxOf { it.weightGrams!! }
    val extent = (highest - lowest).coerceAtLeast(100L).toFloat() * 0.2f
    val bottom = (lowest.toFloat() - extent).coerceAtLeast(0f)
    val top = highest.toFloat() + extent
    val height = (top - bottom).coerceAtLeast(1f)
    val timeSpan = last.occurredAt - first.occurredAt
    val lineColor = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        val vertices = weights.mapIndexed { index, record ->
            val fraction = if (timeSpan > 0L) {
                ((record.occurredAt - first.occurredAt).toDouble() / timeSpan).toFloat()
            } else {
                index.toFloat() / (weights.size - 1)
            }
            val x = fraction.coerceIn(0f, 1f) * size.width
            val y = size.height - ((record.weightGrams!!.toFloat() - bottom) / height) * size.height
            Offset(x, y.coerceIn(0f, size.height))
        }
        vertices.zipWithNext { from, to ->
            drawLine(lineColor, from, to, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        vertices.forEach { point ->
            drawCircle(lineColor, radius = 3.dp.toPx(), center = point)
        }
    }
}
