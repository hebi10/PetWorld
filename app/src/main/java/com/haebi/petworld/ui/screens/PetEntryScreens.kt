package com.haebi.petworld.ui.screens

import android.app.DatePickerDialog
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.haebi.petworld.data.BirthMode
import com.haebi.petworld.data.NeuterStatus
import com.haebi.petworld.data.PetDraft
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.PetProfile
import com.haebi.petworld.data.PetSex
import com.haebi.petworld.data.PetSpecies
import com.haebi.petworld.data.PetValidation
import com.haebi.petworld.data.birthMode
import com.haebi.petworld.ui.components.PetAvatar
import com.haebi.petworld.ui.components.PetPrimaryButton
import com.haebi.petworld.ui.components.PetSecondaryButton
import com.haebi.petworld.ui.components.PetSurfaceCard
import com.haebi.petworld.ui.theme.PetSpacing
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun PetWelcomeScreen(
    archivedCount: Int,
    onStart: () -> Unit,
    onManageArchived: () -> Unit,
    restoring: Boolean,
    onRestore: suspend (Uri, CharArray) -> Result<Unit>,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(PetSpacing.xLarge),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.xLarge),
    ) {
        Text("반려동물의 하루를 함께 기록하세요", style = MaterialTheme.typography.headlineLarge)
        Text(
            "식사와 음수, 활동부터 건강 변화까지 반려동물별로 정리하는 개인 기록 공간입니다.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
            Text("먼저 반려동물을 등록해 주세요", style = MaterialTheme.typography.titleLarge)
            Text(
                "이름과 종만 입력하면 시작할 수 있습니다. 나머지 정보는 나중에 수정할 수 있습니다.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "프로필은 이 기기에 보관됩니다. 별도 계정이나 로그인은 필요하지 않습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PetPrimaryButton(
            label = "반려동물 등록",
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
        )
        if (archivedCount > 0) {
            PetSecondaryButton(
                label = "보관한 프로필 보기 (${archivedCount})",
                onClick = onManageArchived,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PetBackupSection(restoring = restoring, onRestore = onRestore)
    }
}

@Composable
private fun <T> SelectOptions(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                )
            }
        }
    }
}

