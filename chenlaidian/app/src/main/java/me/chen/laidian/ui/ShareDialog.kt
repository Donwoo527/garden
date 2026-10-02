package me.chen.laidian.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import me.chen.laidian.ShareInbox
import java.io.File

/** 0.116 别的 app「分享 → 发给辰」进来的确认框：缩略图 + 可选附言。样式照共享相册「发 N 张照片」那个框 */
@Composable
fun ShareConfirmDialog(files: List<File>, onSent: () -> Unit) {
    val ctx = LocalContext.current
    val skin = LocalSkin.current
    val busy by ShareInbox.sending.collectAsState()   // 上一组还在传：先别让点，免得两组搅在一起
    var caption by remember(files) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { ShareInbox.cancel() },
        properties = DialogProperties(dismissOnClickOutside = false),   // 打附言时点歪到框外不该把图丢了；返回键照样是取消
        containerColor = skin.bg,
        title = { Text("发给辰", color = skin.ink) },
        text = {
            Column {
                val thumb = Modifier.clip(RoundedCornerShape(8.dp)).background(skin.line)
                if (files.size == 1) AsyncImage(
                    model = files[0], contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(160.dp).then(thumb),
                )
                // 多张横排一行可左右划：竖着排 9 张会把附言框顶到键盘底下
                else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    files.forEach { f -> AsyncImage(model = f, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).then(thumb)) }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = caption, onValueChange = { caption = it }, placeholder = { Text("说点什么（可以不写）") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = { if (ShareInbox.send(ctx, caption.trim())) onSent() }) { Text(if (busy) "发送中…" else "发送") }
        },
        dismissButton = { TextButton(onClick = { ShareInbox.cancel() }) { Text("取消", color = skin.muted) } },
    )
}
