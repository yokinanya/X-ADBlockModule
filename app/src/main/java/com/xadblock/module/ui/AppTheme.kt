package com.xadblock.module.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import android.os.Build

private val LightColors = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF95F0FF),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = Color(0xFF8B4A3C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBD1),
    onSecondaryContainer = Color(0xFF370E06),
    tertiary = Color(0xFF685F2A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0E6A8),
    onTertiaryContainer = Color(0xFF211E00),
    background = Color(0xFFFAFCFD),
    surface = Color(0xFFFAFCFD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4CD9E8),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF95F0FF),
    secondary = Color(0xFFFFB4A3),
    onSecondary = Color(0xFF541F15),
    secondaryContainer = Color(0xFF703528),
    onSecondaryContainer = Color(0xFFFFDBD1),
    tertiary = Color(0xFFD4CA7A),
    onTertiary = Color(0xFF363100),
    tertiaryContainer = Color(0xFF4E4813),
    onTertiaryContainer = Color(0xFFF0E6A8),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun XADBlockTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
