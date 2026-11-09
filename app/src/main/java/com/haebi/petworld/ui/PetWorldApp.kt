package com.haebi.petworld.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.haebi.petworld.R
import com.haebi.petworld.ui.components.PetEmptyState
import com.haebi.petworld.ui.components.PetIcon
import com.haebi.petworld.ui.components.PetStatus
import com.haebi.petworld.ui.components.PetStatusLabel
import com.haebi.petworld.ui.components.PetSurfaceCard
import com.haebi.petworld.ui.icons.PetIcons
import com.haebi.petworld.ui.theme.PetSpacing
import com.haebi.petworld.ui.theme.PetWorldTheme
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
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(PrimaryTab.HOME) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                    Text(
                        text = "PetWorld",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                PrimaryTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.imageVector, contentDescription = null) },
                        label = { Text(tab.label) },
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
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = PetSpacing.contentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(PetSpacing.large),
                verticalArrangement = Arrangement.spacedBy(PetSpacing.xLarge),
            ) {
                when (selectedTab) {
                    PrimaryTab.HOME -> HomeFoundationScreen()
                    PrimaryTab.RECORDS -> FeatureFoundationScreen(
                        title = "생활 기록",
                        description = "식사·음수·배변·활동·체중 기록은 MVP 개발 단계에서 연결합니다.",
                    )
                    PrimaryTab.HEALTH -> FeatureFoundationScreen(
                        title = "건강 관리",
                        description = "체중 추적과 이후 진료·복약 관리가 이 화면에 연결됩니다.",
                    )
                    PrimaryTab.INSIGHTS -> FeatureFoundationScreen(
                        title = "통계",
                        description = "실제 기록이 저장된 이후 기간별 추이와 그래프를 제공합니다.",
                    )
                    PrimaryTab.MORE -> SettingsFoundationScreen(
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeFoundationScreen() {
    Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
        Text("함께 기록하는 반려동물의 일상", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Pet Journal",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        PetStatusLabel("디자인 시스템 적용", status = PetStatus.NEUTRAL)
        Text("초기 화면", style = MaterialTheme.typography.titleLarge)
        Text(
            "이 화면은 1-A 기반 검증용입니다. 반려동물 등록과 실제 기록 저장은 후속 단계에서 구현합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.medium)) {
        Text("기록 영역", style = MaterialTheme.typography.titleMedium)
        listOf(
            "식사" to PetIcons.Food,
            "음수" to PetIcons.Water,
            "배변" to PetIcons.Elimination,
            "활동" to PetIcons.Activity,
            "체중" to PetIcons.Weight,
        ).forEach { (label, imageVector) ->
            PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PetSpacing.medium),
                    ) {
                        PetIcon(imageVector = imageVector, contentDescription = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                    PetStatusLabel("준비 중", status = PetStatus.NEUTRAL)
                }
            }
        }
    }
}

@Composable
private fun FeatureFoundationScreen(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge)
    PetEmptyState(
        title = "화면 준비 중",
        detail = description,
    )
}

@Composable
private fun SettingsFoundationScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    Text("설정", style = MaterialTheme.typography.headlineLarge)

    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text("화면 모드", style = MaterialTheme.typography.titleMedium)
        Text(
            "선택한 모드는 기기에 저장됩니다. 시스템은 기기 설정을 따릅니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        listOf(
            ThemeMode.SYSTEM to "시스템 설정",
            ThemeMode.LIGHT to "라이트",
            ThemeMode.DARK to "다크",
        ).forEach { (mode, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PetSpacing.minTouchTarget)
                    .selectable(
                        selected = themeMode == mode,
                        role = Role.RadioButton,
                        onClick = { onThemeModeChange(mode) },
                    )
                    .padding(vertical = PetSpacing.xSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
            ) {
                RadioButton(
                    selected = themeMode == mode,
                    onClick = null,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    Text(
        "PetWorld · 1-A 프로젝트 기반",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(showBackground = true, name = "PetWorld light")
@Composable
private fun PreviewLight() {
    PetWorldTheme(themeMode = ThemeMode.LIGHT) {
        PetWorldApp(ThemeMode.LIGHT, onThemeModeChange = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101010, name = "PetWorld dark")
@Composable
private fun PreviewDark() {
    PetWorldTheme(themeMode = ThemeMode.DARK) {
        PetWorldApp(ThemeMode.DARK, onThemeModeChange = {})
    }
}
