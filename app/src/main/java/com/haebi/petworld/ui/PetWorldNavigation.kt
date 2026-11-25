package com.haebi.petworld.ui

import android.net.Uri
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.haebi.petworld.PetUiState
import com.haebi.petworld.CareFeedState
import com.haebi.petworld.CareReadScope
import com.haebi.petworld.R
import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareHistoryPage
import com.haebi.petworld.data.CareHistoryRequest
import kotlinx.coroutines.flow.Flow
import com.haebi.petworld.data.CareType
import com.haebi.petworld.data.PetDraft
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.PetLoadStatus
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.PetProfile
import com.haebi.petworld.ui.components.PetAvatar
import com.haebi.petworld.ui.components.PetMenuRow
import com.haebi.petworld.ui.components.PetEmptyState
import com.haebi.petworld.ui.components.PetPrimaryButton
import com.haebi.petworld.ui.components.PetSurfaceCard
import com.haebi.petworld.ui.components.PetSecondaryButton
import com.haebi.petworld.ui.icons.PetIcons
import com.haebi.petworld.ui.screens.CareRange
import com.haebi.petworld.ui.screens.CareRecordList
import com.haebi.petworld.ui.screens.CareRecordDetail
import com.haebi.petworld.ui.screens.CareRecordEditor
import com.haebi.petworld.ui.screens.CareStatisticsScreen
import com.haebi.petworld.ui.screens.PetHomeScreen
import com.haebi.petworld.ui.screens.PetHealthScreen
import com.haebi.petworld.ui.screens.PetManagerScreen
import com.haebi.petworld.ui.screens.PetProfileEditor
import com.haebi.petworld.ui.screens.PetSwitcherSheet
import com.haebi.petworld.ui.screens.PetWelcomeScreen
import com.haebi.petworld.ui.screens.PetBackupSection
import com.haebi.petworld.ui.theme.PetSpacing
import com.haebi.petworld.ui.theme.useCompactPetLayout
import com.haebi.petworld.ui.theme.useExpandedPetLayout
import com.haebi.petworld.ui.theme.ThemeMode

