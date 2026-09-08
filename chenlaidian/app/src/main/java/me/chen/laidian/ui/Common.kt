package me.chen.laidian.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 辰的头像：一大一小两个点（网页版 header-dots），小点右下角挂在线灯。会轻轻漂。 */
@Composable
fun DotsAvatar(big: Dp = 14.dp, small: Dp = 9.dp, online: Boolean? = null, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dots")
    val dy by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3000), RepeatMode.Reverse), label = "dy")
    val dy2 by t.animateFloat(1f, 0f, infiniteRepeatable(tween(3500), RepeatMode.Reverse), label = "dy2")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.offset(y = (dy * 2 - 1).dp).size(big).clip(CircleShape).background(C.Dot))
        Spacer(Modifier.width(big / 2))
        Box(Modifier.offset(y = (dy2 * 2 - 1).dp)) {
            Box(Modifier.size(small).clip(CircleShape).background(C.Dot))
            if (online != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = small / 4, y = small / 4)
                        .size(small / 2 + 2.dp).clip(CircleShape).background(if (online) C.Green else C.Grey)
                )
            }
        }
    }
}

/** 主页/百宝箱那种：白卡 + 彩色圆角图标 + 标题 */
@Composable
fun IconCard(title: String, icon: ImageVector, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp), color = C.Surface, shadowElevation = 1.dp,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(tint), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = title, tint = Color.White)
            }
            Spacer(Modifier.height(10.dp))
            Text(title, fontSize = 14.sp, color = C.Ink)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, color = C.Grey, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
fun WhiteCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = C.Surface, shadowElevation = 1.dp, modifier = modifier.fillMaxWidth()) { content() }
}
