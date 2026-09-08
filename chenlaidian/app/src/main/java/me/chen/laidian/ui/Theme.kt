package me.chen.laidian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 照网页版：暖白底、她蓝我灰。深色模式等她的美化稿再说。 */
/** 数值全部来自网页版 index.html 的 :root（浅色）变量，一个不改。 */
object C {
    val Bg = Color(0xFFFAF9F7)          // --paper
    val Surface = Color(0xFFFFFFFF)     // --paper-msg
    val Blue = Color(0xFF5B7BB4)        // --moonblue / --bubble-self
    val BlueLight = Color(0xFF7A9AD4)   // --moonblue-light
    val Orange = Color(0xFFE8A87C)      // --accent-warm
    val ChenBubble = Color(0xFFF0ECE6)  // --bubble-chen
    val Ink = Color(0xFF2C2C2C)         // --ink
    val Grey = Color(0xFF9A9590)        // --muted
    val Green = Color(0xFF4CAF50)       // online / endpoint
    val Dot = Color(0xFF2C2C2C)         // --dot-color
    val Line = Color(0xFFE8E4DE)        // --border
    val QuoteSelf = Color(0x1F5B7BB4)   // rgba(91,123,180,.12)
    val QuoteChen = Color(0x0A000000)   // rgba(0,0,0,.04)
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
