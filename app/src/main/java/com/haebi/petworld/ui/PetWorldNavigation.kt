package com.haebi.petworld.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.haebi.petworld.PetUiState
import com.haebi.petworld.CareFeedState
import com.haebi.petworld.R
import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.PetDraft
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.PetLoadStatus
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.PetProfile
import com.haebi.petworld.ui.components.PetAvatar
import com.haebi.petworld.ui.components.PetEmptyState
import com.haebi.petworld.ui.components.PetPrimaryButton
import com.haebi.petworld.ui.components.PetSurfaceCard
import com.haebi.petworld.ui.components.PetSecondaryButton
import com.haebi.petworld.ui.icons.PetIcons
import com.haebi.petworld.ui.screens.CareRange
import com.haebi.petworld.ui.screens.CareRecordList
import com.haebi.petworld.ui.screens.CareRecordDetail
import com.haebi.petworld.ui.screens.CareRecordEditor
import com.haebi.petworld.ui.screens.CareRecordRow
import com.haebi.petworld.ui.screens.CareStatisticsScreen
import com.haebi.petworld.ui.screens.PetHomeScreen
import com.haebi.petworld.ui.screens.PetManagerScreen
import com.haebi.petworld.ui.screens.PetProfileEditor
import com.haebi.petworld.ui.screens.PetSwitcherSheet
import com.haebi.petworld.ui.screens.PetWelcomeScreen
import com.haebi.petworld.ui.screens.PetBackupSection
import com.haebi.petworld.ui.theme.PetSpacing
import com.haebi.petworld.ui.theme.ThemeMode

