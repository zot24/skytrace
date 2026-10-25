package com.zot24.skytrace

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme = darkColorScheme(
    background = Color(0xFF07090C),
    surface = Color(0xFF080B10),
    primary = Color(0xFFE6B15A),
    onBackground = Color(0xFFE8EEF6),
    onSurface = Color(0xFFE8EEF6),
)

@Composable
fun SkytraceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
