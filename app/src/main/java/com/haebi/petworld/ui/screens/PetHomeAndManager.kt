package com.haebi.petworld.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.localDate
import com.haebi.petworld.data.displayValue
import com.haebi.petworld.data.summarizeCare
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.PetProfile
import com.haebi.petworld.data.ageLabel
import com.haebi.petworld.ui.components.PetAvatar
import com.haebi.petworld.ui.components.PetEmptyState
import com.haebi.petworld.ui.components.PetIcon
import com.haebi.petworld.ui.components.PetPrimaryButton
import com.haebi.petworld.ui.components.PetSecondaryButton
import com.haebi.petworld.ui.components.PetSurfaceCard
import com.haebi.petworld.ui.icons.PetIcons
import com.haebi.petworld.ui.theme.PetSpacing
import com.haebi.petworld.ui.theme.useCompactPetLayout
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PetHomeScreen(
    selected: PetProfile,
    photoStore: PetPhotoStore,
    records: List<CareEntity>,
    onEdit: () -> Unit,
    onQuickAdd: () -> Unit,
    onViewRecord: (String) -> Unit,
    onViewType: (CareType) -> Unit,
    onSwitchPet: () -> Unit,
    onViewAll: () -> Unit,
    onViewToday: () -> Unit,
    expandedLayout: Boolean = false,
) {
    val day = LocalDate.now()
    val todayLabel = day.format(DateTimeFormatter.ofPattern("M월 d일 (EEE)", Locale.KOREAN))
    val todayRecords = records.filter { it.localDate() == day }
    val largeText = LocalDensity.current.fontScale >= 1.3f

    val intro: @Composable () -> Unit = {
        PetHomeIntro(selected, photoStore, todayLabel, largeText, onEdit, onSwitchPet)
        PetHomeDailySummary(todayRecords, largeText, onQuickAdd, onViewType, onViewToday)
    }
    if (expandedLayout) {
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.large),
            verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PetSpacing.medium)) { intro() }
            Column(modifier = Modifier.weight(1f)) { PetHomeRecentRecords(records, onViewRecord, onViewAll) }
        }
    } else {
        intro()
        PetHomeRecentRecords(records, onViewRecord, onViewAll)
    }
}

