package com.haebi.petworld.ui

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
import com.haebi.petworld.R
import com.haebi.petworld.data.PetDraft
import com.haebi.petworld.data.PetLoadStatus
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.PetProfile
import com.haebi.petworld.ui.components.PetAvatar
import com.haebi.petworld.ui.components.PetEmptyState
import com.haebi.petworld.ui.components.PetPrimaryButton
import com.haebi.petworld.ui.components.PetSurfaceCard
import com.haebi.petworld.ui.icons.PetIcons
import com.haebi.petworld.ui.screens.PetHomeScreen
import com.haebi.petworld.ui.screens.PetManagerScreen
import com.haebi.petworld.ui.screens.PetProfileEditor
import com.haebi.petworld.ui.screens.PetSwitcherSheet
import com.haebi.petworld.ui.screens.PetWelcomeScreen
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

@Composable
fun PetWorldApp(
    themeMode: ThemeMode,
    petState: PetUiState,
    photoStore: PetPhotoStore,
    onThemeModeChange: (ThemeMode) -> Unit,
    onSelectPet: (String) -> Unit,
    onSavePet: suspend (PetDraft) -> Result<PetProfile>,
    onArchivedChange: suspend (String, Boolean) -> Result<Unit>,
    onDeletePet: suspend (String) -> Result<Unit>,
    onRetry: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(PrimaryTab.HOME) }
    var showSwitcher by rememberSaveable { mutableStateOf(false) }
    var showManager by rememberSaveable { mutableStateOf(false) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingPetId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorFromManager by rememberSaveable { mutableStateOf(false) }
    val ready = petState.loadStatus == PetLoadStatus.Ready
    val current = petState.activePet

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

    val showTabs = ready && current != null && !showEditor && !showManager
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
                        onBack = { showManager = false },
                    )
                    current == null -> PetWelcomeScreen(
                        archivedCount = petState.profiles.count { it.isArchived },
                        onStart = { openEditor(null, returnToManager = false) },
                        onManageArchived = { showManager = true },
                    )
                    else -> Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(PetSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(PetSpacing.xLarge),
                    ) {
                        when (tab) {
                            PrimaryTab.HOME -> PetHomeScreen(
                                selected = current,
                                photoStore = photoStore,
                                onEdit = { openEditor(current.id, returnToManager = false) },
                            )
                            PrimaryTab.RECORDS -> FeaturePlaceholder(
                                title = "${current.name}의 생활 기록",
                                description = "식사·음수·배변·활동·체중의 기록 입력은 1-D 단계에서 연결됩니다.",
                            )
                            PrimaryTab.HEALTH -> FeaturePlaceholder(
                                title = "${current.name}의 건강",
                                description = "체중 추적과 건강·진료·복약 기록은 다음 개발 단계에서 제공됩니다.",
                            )
                            PrimaryTab.INSIGHTS -> FeaturePlaceholder(
                                title = "${current.name}의 통계",
                                description = "기록이 저장되면 기간별 변화와 그래프를 표시합니다.",
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
    Text(
        "로컬 프로필 데이터는 기기에만 저장됩니다. 백업·복원은 다음 단계에서 추가됩니다.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
