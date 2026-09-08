package me.chen.laidian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 照网页版：暖白底、她蓝我灰。深色模式等她的美化稿再说。 */
object C {
    val Bg = Color(0xFFF7F6F2)
    val Surface = Color(0xFFFFFFFF)
    val Blue = Color(0xFF4F7CD0)
    val Orange = Color(0xFFE8A06A)
    val ChenBubble = Color(0xFFECEAE4)
    val Ink = Color(0xFF1E1E24)
    val Grey = Color(0xFF8A8A94)
    val Green = Color(0xFF34C759)
    val Dot = Color(0xFF2B2B30)
    val Line = Color(0xFFE6E4DE)
}

private val Light = lightColorScheme(
    primary = C.Blue,
    onPrimary = Color.White,
    background = C.Bg,
    onBackground = C.Ink,
    surface = C.Surface,
    onSurface = C.Ink,
    secondary = C.Orange,
)

@Composable
fun ChenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Light, content = content)
}
