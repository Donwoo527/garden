package me.chen.laidian.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.BuildConfig
import me.chen.laidian.update.AppUpdater
import java.io.File

/**
 * 0926 设置页「检查更新」那一行。
 * 启动自查到的新版（AppUpdater.available）显示成「x 可更新」，点了直接弹对话框；
 * 没查到过就点一下现查：有新版弹对话框，没有 Toast「已是最新」。
 */
@Composable
fun UpdateRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val available by AppUpdater.available.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf<AppUpdater.Latest?>(null) }
    val skin = LocalSkin.current
    WhiteCard(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !checking) {
                val a = available
                if (a != null) { show = a; return@clickable }
                checking = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { AppUpdater.checkLatest(ctx) }
                    checking = false
                    when {
                        r == null -> Toast.makeText(ctx, "查不到：${AppUpdater.lastError ?: "未知原因"}", Toast.LENGTH_SHORT).show()
                        r.isNewer -> { AppUpdater.available.value = r; show = r }
                        else -> Toast.makeText(ctx, "已是最新", Toast.LENGTH_SHORT).show()
                    }
                }
            }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("检查更新", fontSize = 15.sp, color = skin.ink)
                Text("当前 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = skin.muted)
            }
            val a = available
            when {
                checking -> Text("查询中…", fontSize = 13.sp, color = skin.muted)
                a != null -> Text("${a.versionName} 可更新", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = skin.accent)
                else -> Text("点一下查", fontSize = 13.sp, color = skin.muted)
            }
        }
    }
    show?.let { UpdateDialog(it, onDismiss = { show = null }) }
}

/**
 * 更新对话框：版本号 + 更新说明 + 「更新」。点更新显示下载进度，下完自动拉系统安装器；
 * 没有"允许安装未知应用"权限时 AppUpdater 会跳去系统设置，这里留着「安装」按钮让她回来再点。
 */
@Composable
fun UpdateDialog(latest: AppUpdater.Latest, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val skin = LocalSkin.current
    var progress by remember { mutableIntStateOf(-1) }        // -1 没开始；0..100 下载中/完成
    var file by remember { mutableStateOf<File?>(null) }      // 下完的 apk
    var error by remember { mutableStateOf<String?>(null) }
    var needPerm by remember { mutableStateOf(false) }        // 拉安装器时发现没权限，已跳系统设置
    val downloading = progress in 0..99 && file == null
    fun startInstall(f: File) {
        val ok = try { AppUpdater.install(ctx, f) } catch (e: Exception) {
            error = "拉不起安装器：${e.message ?: e.javaClass.simpleName}"; return
        }
        needPerm = !ok
    }
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        containerColor = skin.bg,
        title = { Text("有新版本 ${latest.versionName}", color = skin.ink) },
        text = {
            Column {
                Text("当前 ${BuildConfig.VERSION_NAME} → ${latest.versionName}", fontSize = 12.sp, color = skin.muted)
                Spacer(Modifier.height(8.dp))
                Text(latest.notes.ifBlank { "（辰没写更新说明）" }, fontSize = 14.sp, color = skin.ink)
                if (progress >= 0) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(if (file != null) "下载完成" else "下载中 $progress%", fontSize = 12.sp, color = skin.muted)
                }
                if (needPerm) {
                    Spacer(Modifier.height(8.dp))
                    Text("系统还没允许辰来电装应用：在刚跳过去的页面打开开关，回来再点「安装」", fontSize = 12.sp, color = skin.accent)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = Color(0xFFE53935))
                }
            }
        },
        confirmButton = {
            val f = file
            when {
                f != null -> Button(onClick = { startInstall(f) }) { Text("安装") }
                downloading -> Button(onClick = {}, enabled = false) { Text("下载中") }
                else -> Button(onClick = {
                    error = null; needPerm = false; progress = 0
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { AppUpdater.download(ctx, latest.url) { p -> progress = p } }
                        if (r == null) { error = "下载失败：${AppUpdater.lastError ?: "未知原因"}"; progress = -1 }
                        else { progress = 100; file = r; startInstall(r) }
                    }
                }) { Text(if (error != null) "重试" else "更新") }
            }
        },
        dismissButton = { if (!downloading) TextButton(onClick = onDismiss) { Text("以后再说", color = skin.muted) } },
    )
}