private enum class PrimaryTab(
    val label: String,
    val imageVector: ImageVector,
    val selectedVector: ImageVector,
) {
    HOME("홈", PetIcons.Home, PetIcons.HomeSelected),
    RECORDS("기록", PetIcons.Records, PetIcons.RecordsSelected),
    HEALTH("건강", PetIcons.Health, PetIcons.HealthSelected),
    INSIGHTS("통계", PetIcons.Statistics, PetIcons.StatisticsSelected),
    MORE("더보기", PetIcons.More, PetIcons.More),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetWorldApp(
    themeMode: ThemeMode,
    guestScope: Boolean = true,
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
    onCareReadScopeChange: (CareReadScope) -> Unit,
    observeCareHistory: (CareHistoryRequest) -> Flow<CareHistoryPage>,
    restoring: Boolean,
    onRestoreBackup: suspend (Uri, CharArray) -> Result<Unit>,
) {
    val context = LocalContext.current
    val onOpenAccount: (() -> Unit)? = if (context.packageName == "com.haebi.petworld.dev") {
        {
            try {
                context.startActivity(
                    Intent().setClassName(
                        context.packageName,
                        "com.haebi.petworld.auth.PetWorldAccountActivity",
                    )
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "계정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    } else {
        null
    }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val fontScale = LocalDensity.current.fontScale
    val compactUi = useCompactPetLayout(screenWidthDp, fontScale)
    val expandedUi = useExpandedPetLayout(screenWidthDp, fontScale)
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
    val ready = petState.loadStatus == PetLoadStatus.Ready
    val current = petState.activePet
    val readScope = if (tab == PrimaryTab.RECORDS) {
        CareReadScope.Timeline(careEditId ?: careDetailId)
    } else CareReadScope.All
    LaunchedEffect(readScope) { onCareReadScopeChange(readScope) }
    val petFeed = (careFeed as? CareFeedState.Ready)?.takeIf { it.petId == current?.id }
    val scopedFeed = petFeed?.takeIf { it.scope == readScope }
    val records = scopedFeed?.entries.orEmpty()
    val recordCounts = petFeed?.recordCounts.orEmpty()
    val timelineVisible = tab == PrimaryTab.RECORDS && careEditorType == null &&
        (careDetailId == null || expandedUi)
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

    val showTabs = ready && current != null && !showEditor && !showManager &&
        careEditorType == null && (careDetailId == null || (expandedUi && tab == PrimaryTab.RECORDS))
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                if (!showTabs || tab == PrimaryTab.HOME || expandedUi) {
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
                            modifier = Modifier.size(width = 40.dp, height = 36.dp),
                        )
                        Text("PetWorld", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (showTabs && tab == PrimaryTab.HOME) {
                            IconButton(onClick = { tab = PrimaryTab.MORE }) {
                                Icon(PetIcons.Settings, contentDescription = "설정")
                            }
                        }
                    }
                }
                if (showTabs && current != null && tab != PrimaryTab.HOME && tab != PrimaryTab.MORE) {
                    if (!expandedUi) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(
                                start = PetSpacing.large, end = PetSpacing.large, top = PetSpacing.medium,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                        ) {
                            Text(
                                when (tab) {
                                    PrimaryTab.RECORDS -> "${current.name}의 생활 기록"
                                    PrimaryTab.HEALTH -> "${current.name}의 체중·건강"
                                    else -> "${current.name}의 기록 통계"
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            if (tab == PrimaryTab.RECORDS) {
                                PetPrimaryButton("기록 추가", onClick = { choosingCareType = true })
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier.padding(horizontal = PetSpacing.large, vertical = PetSpacing.small)
                            .heightIn(min = PetSpacing.minTouchTarget)
                            .clickable(role = Role.Button) { showSwitcher = true }
                            .semantics(mergeDescendants = true) {
                                contentDescription = "반려동물 전환: ${current.name}"
                            },
                        shape = RectangleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = PetSpacing.medium, vertical = PetSpacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                        ) {
                            Text(current.name, style = MaterialTheme.typography.titleMedium)
                            Icon(PetIcons.Expand, contentDescription = null)
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (showTabs) {
                Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    PrimaryTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = {
                                tab = item
                                if (item != PrimaryTab.RECORDS) careDetailId = null
                            },
                            icon = { Icon(if (tab == item) item.selectedVector else item.imageVector,
                                contentDescription = null, modifier = Modifier.size(24.dp)) },
                            label = { Text(item.label, fontWeight = if (tab == item) FontWeight.Bold else FontWeight.Normal) },
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
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val maxContentWidth = if (showTabs && expandedUi && tab in listOf(
                PrimaryTab.HOME, PrimaryTab.RECORDS, PrimaryTab.INSIGHTS
            )) PetSpacing.expandedContentMaxWidth else PetSpacing.contentMaxWidth
            Box(modifier = Modifier.fillMaxSize().widthIn(max = maxContentWidth)) {
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
                        recordsLoaded = petFeed != null,
                        onBack = { showManager = false },
                    )
                    current == null -> PetWelcomeScreen(
                        archivedCount = petState.profiles.count { it.isArchived },
                        allowGuestBackup = guestScope,
                        onStart = { openEditor(null, returnToManager = false) },
                        onOpenAccount = onOpenAccount,
                        onManageArchived = { showManager = true },
                        restoring = restoring,
                        onRestore = onRestoreBackup,
                    )
                    careFeed is CareFeedState.Failed -> Column(
                        modifier = Modifier.padding(PetSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(PetSpacing.large),
                    ) {
                        Text("기록을 불러오지 못했습니다.", style = MaterialTheme.typography.titleLarge)
                        Text(careFeed.message, style = MaterialTheme.typography.bodyMedium)
                        PetPrimaryButton(label = "다시 시도", onClick = onRetryCare)
                    }
                    scopedFeed == null && !timelineVisible -> Column(
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
                    careDetailId != null && !(expandedUi && tab == PrimaryTab.RECORDS) -> {
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
                    else -> if (expandedUi && tab == PrimaryTab.RECORDS) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(PetSpacing.large),
                            horizontalArrangement = Arrangement.spacedBy(PetSpacing.large),
                        ) {
                            key(current.id) {
                                CareRecordList(
                                    petName = current.name,
                                    petId = current.id,
                                    observeHistory = observeCareHistory,
                                    selectedType = selectedTypeFilter,
                                    selectedRange = selectedRange,
                                    onTypeChange = { selectedTypeFilter = it },
                                    onRangeChange = { selectedRange = it },
                                    onAdd = { choosingCareType = true },
                                    onView = { careDetailId = it },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f).fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(PetSpacing.medium),
                            ) {
                                val record = records.firstOrNull { it.id == careDetailId }
                                if (careDetailId != null && scopedFeed == null) {
                                    CircularProgressIndicator()
                                    Text("기록 확인 중")
                                } else if (careDetailId != null && record == null) {
                                    Text("기록을 찾을 수 없습니다.")
                                    PetSecondaryButton(label = "목록으로", onClick = { careDetailId = null })
                                } else if (record == null) {
                                    PetEmptyState(
                                        title = "기록을 선택해 주세요",
                                        detail = "왼쪽 목록에서 기록을 선택하면 상세 내용을 볼 수 있습니다.",
                                    )
                                } else {
                                    CareRecordDetail(
                                        record = record,
                                        photoStore = photoStore,
                                        onBack = { careDetailId = null },
                                        onEdit = { startCare(CareType.valueOf(record.type), record.id) },
                                        onDelete = onDeleteCare,
                                        onDeleted = { careDetailId = null },
                                    )
                                }
                            }
                        }
                    } else if (tab == PrimaryTab.RECORDS) {
                        key(current.id) {
                            CareRecordList(
                                petName = current.name,
                                petId = current.id,
                                observeHistory = observeCareHistory,
                                selectedType = selectedTypeFilter,
                                selectedRange = selectedRange,
                                onTypeChange = { selectedTypeFilter = it },
                                onRangeChange = { selectedRange = it },
                                onAdd = { choosingCareType = true },
                                onView = { careDetailId = it },
                                modifier = Modifier.fillMaxSize().padding(horizontal = PetSpacing.large, vertical = PetSpacing.small),
                                showTitle = false,
                            )
                        }
                    } else Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(PetSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(
                            if (tab == PrimaryTab.HOME || tab == PrimaryTab.MORE ||
                                tab == PrimaryTab.HEALTH || tab == PrimaryTab.INSIGHTS
                            ) PetSpacing.medium else PetSpacing.xLarge
                        ),
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
                                onSwitchPet = { showSwitcher = true },
                                onViewAll = { selectedTypeFilter = null; selectedRange = CareRange.ALL; tab = PrimaryTab.RECORDS },
                                expandedLayout = expandedUi,
                            )
                            PrimaryTab.RECORDS -> Unit
                            PrimaryTab.HEALTH -> PetHealthScreen(
                                petName = current.name,
                                records = records,
                                onAddWeight = { startCare(CareType.WEIGHT) },
                                onViewRecord = { careDetailId = it },
                                showTitle = expandedUi,
                            )
                            PrimaryTab.INSIGHTS -> CareStatisticsScreen(
                                petName = current.name,
                                records = records,
                                period = statisticsRange,
                                onPeriodChange = { statisticsRange = it },
                                expandedLayout = expandedUi,
                                showTitle = expandedUi,
                            )
                            PrimaryTab.MORE -> SettingsContent(
                                themeMode = themeMode,
                                guestScope = guestScope,
                                profileCount = petState.profiles.size,
                                onOpenAccount = onOpenAccount,
                                onThemeModeChange = onThemeModeChange,
                                onManage = { showManager = true },
                                onAdd = { openEditor(null, returnToManager = false) },
                                restoring = restoring,
                                onRestoreBackup = onRestoreBackup,
                            )
                        }
                    }
                }
            }
        }
    }

    if (choosingCareType && current != null && (scopedFeed != null || timelineVisible)) {
        ModalBottomSheet(onDismissRequest = { choosingCareType = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    start = PetSpacing.large,
                    end = PetSpacing.large,
                    bottom = PetSpacing.xLarge,
                ),
                verticalArrangement = Arrangement.spacedBy(PetSpacing.medium),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                ) {
                    Text(
                        "${current.name}의 기록 추가",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(onClick = { choosingCareType = false }) { Text("닫기") }
                }
                val careOptions = listOf(
                    CareType.FOOD to PetIcons.Food,
                    CareType.WATER to PetIcons.Water,
                    CareType.ELIMINATION to PetIcons.Elimination,
                    CareType.ACTIVITY to PetIcons.Activity,
                    CareType.WEIGHT to PetIcons.Weight,
                )
                careOptions.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                    ) {
                        pair.forEach { (type, glyph) ->
                            Surface(
                                modifier = Modifier.weight(1f)
                                    .heightIn(min = 72.dp)
                                    .clickable(role = Role.Button) { startCare(type) },
                                color = MaterialTheme.colorScheme.surface,
                                shape = RectangleShape,
                                border = BorderStroke(
                                    1.dp, MaterialTheme.colorScheme.outlineVariant
                                ),
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                            ) {
                                Row(
                                    modifier = Modifier.padding(PetSpacing.medium),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
                                ) {
                                    Icon(
                                        imageVector = glyph,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(type.label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    if (restoring) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                CircularProgressIndicator()
                Text("백업 복원 중", style = MaterialTheme.typography.titleLarge)
                Text("데이터를 교체하고 있습니다. 완료될 때까지 앱을 종료하지 마세요.")
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
                careDetailId = null
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
    guestScope: Boolean,
    profileCount: Int,
    onOpenAccount: (() -> Unit)?,
    onThemeModeChange: (ThemeMode) -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
    restoring: Boolean,
    onRestoreBackup: suspend (Uri, CharArray) -> Result<Unit>,
) {
    val context = LocalContext.current
    var showInfo by rememberSaveable { mutableStateOf(false) }
    Text("더보기", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    if (onOpenAccount != null) {
        PetMenuRow(
            icon = PetIcons.Sync,
            label = "계정 및 클라우드",
            detail = if (guestScope) "로그인하지 않음 · 기기 기록 사용" else "계정 관리 및 동기화 상태 확인",
            onClick = onOpenAccount,
            emphasized = true,
        )
    }
    Text("반려동물 관리", style = MaterialTheme.typography.titleLarge)
    PetMenuRow(
        icon = PetIcons.Profile, label = "프로필 관리", detail = "등록 ${profileCount}개", onClick = onManage,
    )
    PetMenuRow(icon = PetIcons.Add, label = "반려동물 추가", onClick = onAdd)
    Text("화면 설정", style = MaterialTheme.typography.titleLarge)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(PetSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PetSpacing.xSmall)) {
            Text("화면 모드", style = MaterialTheme.typography.labelLarge)
            val choices = listOf(ThemeMode.SYSTEM to "시스템", ThemeMode.LIGHT to "라이트", ThemeMode.DARK to "다크")
            val largeText = LocalDensity.current.fontScale >= 1.3f
            val choice: @Composable (ThemeMode, String, Modifier) -> Unit = { mode, label, modifier ->
                Row(
                    modifier = modifier.heightIn(min = PetSpacing.minTouchTarget)
                        .selectable(selected = themeMode == mode, role = Role.RadioButton,
                            onClick = { onThemeModeChange(mode) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.xSmall),
                ) {
                    RadioButton(selected = themeMode == mode, onClick = null, modifier = Modifier.size(24.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (largeText) {
                choices.forEach { (mode, label) -> choice(mode, label, Modifier.fillMaxWidth()) }
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    choices.forEach { (mode, label) -> choice(mode, label, Modifier.weight(1f)) }
                }
            }
        }
    }
    if (guestScope) {
        PetBackupSection(restoring = restoring, onRestore = onRestoreBackup)
    } else {
        PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
            Text("백업·복원", style = MaterialTheme.typography.titleMedium)
            Text("현재 Google 계정의 로컬 데이터는 별도로 보관됩니다. 계정별 암호화 백업·복원은 검증 완료 후 제공됩니다.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    PetMenuRow(icon = PetIcons.Lock, label = "개인정보처리방침", onClick = {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://hebi10.github.io/PetWorld/privacy-policy.html")))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "브라우저 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    })
    PetMenuRow(icon = PetIcons.Info, label = "앱 정보", onClick = { showInfo = true })
    if (showInfo) {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("앱 정보") },
            text = { Text(listOfNotNull("PetWorld", version?.let { "버전 $it" }).joinToString("\n")) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("닫기") } },
        )
    }
}