@Composable
fun PetProfileEditor(
    existing: PetProfile?,
    photoStore: PetPhotoStore,
    onSave: suspend (PetDraft) -> Result<PetProfile>,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var species by rememberSaveable(existing?.id) { mutableStateOf(existing?.species ?: PetSpecies.CAT) }
    var breed by rememberSaveable(existing?.id) { mutableStateOf(existing?.breed.orEmpty()) }
    var birthMode by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.birthMode() ?: BirthMode.UNKNOWN)
    }
    var birthday by rememberSaveable(existing?.id) { mutableStateOf(existing?.birthday.orEmpty()) }
    var ageYears by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.estimatedAgeYears?.toString().orEmpty())
    }
    var sex by rememberSaveable(existing?.id) { mutableStateOf(existing?.sex ?: PetSex.UNKNOWN) }
    var neuter by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.neuterStatus ?: NeuterStatus.UNKNOWN)
    }
    var notes by rememberSaveable(existing?.id) { mutableStateOf(existing?.notes.orEmpty()) }
    var photoUri by rememberSaveable(existing?.id) { mutableStateOf<String?>(null) }
    var removePhoto by rememberSaveable(existing?.id) { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            photoUri = uri.toString()
            removePhoto = false
        }
    }

    val draft = PetDraft(
        id = existing?.id,
        name = name,
        species = species,
        breed = breed,
        birthMode = birthMode,
        birthday = birthday.takeIf { birthMode == BirthMode.EXACT },
        estimatedAgeYears = ageYears,
        sex = sex,
        neuterStatus = neuter,
        notes = notes,
        newPhotoUri = photoUri,
        removePhoto = removePhoto,
    )
    val isDirty = photoUri != null || removePhoto ||
        name != existing?.name.orEmpty() || breed != existing?.breed.orEmpty() ||
        species != (existing?.species ?: PetSpecies.CAT) ||
        birthMode != (existing?.birthMode() ?: BirthMode.UNKNOWN) ||
        (birthMode == BirthMode.EXACT && birthday != existing?.birthday.orEmpty()) ||
        (birthMode == BirthMode.ESTIMATED && ageYears != existing?.estimatedAgeYears?.toString().orEmpty()) ||
        sex != (existing?.sex ?: PetSex.UNKNOWN) ||
        neuter != (existing?.neuterStatus ?: NeuterStatus.UNKNOWN) ||
        notes != existing?.notes.orEmpty()
    fun requestBack() {
        if (isSaving) return
        if (isDirty) confirmExit = true else onBack()
    }

    BackHandler { requestBack() }

    Column(
        modifier = Modifier.fillMaxSize().imePadding()
            .verticalScroll(rememberScrollState()).padding(PetSpacing.large),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (existing == null) "반려동물 등록" else "프로필 수정",
                style = MaterialTheme.typography.headlineMedium,
            )
            TextButton(onClick = ::requestBack, enabled = !isSaving) { Text("닫기") }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PetSpacing.large)) {
            PetAvatar(
                photoStore = photoStore,
                name = name.ifBlank { "반려동물" },
                photoFile = existing?.photoFile.takeUnless { removePhoto },
                previewUri = photoUri,
                size = 84.dp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
                PetSecondaryButton(
                    label = "사진 선택",
                    enabled = !isSaving,
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                )
                if (photoUri != null || (existing?.photoFile != null && !removePhoto)) {
                    TextButton(onClick = {
                        photoUri = null
                        removePhoto = true
                    }, enabled = !isSaving) { Text("사진 제거") }
                }
            }
        }

        OutlinedTextField(
            value = name, onValueChange = { name = it.take(41) },
            label = { Text("이름 (필수)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = name.isBlank(),
            enabled = !isSaving,
        )
        SelectOptions(
            title = "종류 (필수)", options = PetSpecies.entries,
            selected = species, label = { it.label }, onSelect = { species = it },
        )
        OutlinedTextField(
            value = breed, onValueChange = { breed = it.take(81) },
            label = { Text("품종 (선택)") },
            singleLine = true, enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
        SelectOptions(
            title = "나이 정보", options = BirthMode.entries,
            selected = birthMode, label = { it.label }, onSelect = { birthMode = it },
        )
        when (birthMode) {
            BirthMode.EXACT -> {
                PetSecondaryButton(
                    label = if (birthday.isBlank()) "생일 선택" else "생일: $birthday",
                    onClick = {
                        val initial = runCatching { LocalDate.parse(birthday) }
                            .getOrDefault(LocalDate.now())
                        DatePickerDialog(
                            context,
                            { _, y, m, d ->
                                birthday = LocalDate.of(y, m + 1, d)
                                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
                            },
                            initial.year, initial.monthValue - 1, initial.dayOfMonth,
                        ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                    },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BirthMode.ESTIMATED -> OutlinedTextField(
                value = ageYears,
                onValueChange = { ageYears = it.filter(Char::isDigit).take(3) },
                label = { Text("추정 나이 (세)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            BirthMode.UNKNOWN -> Unit
        }
        SelectOptions(
            title = "성별", options = PetSex.entries,
            selected = sex, label = { it.label }, onSelect = { sex = it },
        )
        SelectOptions(
            title = "중성화 여부", options = NeuterStatus.entries,
            selected = neuter, label = { it.label }, onSelect = { neuter = it },
        )
        OutlinedTextField(
            value = notes, onValueChange = { notes = it.take(2001) },
            label = { Text("주의사항·메모 (선택)") },
            minLines = 3, maxLines = 6,
            enabled = !isSaving, modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
        }
        PetPrimaryButton(
            label = if (isSaving) "저장 중..." else if (existing == null) "등록 완료" else "변경 사항 저장",
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSaving,
            onClick = {
                val validation = PetValidation.error(draft)
                if (validation != null) {
                    error = validation
                } else {
                    error = null
                    isSaving = true
                    scope.launch {
                        val result = onSave(draft)
                        isSaving = false
                        result.fold(
                            onSuccess = { onSaved() },
                            onFailure = { error = it.message ?: "프로필을 저장하지 못했습니다." },
                        )
                    }
                }
            },
        )
        Text(
            "이 정보는 기기에만 저장됩니다. 일상 기록과 알림은 다음 단계에서 연결됩니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("변경 내용을 폐기할까요?") },
            text = { Text("저장하지 않은 프로필 정보는 사라집니다.") },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; onBack() }) { Text("변경 폐기") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("계속 작성") }
            },
        )
    }
}
