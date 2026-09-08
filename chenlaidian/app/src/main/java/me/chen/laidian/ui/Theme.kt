package me.chen.laidian.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 先占个位：配色等她的需求来了再定。 */
private val Dark = darkColorScheme(
    primary = Color(0xFFF5E9C8),
    background = Color(0xFF1E1E24),
    surface = Color(0xFF26262E),
)
private val Light = lightColorScheme(
    primary = Color(0xFF1E1E24),
    background = Color(0xFFF7F3EA),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun ChenTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
