package com.example.phonediary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7C6FE0),
    secondary = Color(0xFF5A5A5A),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    primaryContainer = Color(0xFF3A3266)
)

private val ColorfulColors = lightColorScheme(
    primary = Color(0xFFFF6F61),
    secondary = Color(0xFF6FCF97),
    tertiary = Color(0xFFFFD166),
    background = Color(0xFFFFF8F0),
    surface = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE0DB)
)

@Composable
fun PhoneDiaryTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val colors = when (theme) {
        AppTheme.DARK -> DarkColors
        AppTheme.COLORFUL -> ColorfulColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
