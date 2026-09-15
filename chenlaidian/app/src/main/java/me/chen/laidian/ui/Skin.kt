package me.chen.laidian.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 皮肤（0915 她定的"换汤不换药"）：骨架——每页有什么、放哪——不动；颜色/阴影/圆角/材质全装在这一个对象里，
 * 页面只问皮肤一件事：这块面是凸、凹还是平。想换玻璃质感 = 再写一个 Skin，位置一个不动。
 */
abstract class Skin {
    abstract val key: String
    abstract val label: String
    abstract val bg: Color        // 页面底
    abstract val surface: Color   // 面（卡片/气泡）
    abstract val ink: Color       // 正文
    abstract val muted: Color     // 次要字/图标
    abstract val accent: Color    // 唯一强调色（她参考图那种：全靠一个色说话）
    abstract val line: Color
    open val corner: Dp = 18.dp
    /** 聊天气泡：她 0915 的图——辰浅蓝在左、她浅橘在右，字都是 ink */
    open val bubbleChen: Color = Color(0xFFD5E3EA)   // 0915 调暖：蓝里掺一点灰
    open val bubbleMe: Color = Color(0xFFF4E3D1)
    /** 凸起的面：卡片、按钮 */
    abstract fun raised(m: Modifier, corner: Dp): Modifier
    /** 凹陷的面：输入框、槽 */
    abstract fun sunken(m: Modifier, corner: Dp): Modifier
    /** 平面：列表行、分隔 */
    open fun flat(m: Modifier, corner: Dp): Modifier = m.clip(RoundedCornerShape(corner)).background(surface)
    /** 可按：新拟物是平时凸、按住凹（她 0910 定的：状态不靠变色靠凹凸）；别的皮肤自己定按下去的样子 */
    @Composable abstract fun pressable(m: Modifier, corner: Dp, onClick: () -> Unit): Modifier
}

/** 新拟物：现役，主页和底栏在用的那套 */
object NeuSkin : Skin() {
    override val key = "neu"; override val label = "新拟态"
    override val bg = Neu.Bg; override val surface = Neu.Bg
    override val ink = Neu.Ink; override val muted = Neu.Dark
    override val accent = C.Blue      // 强调色她还没定，先沿用网页版的月蓝
    override val line = Neu.Dark.copy(alpha = 0.3f)
    override fun raised(m: Modifier, corner: Dp) = m.neuRaised(corner)
    override fun sunken(m: Modifier, corner: Dp) = m.neuSunken(corner)
    override fun flat(m: Modifier, corner: Dp) = m    // 新拟物里"平"就是底色本身
    @Composable override fun pressable(m: Modifier, corner: Dp, onClick: () -> Unit) = m.neuPressable(corner, onClick)
}

/** 纸面：0.27 之前全 app 的样子——暖白底、白卡、细线（网页版同款） */
object PaperSkin : Skin() {
    override val key = "paper"; override val label = "纸面"
    override val bg = C.Bg; override val surface = C.Surface
    override val ink = C.Ink; override val muted = C.Grey
    override val accent = C.Blue; override val line = C.Line
    override fun raised(m: Modifier, corner: Dp) =
        m.clip(RoundedCornerShape(corner)).background(surface).border(1.dp, line, RoundedCornerShape(corner))
    override fun sunken(m: Modifier, corner: Dp) = m.clip(RoundedCornerShape(corner)).background(C.QuoteChen)
    @Composable override fun pressable(m: Modifier, corner: Dp, onClick: () -> Unit) = raised(m, corner).clickable(onClick = onClick)
}

/** 玻璃：试作——半透白面 + 细白边 + 软投影。真正的背景模糊（RenderEffect）等她定了这个方向再上 */
object GlassSkin : Skin() {
    override val key = "glass"; override val label = "玻璃"
    override val bg = Color(0xFFDDE3EE)
    override val surface = Color(0x99FFFFFF)
    override val ink = Color(0xFF2B3140); override val muted = Color(0xFF7B8394)
    override val accent = Color(0xFF3D7BFF); override val line = Color(0xCCFFFFFF)
    override fun raised(m: Modifier, corner: Dp) = m
        .shadow(8.dp, RoundedCornerShape(corner), clip = false, ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
        .clip(RoundedCornerShape(corner)).background(surface).border(1.dp, line, RoundedCornerShape(corner))
    override fun sunken(m: Modifier, corner: Dp) =
        m.clip(RoundedCornerShape(corner)).background(Color(0x33FFFFFF)).border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(corner))
    @Composable override fun pressable(m: Modifier, corner: Dp, onClick: () -> Unit) = raised(m, corner).clickable(onClick = onClick)
}

val SKINS: List<Skin> = listOf(NeuSkin, PaperSkin, GlassSkin)

/** 当前皮肤：存 SharedPreferences，改了整个 app 立刻跟着换 */
object SkinState {
    private const val PREF = "skin"
    var current by mutableStateOf<Skin>(NeuSkin)
        private set
    fun load(ctx: Context) {
        val k = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("key", null)
        current = SKINS.firstOrNull { it.key == k } ?: NeuSkin
    }
    fun set(ctx: Context, s: Skin) {
        current = s
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("key", s.key).apply()
    }
}

val LocalSkin = staticCompositionLocalOf<Skin> { NeuSkin }

// 页面代码只用这四个：Modifier.raised() / .sunken() / .flat() / .pressable { }
@Composable fun Modifier.raised(corner: Dp = LocalSkin.current.corner): Modifier = LocalSkin.current.raised(this, corner)
@Composable fun Modifier.sunken(corner: Dp = LocalSkin.current.corner): Modifier = LocalSkin.current.sunken(this, corner)
@Composable fun Modifier.flat(corner: Dp = LocalSkin.current.corner): Modifier = LocalSkin.current.flat(this, corner)
@Composable fun Modifier.pressable(corner: Dp = LocalSkin.current.corner, onClick: () -> Unit): Modifier = LocalSkin.current.pressable(this, corner, onClick)
