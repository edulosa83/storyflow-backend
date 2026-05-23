package com.lalo.storyflow.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Color(0xFF006E62),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF9C413D),
    background = Color(0xFFF7F7F5),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF121314)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7AD6C8),
    onPrimary = Color(0xFF003731),
    secondary = Color(0xFFFFB4AB),
    background = Color(0xFF111414),
    surface = Color(0xFF1A1D1D),
    onSurface = Color(0xFFE3E3E1)
)

@Composable
fun StoryFlowTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
