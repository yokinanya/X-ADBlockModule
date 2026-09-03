package com.xadblock.module.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D9BF0),
    secondary = Color(0xFF03A9F4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF85C8F7),
    secondary = Color(0xFF72CEF5),
)

@Composable
fun XADBlockTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
