package me.chen.laidian.ui

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 新拟物 Neumorphism（0910 她定的方向，参数按她给的参考图）：
 * 亮影 #ffffff 偏移(-x,-y)，暗影 #A6ABBD 偏移(+x,+y)，双向光源软浮雕。
 * 规范（她定的）：容器/按钮=凸，输入区=凹；按下去凸变凹，松手弹回。
 */
object Neu {
    val Bg = Color(0xFFE7EAF0)        // 同色底（新拟物的地基，控件和背景一体）
    val Light = Color(0xFFFFFFFF)
    val Dark = Color(0xFFA6ABBD)
    val Ink = Color(0xFF4A4F5C)       // 这套底色上的文字/图标色
}

/** 凸起：控件后方画两个反向偏移的模糊色块（左上亮、右下暗） */
fun Modifier.neuRaised(corner: Dp = 22.dp, offset: Dp = 5.dp, blur: Dp = 12.dp): Modifier = drawBehind {
    val off = offset.toPx(); val b = blur.toPx()
    val r = corner.toPx().coerceAtMost(size.minDimension / 2)
    drawIntoCanvas { c ->
        val p = android.graphics.Paint().apply { isAntiAlias = true; maskFilter = BlurMaskFilter(b, BlurMaskFilter.Blur.NORMAL) }
        p.color = Neu.Light.toArgb()
        c.nativeCanvas.drawRoundRect(-off, -off, size.width - off, size.height - off, r, r, p)
        p.color = Neu.Dark.toArgb()
        c.nativeCanvas.drawRoundRect(off, off, size.width + off, size.height + off, r, r, p)
        p.maskFilter = null
        p.color = Neu.Bg.toArgb()
        c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, p)
    }
}

/** 凹陷：clip 后在内侧画两道错位模糊描边（左上暗、右下亮）——内阴影 */
fun Modifier.neuSunken(corner: Dp = 22.dp, offset: Dp = 3.dp, blur: Dp = 8.dp): Modifier = this
    .clip(RoundedCornerShape(corner))
    .drawBehind {
        val off = offset.toPx(); val b = blur.toPx()
        val r = corner.toPx().coerceAtMost(size.minDimension / 2)
        drawIntoCanvas { c ->
            val fill = android.graphics.Paint().apply { isAntiAlias = true; color = Neu.Bg.toArgb() }
            c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, fill)
            val p = android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
                strokeWidth = b; maskFilter = BlurMaskFilter(b, BlurMaskFilter.Blur.NORMAL)
            }
            // 暗描边整体往左上收：左上内壁显影，右下越界被clip吃掉
            p.color = Neu.Dark.toArgb()
            c.nativeCanvas.drawRoundRect(-b / 2 + off, -b / 2 + off, size.width + b / 2 + off, size.height + b / 2 + off, r, r, p)
            // 亮描边整体往右下收
            p.color = Neu.Light.toArgb()
            c.nativeCanvas.drawRoundRect(-b / 2 - off, -b / 2 - off, size.width + b / 2 - off, size.height + b / 2 - off, r, r, p)
        }
    }

/** 可按压：平时凸、按住凹（她定的交互——状态不靠变色靠凹凸） */
@Composable
fun Modifier.neuPressable(corner: Dp = 22.dp, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shapeMod = if (pressed) Modifier.neuSunken(corner) else Modifier.neuRaised(corner)
    return this
        .then(shapeMod)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}
