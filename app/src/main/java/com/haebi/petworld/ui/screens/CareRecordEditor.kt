package com.haebi.petworld.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
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
import com.haebi.petworld.ui.components.PetPrimaryButton
import com.haebi.petworld.ui.theme.PetSpacing
import kotlinx.coroutines.launch
import java.time.ZoneId

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
    suffixText: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
        supportingText = if (supportingText == null) null else {
            { Text(supportingText, style = MaterialTheme.typography.bodyMedium) }
        },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        suffix = if (suffixText == null) null else {
            { Text(suffixText, style = MaterialTheme.typography.bodyMedium) }
        },
        shape = RectangleShape,
        modifier = modifier,
    )
}

@Composable
private fun FoodPortionInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    unit: PortionUnit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    CareFormTextField(
        value = value,
        onValueChange = onValueChange,
        label = "$label (${unit.label})",
        keyboardType = KeyboardType.Decimal,
        suffixText = unit.label,
        modifier = modifier,
    )
}

@Composable
private fun FoodPortionFields(
    unit: PortionUnit,
    served: String,
    consumed: String,
    onUnitChange: (PortionUnit) -> Unit,
    onServedChange: (String) -> Unit,
    onConsumedChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.small),
    ) {
        ChoiceRow("단위", unit, PortionUnit.entries, { it.label }, onUnitChange)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.3f
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
                    FoodPortionInput(served, onServedChange, "제공량", unit)
                    FoodPortionInput(consumed, onConsumedChange, "섭취량", unit)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                ) {
                    FoodPortionInput(
                        served, onServedChange, "제공량", unit,
                        modifier = Modifier.weight(1f),
                    )
                    FoodPortionInput(
                        consumed, onConsumedChange, "섭취량", unit,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Text(
            "제공량은 필수, 섭취량은 선택입니다. 미측정은 빈칸, 확인된 미섭취는 0으로 입력합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
        modifier = Modifier.fillMaxSize().imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.weight(1f)
                .widthIn(max = PetSpacing.careFormMaxWidth)
                .fillMaxWidth()
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
                        FoodPortionFields(
                            unit = foodUnit,
                            served = amount,
                            consumed = consumed,
                            onUnitChange = { selectedUnit ->
                                if (foodUnit != selectedUnit) {
                                    amount = ""
                                    consumed = ""
                                    foodUnit = selectedUnit
                                }
                            },
                            onServedChange = { amount = it.take(20) },
                            onConsumedChange = { consumed = it.take(20) },
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
                        CareEliminationPhotoField(
                            photoStore = photoStore,
                            photoFile = existing?.photoFile,
                            previewUri = photoUri,
                            removePhoto = removePhoto,
                            onChoosePhoto = {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onRemovePhoto = { photoUri = null; removePhoto = true },
                        )
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
            CareObservationDateTime(
                occurredAt = occurredAt,
                zone = zone,
                onOccurredAtChange = { occurredAt = it },
            )
            CareFormTextField(
                value = note,
                onValueChange = { note = it.take(2_001) },
                label = "관찰 메모 (선택)",
                singleLine = false,
                minLines = 2,
                maxLines = 6,
            )
            Text(
                "기록은 ${pet.name}에게만 저장됩니다. 날짜·양·메모는 나중에 수정할 수 있습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column(
            modifier = Modifier.widthIn(max = PetSpacing.careFormMaxWidth)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = PetSpacing.large, vertical = PetSpacing.small),
            verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall),
        ) {
            if (error != null) {
                Text(
                    error.orEmpty(),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error,
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
        }
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
