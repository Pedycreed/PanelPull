package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = CrimsonCoral,
    onPrimary = Color.White,
    primaryContainer = CrimsonCoralContainer,
    onPrimaryContainer = OnCrimsonCoralContainer,
    secondary = ElectricCyan,
    onSecondary = InkObsidian,
    secondaryContainer = ElectricCyanContainer,
    onSecondaryContainer = OnElectricCyanContainer,
    tertiary = VioletInk,
    onTertiary = InkObsidian,
    tertiaryContainer = VioletInkContainer,
    onTertiaryContainer = OnVioletInkContainer,
    background = InkObsidian,
    onBackground = InkTextLight,
    surface = InkSurface,
    onSurface = InkTextLight,
    surfaceVariant = InkSurfaceElevated,
    onSurfaceVariant = InkTextMutedLight,
    surfaceContainerLowest = InkObsidian,
    surfaceContainerLow = InkSurface,
    surfaceContainer = InkSurfaceElevated,
    surfaceContainerHigh = InkSurfaceHighest,
    outline = InkOutline,
    outlineVariant = Color(0xFF2B283D),
    error = RoseError,
    onError = InkObsidian
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFD92B4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCE2),
    onPrimaryContainer = Color(0xFF400011),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8F0FE),
    onSecondaryContainer = Color(0xFF00253A),
    tertiary = Color(0xFF6D28D9),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE4FF),
    onTertiaryContainer = Color(0xFF21005D),
    background = PaperLight,
    onBackground = InkTextDark,
    surface = PaperSurface,
    onSurface = InkTextDark,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = InkTextMutedDark,
    outline = Color(0xFFCAC4D4),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

private val PanelPullShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PanelPullTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PanelPullTypography,
        shapes = PanelPullShapes,
        content = content
    )
}
