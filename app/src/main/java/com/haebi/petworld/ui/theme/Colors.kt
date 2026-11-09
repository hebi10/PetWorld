package com.haebi.petworld.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal object PetColors {
    val black = Color(0xFF191919)
    val graphite = Color(0xFF333333)
    val gray = Color(0xFF666666)
    val lightGray = Color(0xFFF2F2F2)
    val white = Color(0xFFFFFFFF)
    val darkBackground = Color(0xFF101010)
    val darkSurface = Color(0xFF181818)
    val darkElevated = Color(0xFF252525)
    val darkOutline = Color(0xFF525252)
    val darkText = Color(0xFFF2F2F2)
}

internal val LightPetColorScheme = lightColorScheme(
    primary = PetColors.black,
    onPrimary = PetColors.white,
    primaryContainer = Color(0xFFEAEAEA),
    onPrimaryContainer = PetColors.black,
    secondary = PetColors.graphite,
    onSecondary = PetColors.white,
    secondaryContainer = Color(0xFFE9E9E9),
    onSecondaryContainer = PetColors.black,
    tertiary = PetColors.gray,
    onTertiary = PetColors.white,
    tertiaryContainer = Color(0xFFECECEC),
    onTertiaryContainer = PetColors.black,
    background = PetColors.white,
    onBackground = PetColors.black,
    surface = PetColors.white,
    onSurface = PetColors.black,
    surfaceVariant = PetColors.lightGray,
    onSurfaceVariant = Color(0xFF565656),
    surfaceDim = Color(0xFFE7E7E7),
    surfaceBright = PetColors.white,
    surfaceContainerLowest = PetColors.white,
    surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainer = Color(0xFFF3F3F3),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE4E4E4),
    outline = Color(0xFF767676),
    outlineVariant = Color(0xFFCECECE),
    inverseSurface = PetColors.black,
    inverseOnSurface = PetColors.white,
    inversePrimary = PetColors.white,
    surfaceTint = PetColors.black,
    error = Color(0xFFB3261E),
    onError = PetColors.white,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF6F1711),
    scrim = PetColors.black,
)

internal val DarkPetColorScheme = darkColorScheme(
    primary = PetColors.darkText,
    onPrimary = PetColors.darkBackground,
    primaryContainer = Color(0xFF383838),
    onPrimaryContainer = PetColors.darkText,
    secondary = Color(0xFFD2D2D2),
    onSecondary = PetColors.darkBackground,
    secondaryContainer = Color(0xFF353535),
    onSecondaryContainer = PetColors.darkText,
    tertiary = Color(0xFFBFBFBF),
    onTertiary = PetColors.darkBackground,
    tertiaryContainer = Color(0xFF363636),
    onTertiaryContainer = PetColors.darkText,
    background = PetColors.darkBackground,
    onBackground = PetColors.darkText,
    surface = PetColors.darkSurface,
    onSurface = PetColors.darkText,
    surfaceVariant = PetColors.darkElevated,
    onSurfaceVariant = Color(0xFFC4C4C4),
    surfaceDim = PetColors.darkBackground,
    surfaceBright = Color(0xFF363636),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF161616),
    surfaceContainer = Color(0xFF202020),
    surfaceContainerHigh = Color(0xFF292929),
    surfaceContainerHighest = Color(0xFF353535),
    outline = Color(0xFFA3A3A3),
    outlineVariant = PetColors.darkOutline,
    inverseSurface = PetColors.darkText,
    inverseOnSurface = PetColors.darkBackground,
    inversePrimary = PetColors.black,
    surfaceTint = PetColors.darkText,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF660000),
    errorContainer = Color(0xFF6B1B1B),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)
