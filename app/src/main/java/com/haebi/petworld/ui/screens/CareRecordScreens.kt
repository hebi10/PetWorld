package com.haebi.petworld.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.haebi.petworld.data.CARE_HISTORY_PAGE_SIZE
import com.haebi.petworld.data.CareHistoryPage
import com.haebi.petworld.data.CareHistoryRequest
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

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

internal fun CareEntity.displayRecordZone(): ZoneId =
    runCatching { ZoneId.of(timezoneId) }.getOrDefault(ZoneId.systemDefault())

internal fun CareEntity.displayRecordTime(instantMillis: Long): String {
    return Instant.ofEpochMilli(instantMillis).atZone(displayRecordZone())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.KOREAN))
}

fun CareEntity.displayDateTime(): String = displayRecordTime(occurredAt)

internal fun CareEntity.displayClockTime(): String =
    displayDateTime().substringAfterLast(' ')

internal fun CareEntity.homeClockLabel(today: LocalDate = LocalDate.now()): String =
    if (localDate() == today) displayClockTime()
    else "${localDate().format(DateTimeFormatter.ofPattern("M/d", Locale.KOREAN))} ${displayClockTime()}"

internal fun CareEntity.hasBeenEdited(): Boolean = updatedAt > createdAt

private sealed interface HistoryLoadState {
    data object Loading : HistoryLoadState
    data class Ready(val request: CareHistoryRequest, val page: CareHistoryPage) : HistoryLoadState
    data class Failed(val request: CareHistoryRequest, val message: String) : HistoryLoadState
}

