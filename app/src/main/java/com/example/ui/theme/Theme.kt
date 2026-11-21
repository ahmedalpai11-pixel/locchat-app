package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF031F2C),
    primaryContainer = ElectricCyanContainerDark,
    onPrimaryContainer = Color(0xFFBDF2FF),
    secondary = MeshViolet,
    onSecondary = Color(0xFF1B093B),
    secondaryContainer = MeshVioletContainerDark,
    onSecondaryContainer = Color(0xFFE7DCFF),
    tertiary = PrivateShieldGold,
    onTertiary = Color(0xFF2B1800),
    background = SlateBgDark,
    onBackground = TextPrimaryDark,
    surface = SlateSurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateSurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = SlateOutlineDark,
    error = PanicCrimson,
    onError = Color.White,
    errorContainer = PanicCrimsonDark,
    onErrorContainer = Color(0xFFFFD9DF)
)

private val LightColorScheme = lightColorScheme(
    primary = ElectricCyanDark,
    onPrimary = Color.White,
    primaryContainer = ElectricCyanContainerLight,
    onPrimaryContainer = Color(0xFF002438),
    secondary = MeshVioletDark,
    onSecondary = Color.White,
    secondaryContainer = MeshVioletContainerLight,
    onSecondaryContainer = Color(0xFF220E56),
    tertiary = RadarAmberDark,
    onTertiary = Color.White,
    background = MistBgLight,
    onBackground = TextPrimaryLight,
    surface = MistSurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = MistSurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = MistOutlineLight,
    error = PanicCrimson,
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E9),
    onErrorContainer = Color(0xFF6B0B1B)
)

// Squircle-inspired rounded shapes mirroring the glossy app icon
val LocChatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun LocChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LocChatTypography,
        shapes = LocChatShapes,
        content = content
    )
}