@Composable
private fun PetHomeIntro(
    selected: PetProfile,
    photoStore: PetPhotoStore,
    todayLabel: String,
    largeText: Boolean,
    onEdit: () -> Unit,
    onSwitchPet: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall)) {
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
            verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onSwitchPet)
                    .semantics { contentDescription = "반려동물 전환: ${selected.name}" },
                horizontalArrangement = Arrangement.spacedBy(PetSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PetAvatar(photoStore, selected.name, selected.photoFile,
                    size = if (largeText) 56.dp else 72.dp, shape = RectangleShape)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${selected.name}의 하루", modifier = Modifier.weight(1f),
                            style = if (largeText) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Icon(PetIcons.Expand, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    Text(todayLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(listOfNotNull(selected.species.label, selected.breed.takeIf { it.isNotBlank() }, selected.ageLabel()).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onEdit) { Text("수정") }
        }
        if (selected.notes.isNotBlank()) {
            Text("주의사항 · ${selected.notes}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PetHomeDailySummary(
    todayRecords: List<CareEntity>,
    largeText: Boolean,
    onQuickAdd: () -> Unit,
    onViewType: (CareType) -> Unit,
    onViewToday: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
        if (largeText) PetPrimaryButton("빠른 기록 추가", onClick = onQuickAdd, modifier = Modifier.fillMaxWidth(),
            prominent = true, leadingIcon = PetIcons.Add)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RectangleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = PetSpacing.medium, end = PetSpacing.xSmall),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("오늘의 돌봄", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onViewToday) {
                            Text("자세히 보기")
                            Icon(PetIcons.Chevron, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                CareType.entries.forEachIndexed { index, type ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = PetSpacing.minTouchTarget)
                            .clickable(role = Role.Button) { onViewType(type) }
                            .padding(horizontal = PetSpacing.medium, vertical = PetSpacing.xSmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PetSpacing.medium),
                    ) {
                        Icon(careGlyph(type), contentDescription = null, modifier = Modifier.size(24.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val count = todayRecords.count { it.type == type.name }
                            if (largeText) {
                                Text(type.label, style = MaterialTheme.typography.titleSmall)
                                Text("${count}건", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text("${type.label} · ${count}건", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        Text(summarizeCare(type, todayRecords), modifier = Modifier.weight(1.65f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            textAlign = TextAlign.End)
                    }
                }
            }
        }
        if (!largeText) PetPrimaryButton("빠른 기록 추가", onClick = onQuickAdd, modifier = Modifier.fillMaxWidth(),
            prominent = true, leadingIcon = PetIcons.Add)
    }
}

@Composable
private fun PetHomeRecentRecords(records: List<CareEntity>, onViewRecord: (String) -> Unit, onViewAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("최근 기록", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onViewAll) { Text("전체 보기"); Icon(PetIcons.Chevron, contentDescription = null) }
        }
        val recent = records.sortedWith(compareByDescending<CareEntity> { it.occurredAt }.thenByDescending { it.createdAt }).take(5)
        if (recent.isEmpty()) {
            PetEmptyState("아직 입력한 기록이 없습니다", "빠른 기록으로 식사·음수·배변·활동·체중을 추가해 주세요.")
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RectangleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    recent.forEachIndexed { index, record ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val type = CareType.valueOf(record.type)
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = PetSpacing.minTouchTarget)
                                .clickable(role = Role.Button) { onViewRecord(record.id) }
                                .padding(horizontal = PetSpacing.medium, vertical = PetSpacing.xSmall),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                        ) {
                            Box(modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainer),
                                contentAlignment = Alignment.Center) {
                                Icon(careGlyph(type), contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${record.homeClockLabel()} · ${type.label}",
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(record.displayValue(), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (record.note.isNotBlank()) Text(record.note, style = MaterialTheme.typography.bodyMedium)
                                if (record.photoFile != null) Text("관찰 사진 있음", style = MaterialTheme.typography.labelMedium)
                            }
                            Icon(PetIcons.Chevron, contentDescription = null, modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetSwitcherSheet(
    profiles: List<PetProfile>,
    selectedId: String?,
    photoStore: PetPhotoStore,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PetSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PetSpacing.medium),
        ) {
            Text("반려동물 선택", style = MaterialTheme.typography.titleLarge)
            profiles.forEach { pet ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { onSelect(pet.id) }
                        .padding(vertical = PetSpacing.small),
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PetAvatar(photoStore, pet.name, pet.photoFile, size = 44.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pet.name, style = MaterialTheme.typography.bodyLarge)
                        Text(pet.species.label, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selectedId == pet.id) {
                        Icon(PetIcons.Done, contentDescription = "현재 선택된 반려동물",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            PetPrimaryButton(label = "반려동물 추가", onClick = onAdd, modifier = Modifier.fillMaxWidth())
            PetSecondaryButton(label = "전체 프로필 관리", onClick = onManage,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun PetManagerScreen(
    profiles: List<PetProfile>,
    photoStore: PetPhotoStore,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onArchivedChange: suspend (String, Boolean) -> Result<Unit>,
    onDelete: suspend (String) -> Result<Unit>,
    recordCounts: Map<String, Int>,
    recordsLoaded: Boolean,
    onBack: () -> Unit,
) {
    var deletePet by remember { mutableStateOf<PetProfile?>(null) }
    var deleteStage by remember { mutableStateOf(0) }
    var pendingId by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler { if (pendingId == null) onBack() }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
            .padding(PetSpacing.large),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "프로필 관리",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack, enabled = pendingId == null) { Text("닫기") }
        }
        PetPrimaryButton(
            label = "새 프로필 추가",
            onClick = onAdd,
            enabled = pendingId == null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (errorText != null) {
            Text(
                errorText.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        profiles.forEach { pet ->
            PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PetAvatar(photoStore, pet.name, pet.photoFile, size = 44.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            pet.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${pet.species.label} · ${if (pet.isArchived) "보관됨" else "사용 중"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = { onEdit(pet.id) },
                        enabled = pendingId == null,
                    ) { Text("수정") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PetSecondaryButton(
                        label = if (pet.isArchived) "보관 해제" else "프로필 보관",
                        modifier = Modifier.weight(1f),
                        enabled = pendingId == null,
                        onClick = {
                            pendingId = pet.id
                            scope.launch {
                                onArchivedChange(pet.id, !pet.isArchived).fold(
                                    onSuccess = { errorText = null },
                                    onFailure = {
                                        errorText = it.message ?: "프로필 상태 변경에 실패했습니다."
                                    },
                                )
                                pendingId = null
                            }
                        },
                    )
                    TextButton(
                        enabled = pendingId == null && recordsLoaded,
                        onClick = {
                            deletePet = pet
                            deleteStage = 1
                        },
                    ) { Text("영구 삭제", color = MaterialTheme.colorScheme.error) }
                }
                if (!recordsLoaded) {
                    Text(
                        "기록을 불러오지 못한 상태에서는 삭제할 수 없습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val target = deletePet
    if (target != null && deleteStage > 0) {
        AlertDialog(
            onDismissRequest = { if (pendingId == null) { deletePet = null; deleteStage = 0 } },
            title = {
                Text(if (deleteStage == 1) "프로필을 삭제하시겠습니까?" else "최종 삭제 확인")
            },
            text = {
                Text(
                    if (deleteStage == 1)
                        "${target.name}의 프로필, 사진 및 연결된 기록 ${recordCounts[target.id] ?: 0}건이 함께 영구 삭제됩니다."
                    else "${target.name}의 기록 ${recordCounts[target.id] ?: 0}건을 포함한 모든 데이터를 삭제합니다. 복구할 수 없습니다."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = pendingId == null,
                    onClick = {
                        if (deleteStage == 1) {
                            deleteStage = 2
                        } else {
                            pendingId = target.id
                            scope.launch {
                                onDelete(target.id).fold(
                                    onSuccess = { errorText = null },
                                    onFailure = {
                                        errorText = it.message ?: "프로필 삭제에 실패했습니다."
                                    },
                                )
                                deletePet = null
                                deleteStage = 0
                                pendingId = null
                            }
                        }
                    },
                ) { Text(if (deleteStage == 1) "계속" else "영구 삭제") }
            },
            dismissButton = {
                TextButton(
                    enabled = pendingId == null,
                    onClick = { deletePet = null; deleteStage = 0 },
                ) { Text("취소") }
            },
        )
    }
}