@Composable
fun CareRecordList(
    petName: String,
    petId: String,
    observeHistory: (CareHistoryRequest) -> Flow<CareHistoryPage>,
    selectedType: CareType?,
    selectedRange: CareRange,
    onTypeChange: (CareType?) -> Unit,
    onRangeChange: (CareRange) -> Unit,
    onAdd: () -> Unit,
    onView: (String) -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    var showRangePicker by remember { mutableStateOf(false) }
    var showTypePicker by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    var limit by rememberSaveable(petId, selectedType?.name, selectedRange.name, today.toEpochDay()) {
        mutableStateOf(CARE_HISTORY_PAGE_SIZE)
    }
    var retry by remember { mutableStateOf(0) }
    val firstDay = when (selectedRange) {
        CareRange.TODAY -> today
        CareRange.SEVEN -> today.minusDays(6)
        CareRange.THIRTY -> today.minusDays(29)
        CareRange.ALL -> null
    }
    val request = CareHistoryRequest(petId, selectedType, firstDay, firstDay?.let { today }, limit)
    val historyFlow = remember(request, retry) {
        observeHistory(request).map<CareHistoryPage, HistoryLoadState> {
            HistoryLoadState.Ready(request, it)
        }.catch { error ->
            emit(HistoryLoadState.Failed(request, error.message ?: "기록을 불러오지 못했습니다."))
        }
    }
    val loadState by historyFlow.collectAsStateWithLifecycle(initialValue = HistoryLoadState.Loading)
    val loaded = (loadState as? HistoryLoadState.Ready)?.takeIf { it.request.sameFilter(request) }
    val failure = (loadState as? HistoryLoadState.Failed)?.takeIf { it.request == request }
    val page = loaded?.page
    val listState = rememberLazyListState()
    LaunchedEffect(petId, selectedType, selectedRange) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(loaded, request) {
        if (loaded?.request == request && page != null && page.entries.size < page.totalCount) {
            snapshotFlow {
                val info = listState.layoutInfo
                info.totalItemsCount > 0 &&
                    (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= (info.totalItemsCount - 5).coerceAtLeast(0)
            }.first { it }
            limit = minOf(page.totalCount.toLong(), limit.toLong() + CARE_HISTORY_PAGE_SIZE).toInt()
        }
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(PetSpacing.small),
    ) {
        if (showTitle) item(key = "record-title") {
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
        }
        item(key = "record-filters") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
            ) {
                RecordFilterButton(
                    label = "기간: ${selectedRange.label}",
                    onClick = { showRangePicker = true },
                    modifier = Modifier.weight(1f),
                )
                RecordFilterButton(
                    label = "유형: ${selectedType?.label ?: "전체"}",
                    onClick = { showTypePicker = true },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (failure != null) {
            item(key = "record-error") {
                Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
                    Text("기록을 불러오지 못했습니다.")
                    Text(failure.message, style = MaterialTheme.typography.bodyMedium)
                    PetSecondaryButton(label = "다시 시도", onClick = { retry++ })
                }
            }
        } else if (page == null) {
            item(key = "record-loading") {
                CircularProgressIndicator()
            }
        } else {
            item(key = "record-count") {
                Text(
                    "기록 ${page.totalCount}건",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (page.entries.isEmpty()) {
                item(key = "record-empty") {
                    PetEmptyState(
                        title = "해당 기간에 기록이 없습니다",
                        detail = "기간 또는 종류를 변경하거나 새로운 기록을 추가해 주세요.",
                    )
                }
            } else {
                page.entries.forEachIndexed { index, record ->
                    val date = record.localDate()
                    if (index == 0 || page.entries[index - 1].localDate() != date) {
                        item(key = "date-${record.id}") {
                            Row(modifier = Modifier.fillMaxWidth().padding(top = PetSpacing.small),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(PetSpacing.medium)) {
                                Text(date.format(DateTimeFormatter.ofPattern("M월 d일 (EEE)", Locale.KOREAN)),
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                    item(key = "care-${record.id}") {
                        CareRecordCompactRow(record = record, onClick = { onView(record.id) }, groupedByDate = true)
                    }
                }
                if (loaded?.request != request) {
                    item(key = "record-loading-more") { CircularProgressIndicator() }
                }
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

internal fun careGlyph(type: CareType): ImageVector = when (type) {
    CareType.FOOD -> PetIcons.Food
    CareType.WATER -> PetIcons.Water
    CareType.ELIMINATION -> PetIcons.LitterTray
    CareType.ACTIVITY -> PetIcons.Profile
    CareType.WEIGHT -> PetIcons.Weight
}

@Composable
private fun RecordFilterButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = PetSpacing.minTouchTarget).clickable(role = Role.Button, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RectangleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = PetSpacing.medium, vertical = PetSpacing.small),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(PetIcons.Expand, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CareRecordCompactRow(record: CareEntity, onClick: () -> Unit, groupedByDate: Boolean = false) {
    val type = CareType.valueOf(record.type)
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val value = record.displayValue()
    // displayValue's final two segments are the actual provided and consumed values.
    // Keep the product description intact, including any user-entered separators.
    val foodParts = if (!largeText && type == CareType.FOOD) {
        val separator = value.lastIndexOf(" · 제공 ")
        val consumedSeparator = value.lastIndexOf(" · 섭취 ")
        if (separator >= 0 && consumedSeparator > separator)
            Triple(value.substring(0, separator), value.substring(separator + 3, consumedSeparator),
                value.substring(consumedSeparator + 3))
        else null
    } else null
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .padding(horizontal = PetSpacing.medium, vertical = PetSpacing.small),
            verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall),
            horizontalAlignment = Alignment.Start) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center) {
                    Icon(careGlyph(type), contentDescription = null, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (groupedByDate) record.displayClockTime() else record.displayDateTime(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(type.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (largeText) Text(value, style = MaterialTheme.typography.bodyLarge)
                    else foodParts?.let {
                        Text(it.first, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!largeText) {
                    if (foodParts != null) {
                        Column(modifier = Modifier.weight(1.25f), horizontalAlignment = Alignment.End) {
                            Text(foodParts.second, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.End)
                            Text(foodParts.third, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                        }
                    } else Text(value, modifier = Modifier.weight(1.25f),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.End)
                }
                Icon(PetIcons.Chevron, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (record.note.isNotBlank()) Text(record.note, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (record.photoFile != null) Text("관찰 사진 있음", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CareRecordRow(record: CareEntity, onClick: () -> Unit) =
    CareRecordCompactRow(record, onClick)

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
    val type = CareType.valueOf(record.type)
    Text("${type.label} 기록", style = MaterialTheme.typography.headlineMedium)
    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            record.displayValue(),
            style = if (type == CareType.FOOD) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "발생 시각 · ${record.displayDateTime()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "시간대 · ${record.displayRecordZone().id}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(PetSpacing.small),
                ) {
                    Text("삭제 대상 · ${type.label} 기록", style = MaterialTheme.typography.titleMedium)
                    Text("기록 값 · ${record.displayValue()}")
                    Text("발생 시각 · ${record.displayDateTime()}")
                    Text("시간대 · ${record.displayRecordZone().id}", style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    Text("삭제한 기록은 복구할 수 없으며, 홈 집계에서도 제외됩니다.")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
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
    showTitle: Boolean = true,
) {
    val selected = records.filter { period.accepts(it) }
    val weights = weightTrendData(selected)
    val summaryTypes = CareType.entries.filter { it != CareType.WEIGHT }
    if (showTitle) Text("${petName}의 기록 통계", style = MaterialTheme.typography.headlineLarge)
    Row(modifier = Modifier.fillMaxWidth()) {
        listOf(CareRange.SEVEN, CareRange.THIRTY, CareRange.ALL).forEach { range ->
            Surface(
                modifier = Modifier.weight(1f).heightIn(min = PetSpacing.minTouchTarget)
                    .selectable(selected = period == range, role = Role.RadioButton,
                        onClick = { onPeriodChange(range) }),
                shape = RectangleShape,
                color = if (period == range) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(modifier = Modifier.padding(PetSpacing.small), contentAlignment = Alignment.Center) {
                    Text(range.label, style = MaterialTheme.typography.titleSmall,
                        color = if (period == range) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (period == range) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }

    val weightSection: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
            Text("체중 변화", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val latest = weights.latest
            if (latest == null) {
                Text("선택한 기간에 체중 기록이 없습니다.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("체중 추이는 측정값 2개 이상부터 표시됩니다.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (weights.entries.size >= 2) {
                    WeightTrend(weights.entries)
                } else {
                    Text("체중 추이는 측정값 2개 이상부터 표시됩니다.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RectangleShape) {
                    Column(modifier = Modifier.padding(PetSpacing.medium), verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall)) {
                        Text("최근 측정 ${milliText(latest.weightGrams!!)}kg",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("최저 ${milliText(weights.minimumGrams!!)}kg · 최고 ${milliText(weights.maximumGrams!!)}kg",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("${latest.displayDateTime()} · 측정 ${weights.entries.size}건",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    val summarySection: @Composable () -> Unit = {
        Text("생활 기록 요약", style = MaterialTheme.typography.titleLarge)
        summaryTypes.forEach { type ->
            CareStatisticsSummaryCard(type, selected, Modifier.fillMaxWidth())
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
            Column(modifier = Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) { weightSection() }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) { summarySection() }
        }
    } else {
        weightSection()
        summarySection()
    }
}

@Composable
private fun CareStatisticsSummaryCard(type: CareType, records: List<CareEntity>, modifier: Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer, shape = RectangleShape) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = PetSpacing.minTouchTarget)
            .padding(horizontal = PetSpacing.medium, vertical = PetSpacing.xSmall),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
            Icon(careGlyph(type), contentDescription = null, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(0.85f)) {
                Text(type.label, style = MaterialTheme.typography.titleSmall)
                Text("${records.count { it.type == type.name }}건의 기록",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(summarizeCare(type, records), modifier = Modifier.weight(1.65f),
                textAlign = TextAlign.End, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

internal data class WeightChartScale(val bottom: Long, val step: Long) {
    val top: Long get() = bottom + step * 4
    val ticks: List<Long> get() = (4 downTo 0).map { bottom + step * it }
}

/** Five evenly spaced labels; leave headroom without exaggerating tiny weight changes. */
internal fun weightChartScale(lowest: Long, highest: Long): WeightChartScale {
    require(lowest in 1L..highest && highest <= 1_000_000_000L)
    val desired = maxOf(1.0, (highest - lowest) / 2.0, highest * 0.025)
    val magnitude = 10.0.pow(floor(log10(desired)))
    val step = (listOf(1.0, 2.0, 5.0, 10.0).first { it * magnitude >= desired } * magnitude)
        .toLong().coerceAtLeast(1L)
    val bottom = ((lowest / step - 1L) * step).coerceAtLeast(0L)
    return WeightChartScale(bottom, step)
}

@Composable
private fun WeightTrend(weights: List<CareEntity>) {
    val first = weights.first()
    val last = weights.last()
    val lowest = weights.minOf { it.weightGrams!! }
    val highest = weights.maxOf { it.weightGrams!! }
    val scale = weightChartScale(lowest, highest)
    val span = (scale.top - scale.bottom).toDouble()
    val timeSpan = last.occurredAt - first.occurredAt
    val lineColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val dotColor = MaterialTheme.colorScheme.surface
    val labelStyle = MaterialTheme.typography.labelMedium
    val labelInset = with(LocalDensity.current) { labelStyle.lineHeight.toDp() / 2 }
    val chartHeight = if (LocalDensity.current.fontScale >= 1.3f) 180.dp else 136.dp
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
        verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.height(chartHeight), verticalArrangement = Arrangement.SpaceBetween) {
            scale.ticks.forEach { value ->
                Text("${milliText(value)}kg", style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
                val xInset = 5.dp.toPx()
                val yInset = labelInset.toPx()
                val plotWidth = (size.width - 2 * xInset).coerceAtLeast(1f)
                val plotHeight = (size.height - 2 * yInset).coerceAtLeast(1f)
                repeat(scale.ticks.size) { tick ->
                    val y = yInset + plotHeight * tick / 4f
                    drawLine(gridColor, Offset(xInset, y), Offset(xInset + plotWidth, y), strokeWidth = 1.dp.toPx())
                }
                drawLine(gridColor, Offset(xInset, yInset), Offset(xInset, yInset + plotHeight), strokeWidth = 1.dp.toPx())
                val vertices = weights.mapIndexed { index, record ->
                    val fraction = if (timeSpan > 0L) ((record.occurredAt - first.occurredAt).toDouble() / timeSpan).toFloat()
                        else index.toFloat() / (weights.size - 1)
                    Offset(xInset + fraction.coerceIn(0f, 1f) * plotWidth,
                        yInset + plotHeight * (1 - ((record.weightGrams!! - scale.bottom) / span).toFloat().coerceIn(0f, 1f)))
                }
                vertices.zipWithNext { from, to ->
                    drawLine(lineColor, from, to, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
                vertices.forEach { point ->
                    drawCircle(lineColor, radius = 4.dp.toPx(), center = point)
                    drawCircle(dotColor, radius = 2.3.dp.toPx(), center = point)
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = PetSpacing.xSmall),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(weightAxisDate(first), modifier = Modifier.weight(1f),
                    style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(weightAxisDate(last), modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                    style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
