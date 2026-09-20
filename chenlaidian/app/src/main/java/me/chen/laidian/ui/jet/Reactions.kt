package me.chen.laidian.ui.jet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chen.laidian.ui.LocalSkin

/**
 * 0920 她的第 3 条："轻点一下 像 TG 一样表态/点赞"。
 * 表态条（轻点气泡弹出 / 长按菜单顶上一排）+ 气泡下的小胶囊（emoji 人数 自己点过的高亮 点胶囊=切换）。
 * 协议：ws 发 {type:react,id,emoji} 同一人同一 emoji 再发=取消；服务端广播 {type:reaction,id,reactions} 全量替换。
 */
/** 0920 表情/加号面板开着时 点气泡那一下只收面板 不弹表态条（TG 同款） */
internal val LocalPanelOpen = androidx.compose.runtime.compositionLocalOf { false }

internal val REACTION_SET = listOf("❤", "👍", "😂", "😮", "😢", "🔥", "🥰", "💋")

/** 一排 8 个 emoji 点一个就回调；放在 DropdownMenu 里当第一行 */
@Composable
internal fun ReactionRow(onPick: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        REACTION_SET.forEach { e ->
            Text(e, fontSize = 22.sp, modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(e) })
        }
    }
}

/** 气泡下的表态胶囊 "❤ 2"：自己（xiaochen）点过的 = 强调色淡底 + 描边；点胶囊 = 切换 */
@Composable
internal fun ReactionChips(reactions: Map<String, List<String>>, onToggle: (String) -> Unit) {
    if (reactions.isEmpty()) return
    val skin = LocalSkin.current
    Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        reactions.forEach { (e, users) ->
            val mine = "xiaochen" in users
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (mine) skin.accent.copy(alpha = 0.18f) else skin.surface,
                border = if (mine) BorderStroke(1.dp, skin.accent) else null,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggle(e) },
            ) {
                Text("$e ${users.size}", fontSize = 12.sp, color = skin.ink, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}