private enum class PrimaryTab(
    val label: String,
    val imageVector: ImageVector,
) {
    HOME("홈", PetIcons.Home),
    RECORDS("기록", PetIcons.Records),
    HEALTH("건강", PetIcons.Health),
    INSIGHTS("통계", PetIcons.Statistics),
    MORE("더보기", PetIcons.More),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetWorldApp(
    themeMode: ThemeMode,
    petState: PetUiState,
    careFeed: CareFeedState,
    photoStore: PetPhotoStore,
    onThemeModeChange: (ThemeMode) -> Unit,
    onSelectPet: (String) -> Unit,
    onSavePet: suspend (PetDraft) -> Result<PetProfile>,
    onArchivedChange: suspend (String, Boolean) -> Result<Unit>,
    onDeletePet: suspend (String) -> Result<Unit>,
    onRetry: () -> Unit,
    onSaveCare: suspend (CareDraft) -> Result<CareEntity>,
    onDeleteCare: suspend (String, String) -> Result<Unit>,
    onRetryCare: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(PrimaryTab.HOME) }
    var showSwitcher by rememberSaveable { mutableStateOf(false) }
    var showManager by rememberSaveable { mutableStateOf(false) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingPetId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorFromManager by rememberSaveable { mutableStateOf(false) }
    var choosingCareType by rememberSaveable { mutableStateOf(false) }
    var careEditorType by rememberSaveable { mutableStateOf<CareType?>(null) }
    var careEditId by rememberSaveable { mutableStateOf<String?>(null) }
    var careDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTypeFilter by rememberSaveable { mutableStateOf<CareType?>(null) }
    var selectedRange by rememberSaveable { mutableStateOf(CareRange.SEVEN) }
    var statisticsRange by rememberSaveable { mutableStateOf(CareRange.THIRTY) }
    val currentEntries = if (careFeed is CareFeedState.Ready) careFeed.entries else emptyList()
    val ready = petState.loadStatus == PetLoadStatus.Ready
    val current = petState.activePet
    val records = currentEntries.filter { it.petId == current?.id }
    val recordCounts = currentEntries.groupingBy { it.petId }.eachCount()
    fun startCare(type: CareType, existingId: String? = null) {
        careEditorType = type
        careEditId = existingId
        choosingCareType = false
        careDetailId = null
    }
    fun openFilteredRecords(type: CareType) {
        selectedTypeFilter = type
        selectedRange = CareRange.TODAY
        tab = PrimaryTab.RECORDS
    }
    BackHandler(enabled = careDetailId != null && careEditorType == null) {
        careDetailId = null
    }

    fun openEditor(id: String?, returnToManager: Boolean) {
        editingPetId = id
        editorFromManager = returnToManager
        showEditor = true
        showManager = false
        showSwitcher = false
    }

    fun returnFromEditor() {
        showEditor = false
        showManager = editorFromManager
    }

    val showTabs = ready && current != null && !showEditor && !showManager && careEditorType == null && careDetailId == null
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = PetSpacing.large, vertical = PetSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.petworld_brand_mark),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.size(width = 48.dp, height = 40.dp),
                    )
                    Text("PetWorld", style = MaterialTheme.typography.titleLarge)
                }
                if (showTabs && current != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = PetSpacing.minTouchTarget)
                            .clickable(role = Role.Button) { showSwitcher = true }
                            .padding(horizontal = PetSpacing.large, vertical = PetSpacing.xSmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PetSpacing.medium),
                    ) {
                        PetAvatar(photoStore, current.name, current.photoFile, size = 36.dp)
                        Text(current.name, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        Icon(PetIcons.More, contentDescription = "반려동물 전환",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        bottomBar = {
            if (showTabs) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    PrimaryTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(item.imageVector, contentDescription = null) },
                            label = { Text(item.label) },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.surface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(modifier = Modifier.fillMaxSize().widthIn(max = PetSpacing.contentMaxWidth)) {
                when {
                    petState.loadStatus == PetLoadStatus.Loading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                            Text("프로필 확인 중", modifier = Modifier.padding(PetSpacing.large))
                        }
                    }
                    petState.loadStatus is PetLoadStatus.Failed -> {
                        Column(
                            modifier = Modifier.padding(PetSpacing.xLarge),
                            verticalArrangement = Arrangement.spacedBy(PetSpacing.large),
                        ) {
                            Text("프로필을 불러오지 못했습니다.",
                                style = MaterialTheme.typography.titleLarge)
                            Text((petState.loadStatus as PetLoadStatus.Failed).message)
                            PetPrimaryButton(label = "다시 시도", onClick = onRetry)
                        }
                    }
                    showEditor -> PetProfileEditor(
                        existing = petState.profiles.firstOrNull { it.id == editingPetId },
                        photoStore = photoStore,
                        onSave = onSavePet,
                        onSaved = {
                            showEditor = false
                            showManager = false
                            tab = PrimaryTab.HOME
                        },
                        onBack = ::returnFromEditor,
                    )
                    showManager -> PetManagerScreen(
                        profiles = petState.profiles,
                        photoStore = photoStore,
                        onEdit = { openEditor(it, returnToManager = true) },
                        onAdd = { openEditor(null, returnToManager = true) },
                        onArchivedChange = onArchivedChange,
                        onDelete = onDeletePet,
                        recordCounts = recordCounts,
                        recordsLoaded = careFeed is CareFeedState.Ready,
                        onBack = { showManager = false },
                    )
                    current == null -> PetWelcomeScreen(
                        archivedCount = petState.profiles.count { it.isArchived },
                        onStart = { openEditor(null, returnToManager = false) },
                        onManageArchived = { showManager = true },
                    )
                    careFeed is CareFeedState.Failed -> Column(
                        modifier = Modifier.padding(PetSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(PetSpacing.large),
                    ) {
                        Text("기록을 불러오지 못했습니다.", style = MaterialTheme.typography.titleLarge)
                        Text(careFeed.message, style = MaterialTheme.typography.bodyMedium)
                        PetPrimaryButton(label = "다시 시도", onClick = onRetryCare)
                    }
                    careFeed == CareFeedState.Loading -> Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Text("기록 확인 중", modifier = Modifier.padding(PetSpacing.large))
                    }
                    careEditorType != null -> {
                        val chosenType = careEditorType!!
                        val existingCare = careEditId?.let { targetId ->
                            records.firstOrNull { it.id == targetId }
                        }
                        if (careEditId != null && existingCare == null) {
                            Column(modifier = Modifier.padding(PetSpacing.large)) {
                                Text("기록을 찾을 수 없습니다.")
                                PetSecondaryButton(label = "목록으로", onClick = {
                                    careEditorType = null; careEditId = null; tab = PrimaryTab.RECORDS
                                })
                            }
                        } else CareRecordEditor(
                            pet = current,
                            type = chosenType,
                            existing = existingCare,
                            photoStore = photoStore,
                            onSave = onSaveCare,
                            onSaved = {
                                careDetailId = careEditId
                                careEditId = null
                                careEditorType = null
                                tab = PrimaryTab.RECORDS
                            },
                            onBack = {
                                careDetailId = careEditId
                                careEditId = null
                                careEditorType = null
                            },
                        )
                    }
                    careDetailId != null -> {
                        val record = records.firstOrNull { it.id == careDetailId }
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(PetSpacing.large),
                            verticalArrangement = Arrangement.spacedBy(PetSpacing.large),
                        ) {
                            if (record == null) {
                                Text("기록을 찾을 수 없습니다.", style = MaterialTheme.typography.bodyLarge)
                                PetSecondaryButton(label = "목록으로", onClick = { careDetailId = null })
                            } else CareRecordDetail(
                                record = record,
                                photoStore = photoStore,
                                onBack = { careDetailId = null },
                                onEdit = { startCare(CareType.valueOf(record.type), record.id) },
                                onDelete = onDeleteCare,
                                onDeleted = { careDetailId = null },
                            )
                        }
                    }
                    else -> Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(PetSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(PetSpacing.xLarge),
                    ) {
                        when (tab) {
                            PrimaryTab.HOME -> PetHomeScreen(
                                selected = current,
                                photoStore = photoStore,
                                records = records,
                                onEdit = { openEditor(current.id, returnToManager = false) },
                                onQuickAdd = { choosingCareType = true },
                                onViewRecord = { careDetailId = it },
                                onViewType = ::openFilteredRecords,
                            )
                            PrimaryTab.RECORDS -> CareRecordList(
                                petName = current.name,
                                records = records,
                                selectedType = selectedTypeFilter,
                                selectedRange = selectedRange,
                                onTypeChange = { selectedTypeFilter = it },
                                onRangeChange = { selectedRange = it },
                                onAdd = { choosingCareType = true },
                                onView = { careDetailId = it },
                            )
                            PrimaryTab.HEALTH -> {
                                Text("${current.name}의 체중·건강", style = MaterialTheme.typography.headlineLarge)
                                PetPrimaryButton(
                                    label = "체중 기록",
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { startCare(CareType.WEIGHT) },
                                )
                                records.filter { it.type == CareType.WEIGHT.name }.take(5).forEach {
                                    CareRecordRow(it, onClick = { careDetailId = it.id })
                                }
                                if (records.none { it.type == CareType.WEIGHT.name }) {
                                    PetEmptyState(
                                        title = "체중 기록이 없습니다",
                                        detail = "체중을 측정한 뒤 기록을 추가해 주세요.",
                                    )
                                }
                                Text("증상·진료·복약·예방 관리는 후속 단계에서 제공됩니다.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            PrimaryTab.INSIGHTS -> CareStatisticsScreen(
                                petName = current.name,
                                records = records,
                                period = statisticsRange,
                                onPeriodChange = { statisticsRange = it },
                            )
                            PrimaryTab.MORE -> SettingsContent(
                                themeMode = themeMode,
                                profileCount = petState.profiles.size,
                                onThemeModeChange = onThemeModeChange,
                                onManage = { showManager = true },
                                onAdd = { openEditor(null, returnToManager = false) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (choosingCareType && current != null && careFeed is CareFeedState.Ready) {
        ModalBottomSheet(onDismissRequest = { choosingCareType = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(PetSpacing.large),
                verticalArrangement = Arrangement.spacedBy(PetSpacing.medium),
            ) {
                Text("${current.name}의 기록 추가", style = MaterialTheme.typography.titleLarge)
                CareType.entries.forEach { type ->
                    PetSecondaryButton(
                        label = type.label,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { startCare(type) },
                    )
                }
            }
        }
    }

    if (showSwitcher && showTabs) {
        PetSwitcherSheet(
            profiles = petState.activeProfiles,
            selectedId = current?.id,
            photoStore = photoStore,
            onSelect = {
                onSelectPet(it)
                showSwitcher = false
            },
            onAdd = { openEditor(null, returnToManager = false) },
            onManage = { showSwitcher = false; showManager = true },
            onDismiss = { showSwitcher = false },
        )
    }
}

@Composable
private fun FeaturePlaceholder(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge)
    PetEmptyState(title = "아직 기록이 없습니다", detail = description)
}

@Composable
private fun SettingsContent(
    themeMode: ThemeMode,
    profileCount: Int,
    onThemeModeChange: (ThemeMode) -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
) {
    Text("설정", style = MaterialTheme.typography.headlineLarge)
    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text("반려동물 프로필", style = MaterialTheme.typography.titleMedium)
        Text("등록된 프로필 ${profileCount}개",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PetPrimaryButton(label = "프로필 관리", onClick = onManage, modifier = Modifier.fillMaxWidth())
        PetPrimaryButton(label = "반려동물 추가", onClick = onAdd, modifier = Modifier.fillMaxWidth())
    }
    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text("화면 모드", style = MaterialTheme.typography.titleMedium)
        Text("기기 설정을 따르거나 라이트·다크 모드를 직접 선택할 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(
            ThemeMode.SYSTEM to "시스템 설정",
            ThemeMode.LIGHT to "라이트",
            ThemeMode.DARK to "다크",
        ).forEach { (mode, label) ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = PetSpacing.minTouchTarget)
                    .selectable(
                        selected = themeMode == mode,
                        role = Role.RadioButton,
                        onClick = { onThemeModeChange(mode) },
                    ).padding(vertical = PetSpacing.xSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
            ) {
                RadioButton(selected = themeMode == mode, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f))
            }
        }
    }
    PetBackupSection()
}
