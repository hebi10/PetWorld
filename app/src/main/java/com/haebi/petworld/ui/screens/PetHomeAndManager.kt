package com.haebi.petworld.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PetHomeScreen(
    selected: PetProfile,
    photoStore: PetPhotoStore,
    onEdit: () -> Unit,
) {
    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN))
    Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
        Text(today, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${selected.name}의 하루", style = MaterialTheme.typography.headlineLarge)
    }
    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PetAvatar(
                photoStore = photoStore,
                name = selected.name,
                photoFile = selected.photoFile,
                size = 72.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall),
            ) {
                Text(selected.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    listOf(
                        selected.species.label,
                        selected.breed.takeIf { it.isNotBlank() },
                        selected.ageLabel(),
                    ).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected.notes.isNotBlank()) {
            Text("주의사항", style = MaterialTheme.typography.labelLarge)
            Text(selected.notes, style = MaterialTheme.typography.bodyMedium)
        }
        PetSecondaryButton(label = "프로필 수정", onClick = onEdit)
    }
    Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.medium)) {
        Text("오늘의 돌봄", style = MaterialTheme.typography.titleLarge)
        listOf(
            "식사" to PetIcons.Food,
            "음수" to PetIcons.Water,
            "배변" to PetIcons.Elimination,
            "활동" to PetIcons.Activity,
            "체중" to PetIcons.Weight,
        ).forEach { (label, glyph) ->
            PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(PetSpacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PetIcon(imageVector = glyph, contentDescription = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("기록 없음", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            "기록 입력은 1-D 단계에서 제공됩니다. '기록 없음'은 돌봄을 하지 않았다는 의미가 아닙니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
        Text("최근 기록", style = MaterialTheme.typography.titleLarge)
        PetEmptyState(
            title = "아직 입력한 기록이 없습니다",
            detail = "식사, 음수, 배변, 활동, 체중 기록을 다음 개발 단계에서 추가할 수 있습니다.",
        )
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
        verticalArrangement = Arrangement.spacedBy(PetSpacing.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("프로필 관리", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("닫기") }
        }
        Text(
            "반려동물별 기본 정보와 보관 상태를 관리합니다. 보관한 프로필은 홈 목록에서 숨겨집니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PetPrimaryButton(label = "새 프로필 추가", onClick = onAdd, modifier = Modifier.fillMaxWidth())
        if (errorText != null) {
            Text(errorText.orEmpty(), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        profiles.forEach { pet ->
            PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PetAvatar(photoStore, pet.name, pet.photoFile, size = 56.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pet.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${pet.species.label} · ${if (pet.isArchived) "보관됨" else "사용 중"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                PetSecondaryButton(
                    label = "정보 수정",
                    onClick = { onEdit(pet.id) },
                    enabled = pendingId == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                PetSecondaryButton(
                    label = if (pet.isArchived) "보관 해제" else "프로필 보관",
                    enabled = pendingId == null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        pendingId = pet.id
                        scope.launch {
                            onArchivedChange(pet.id, !pet.isArchived).fold(
                                onSuccess = { errorText = null },
                                onFailure = { errorText = it.message ?: "프로필 상태 변경에 실패했습니다." },
                            )
                            pendingId = null
                        }
                    },
                )
                TextButton(
                    enabled = pendingId == null,
                    onClick = {
                        deletePet = pet
                        deleteStage = 1
                    },
                ) { Text("영구 삭제", color = MaterialTheme.colorScheme.error) }
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
                        "${target.name}의 프로필과 사진이 삭제됩니다. 나중에 복구할 수 없습니다."
                    else "${target.name}의 프로필을 영구 삭제합니다. 이 작업은 취소할 수 없습니다."
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
