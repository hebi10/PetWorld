package com.haebi.petworld.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import com.haebi.petworld.data.ActivityKind
import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.CareValidation
import com.haebi.petworld.data.EliminationKind
import com.haebi.petworld.data.FoodKind
import com.haebi.petworld.data.PetProfile
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.PortionUnit
import com.haebi.petworld.data.WeightUnit
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.toDraft
import com.haebi.petworld.ui.components.PetAvatar
import com.haebi.petworld.ui.components.PetPrimaryButton
import com.haebi.petworld.ui.components.PetSecondaryButton
import com.haebi.petworld.ui.theme.PetSpacing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
private fun <T> ChoiceRow(
    label: String,
    selected: T,
    options: List<T>,
    text: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
            verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(text(option), style = MaterialTheme.typography.bodyMedium) },
                    shape = RectangleShape,
                )
            }
        }
    }
}

@Composable
private fun CareFormFields(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.medium),
        content = content,
    )
}

@Composable
private fun CareFormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    supportingText: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = if (supportingText == null) null else {
            { Text(supportingText, style = MaterialTheme.typography.bodyMedium) }
        },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = RectangleShape,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun CareRecordEditor(
    pet: PetProfile,
    type: CareType,
    existing: CareEntity?,
    photoStore: PetPhotoStore,
    onSave: suspend (CareDraft) -> Result<CareEntity>,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val original = remember(pet.id, type, existing?.id) {
        existing?.toDraft() ?: CareDraft(
            petId = pet.id,
            type = type,
            occurredAt = System.currentTimeMillis(),
            timezoneId = ZoneId.systemDefault().id,
            amount = if (type == CareType.ELIMINATION) "1" else "",
        )
    }
    var amount by rememberSaveable(pet.id, type, existing?.id) { mutableStateOf(original.amount) }
    var consumed by rememberSaveable(pet.id, type, existing?.id) {
        mutableStateOf(original.consumedAmount)
    }
    var foodProductName by rememberSaveable(pet.id, type, existing?.id) {
        mutableStateOf(original.foodProductName)
    }
    var foodKind by rememberSaveable(pet.id, type, existing?.id) {
        mutableStateOf(original.foodKind)
    }
    var foodUnit by rememberSaveable(pet.id, type, existing?.id) {
        mutableStateOf(original.foodUnit)
    }
    var measured by rememberSaveable(pet.id, type, existing?.id) {
        mutableStateOf(original.waterMeasured)
    }
    var eliminationKind by rememberSaveable(pet.id, type, existing?.id) {
        mutableStateOf(original.eliminationKind)
    }
    var activityKind by rememberSaveable(pet.id, type, existing?.id) {
        mutableStateOf(original.activityKind)
    }
    var weightUnit by rememberSaveable(pet.id, type, existing?.id) {
        mutableStateOf(original.weightUnit)
    }
    var note by rememberSaveable(pet.id, type, existing?.id) { mutableStateOf(original.note) }
    var occurredAt by rememberSaveable(pet.id, type, existing?.id) {
        mutableStateOf(original.occurredAt)
    }
    var photoUri by rememberSaveable(pet.id, type, existing?.id) { mutableStateOf<String?>(null) }
    var removePhoto by rememberSaveable(pet.id, type, existing?.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) { photoUri = uri.toString(); removePhoto = false }
    }
    val zone = remember(original.timezoneId) {
        runCatching { ZoneId.of(original.timezoneId) }.getOrDefault(ZoneId.systemDefault())
    }

    val draft = CareDraft(
        id = original.id,
        petId = pet.id,
        type = type,
        occurredAt = occurredAt,
        timezoneId = original.timezoneId,
        amount = amount,
        consumedAmount = consumed,
        foodKind = foodKind,
        foodProductName = foodProductName,
        foodUnit = foodUnit,
        waterMeasured = measured,
        eliminationKind = eliminationKind,
        activityKind = activityKind,
        weightUnit = weightUnit,
        note = note,
        newPhotoUri = photoUri,
        removePhoto = removePhoto,
    )

    fun dismiss() {
        if (saving) return
        if (draft != original) confirmExit = true else onBack()
    }
    BackHandler { dismiss() }

    Column(
        modifier = Modifier.fillMaxSize().imePadding()
            .verticalScroll(rememberScrollState())
            .padding(PetSpacing.large),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
        ) {
            Text(
                "${pet.name} · ${type.label} ${if (existing == null) "기록" else "수정"}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = ::dismiss, enabled = !saving) { Text("닫기") }
        }

        CareFormFields {
            when (type) {
                CareType.FOOD -> {
                    ChoiceRow("종류", foodKind, FoodKind.entries, { it.label }) { foodKind = it }
                    CareFormTextField(
                        value = foodProductName,
                        onValueChange = { foodProductName = it.take(101) },
                        label = "제품명 (선택)",
                    )
                    ChoiceRow("단위", foodUnit, PortionUnit.entries, { it.label }) {
                        if (foodUnit != it) {
                            amount = ""
                            consumed = ""
                            foodUnit = it
                        }
                    }
                    CareFormTextField(
                        value = amount,
                        onValueChange = { amount = it.take(20) },
                        label = "제공량 (${foodUnit.label}) · 필수",
                        keyboardType = KeyboardType.Decimal,
                    )
                    CareFormTextField(
                        value = consumed,
                        onValueChange = { consumed = it.take(20) },
                        label = "실제 섭취량 (${foodUnit.label}) · 선택",
                        supportingText = "빈칸은 미측정, 0은 먹지 않음을 확인한 경우입니다.",
                        keyboardType = KeyboardType.Decimal,
                    )
                }
                CareType.WATER -> {
                    ChoiceRow("섭취량 측정 여부", measured, listOf(true, false),
                        { if (it) "측정됨" else "미측정" }) { measured = it }
                    if (measured) {
                        CareFormTextField(
                            value = amount,
                            onValueChange = { amount = it.take(10) },
                            label = "음수량 (mL) · 필수",
                            supportingText = "직접 확인한 0mL는 미측정과 구분됩니다.",
                            keyboardType = KeyboardType.Number,
                        )
                    }
                }
                CareType.ELIMINATION -> {
                    ChoiceRow("종류", eliminationKind, EliminationKind.entries,
                        { it.label }) { eliminationKind = it }
                    CareFormTextField(
                        value = amount,
                        onValueChange = { amount = it.take(3) },
                        label = "횟수 (회)",
                        keyboardType = KeyboardType.Number,
                    )
                    Text("관찰 사진 (선택)", style = MaterialTheme.typography.labelLarge)
                    PetAvatar(
                        photoStore = photoStore,
                        name = "배변 관찰",
                        photoFile = existing?.photoFile.takeUnless { removePhoto },
                        previewUri = photoUri,
                        size = 104.dp,
                    )
                    PetSecondaryButton(
                        label = "사진 선택",
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    )
                    if (photoUri != null || (existing?.photoFile != null && !removePhoto)) {
                        TextButton(onClick = { photoUri = null; removePhoto = true }) {
                            Text("관찰 사진 제거")
                        }
                    }
                }
                CareType.ACTIVITY -> {
                    ChoiceRow("활동", activityKind, ActivityKind.entries,
                        { it.label }) { activityKind = it }
                    CareFormTextField(
                        value = amount,
                        onValueChange = { amount = it.take(4) },
                        label = "지속시간 (분)",
                        keyboardType = KeyboardType.Number,
                    )
                }
                CareType.WEIGHT -> {
                    ChoiceRow("단위", weightUnit, WeightUnit.entries,
                        { it.label }) { if (weightUnit != it) { amount = ""; weightUnit = it } }
                    CareFormTextField(
                        value = amount,
                        onValueChange = { amount = it.take(20) },
                        label = "체중 (${weightUnit.label})",
                        keyboardType = KeyboardType.Decimal,
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
            Text("관찰 시각", style = MaterialTheme.typography.labelLarge)
            val local = Instant.ofEpochMilli(occurredAt).atZone(zone)
            PetSecondaryButton(
                label = local.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                onClick = {
                    val day = local.toLocalDate()
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            val datetime = LocalDateTime.of(
                                LocalDate.of(year, month + 1, dayOfMonth),
                                local.toLocalTime(),
                            )
                            occurredAt = datetime.atZone(zone).toInstant().toEpochMilli()
                        },
                        day.year, day.monthValue - 1, day.dayOfMonth,
                    ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                },
            )
            PetSecondaryButton(
                label = local.format(DateTimeFormatter.ofPattern("HH:mm")),
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            val datetime = local.toLocalDate().atTime(hour, minute)
                            occurredAt = datetime.atZone(zone).toInstant().toEpochMilli()
                        },
                        local.hour, local.minute, true,
                    ).show()
                },
            )
        }
        CareFormTextField(
            value = note,
            onValueChange = { note = it.take(2_001) },
            label = "관찰 메모 (선택)",
            singleLine = false,
            minLines = 2,
            maxLines = 6,
        )
        if (error != null) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        PetPrimaryButton(
            label = if (saving) "저장 중..." else if (existing == null) "기록 저장" else "변경 저장",
            enabled = !saving, modifier = Modifier.fillMaxWidth(),
            onClick = {
                val issue = CareValidation.error(draft)
                if (issue != null) {
                    error = issue
                } else {
                    error = null
                    saving = true
                    scope.launch {
                        onSave(draft).fold(
                            onSuccess = { onSaved() },
                            onFailure = { error = it.message ?: "기록을 저장하지 못했습니다." },
                        )
                        saving = false
                    }
                }
            },
        )
        Text(
            "기록은 ${pet.name}에게만 저장됩니다. 날짜·양·메모는 나중에 수정할 수 있습니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("저장하지 않고 나가시겠습니까?") },
            text = { Text("변경한 기록은 저장되지 않습니다.") },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; onBack() }) { Text("변경 폐기") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("계속 작성") }
            },
        )
    }
}
