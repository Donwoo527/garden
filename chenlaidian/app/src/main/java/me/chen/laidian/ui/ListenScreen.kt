package me.chen.laidian.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.chen.laidian.music.MusicCommands
import me.chen.laidian.music.MusicListenerService
import me.chen.laidian.music.MusicReporter
import me.chen.laidian.music.NowPlaying
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0929 一起听（百宝箱→一起听）。她照常用 QQ 音乐 / 网易云听，app 在旁边读系统「正在播放」报给辰。
 * 三块：通知使用权 → 开关「让辰听见我在听什么」→ 正在播放卡片（歌名/歌手/专辑/进度/状态/来自哪个 app + 上次上报 + 现在发一次）。
 * 样式照 ToyScreen（LocalSkin 配色、WhiteCard）。底栏照 SubPage.open 藏掉（子页里选不了 tab）。
 */
@Composable
fun ListenScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val skin = LocalSkin.current
    LaunchedEffect(Unit) { SubPage.open = true }
    DisposableEffect(Unit) { onDispose { SubPage.open = false } }

    // 权限每 1.5 秒看一眼：她去系统设置给完权限回来，页面自己刷新，不用重进
    var perm by remember { mutableStateOf(MusicListenerService.hasPermission(ctx)) }
    LaunchedEffect(Unit) {
        MusicReporter.init(ctx)
        while (true) {
            perm = MusicListenerService.hasPermission(ctx)
            delay(1500)
        }
    }
    val connected by MusicListenerService.connected.collectAsState()
    val enabled by MusicReporter.enabled.collectAsState()
    val snap by NowPlaying.snapshot.collectAsState()
    val sessionStatus by NowPlaying.status.collectAsState()
    val extras by NowPlaying.lastExtras.collectAsState()
    val lastSentAt by MusicReporter.lastSentAt.collectAsState()
    val lastReason by MusicReporter.lastReason.collectAsState()
    val lastError by MusicReporter.lastError.collectAsState()
    val lastCmd by MusicCommands.lastCmd.collectAsState()
    var showExtras by remember { mutableStateOf(false) }

    // 进度条每秒走一下（播放中按 position + 经过时间 外推；暂停就停在那）
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snap.state, snap.at) {
        now = System.currentTimeMillis()
        while (snap.state == "playing") { delay(1000); now = System.currentTimeMillis() }
    }
    val pos = snap.positionAt(now)
    val frac = if (snap.durationMs > 0) (pos.toFloat() / snap.durationMs).coerceIn(0f, 1f) else 0f

    Column(Modifier.fillMaxSize().background(skin.bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 百宝箱", color = skin.muted) }
        }
        Text("一起听", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = skin.ink)
        Spacer(Modifier.height(6.dp))
        Text("你照常用 QQ 音乐 / 网易云听。这页在旁边读系统的「正在播放」，辰就知道你在听什么、听到哪句，还能给你切歌、暂停、点歌。", fontSize = 13.sp, color = skin.muted)
        Spacer(Modifier.height(14.dp))

        // ---------- 一、通知使用权 ----------
        WhiteCard {
            Column(Modifier.padding(16.dp)) {
                Text("通知使用权：${if (perm) "已给" else "没给"}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (perm) skin.ink else skin.accent)
                Spacer(Modifier.height(6.dp))
                if (!perm) {
                    Text("系统只把「正在播放」给通知监听服务读，所以要这一个权限。它不看你的通知内容，只要这个资格。", fontSize = 13.sp, color = skin.ink)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { MusicListenerService.openSettings(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("去系统设置里打开") }
                    Spacer(Modifier.height(6.dp))
                    Text("列表里找「辰来电」（辰的一起听）打开。给了权限回到这页会自己刷新。ColorOS 可能还要在「自启动 / 耗电管理」里把辰来电放行一下，不然后台会被掐。", fontSize = 12.sp, color = skin.muted)
                } else {
                    Text("服务：${if (connected) "已连上系统" else "还没连上系统"}", fontSize = 13.sp, color = skin.ink)
                    Text(sessionStatus, fontSize = 12.sp, color = skin.muted)
                    if (!connected) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            MusicListenerService.rebind(ctx)
                            Toast.makeText(ctx, "已请系统重新绑定 几秒后还不行就把权限关了再开一次", Toast.LENGTH_LONG).show()
                        }, modifier = Modifier.fillMaxWidth()) { Text("重新连接") }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------- 二、开关 ----------
        WhiteCard {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("让辰听见我在听什么", fontSize = 15.sp, color = skin.ink)
                    Text("关了就不上报，辰也不能给你切歌暂停；点歌的通知还会来。关的那一刻会告诉辰一声「听不见了」。", fontSize = 12.sp, color = skin.muted)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = { MusicReporter.setEnabled(ctx, it) })
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------- 三、正在播放 ----------
        WhiteCard {
            Column(Modifier.padding(16.dp)) {
                val stateLabel = when (snap.state) {
                    "playing" -> "▶ 播放中"
                    "paused" -> "❚❚ 暂停"
                    "stopped" -> "■ 停了"
                    else -> "没有在放"
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (snap.app.isBlank()) "正在播放" else "来自 ${snap.app}", fontSize = 12.sp, color = skin.muted)
                    Text(stateLabel, fontSize = 12.sp, color = if (snap.state == "playing") skin.accent else skin.muted)
                }
                Spacer(Modifier.height(8.dp))
                if (snap.state == "none" || snap.title.isBlank()) {
                    Text(if (!perm) "给了通知使用权才能看到" else "打开 QQ 音乐或网易云放一首，这里就会出现", fontSize = 14.sp, color = skin.muted)
                } else {
                    Text(snap.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = skin.ink)
                    Text(listOf(snap.artist, snap.album).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { " " }, fontSize = 13.sp, color = skin.muted)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(), color = skin.accent, trackColor = skin.line)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(mmss(pos), fontSize = 11.sp, color = skin.muted)
                        Text(if (snap.durationMs > 0) mmss(snap.durationMs) else "--:--", fontSize = 11.sp, color = skin.muted)
                    }
                    if (snap.mediaId.isNotBlank()) Text("media_id：${snap.mediaId}", fontSize = 11.sp, color = skin.muted)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        lastError.isNotBlank() -> "上报失败：$lastError（会自己重试）"
                        lastSentAt > 0L -> "上次告诉辰：${hhmm(lastSentAt)}" + (if (lastReason.isNotBlank()) "（${reasonLabel(lastReason)}）" else "")
                        else -> "还没告诉过辰"
                    },
                    fontSize = 12.sp, color = if (lastError.isNotBlank()) skin.accent else skin.muted,
                )
                if (lastCmd.isNotBlank()) Text("辰最近的指令：$lastCmd", fontSize = 12.sp, color = skin.muted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val sent = MusicReporter.sendNow()
                        Toast.makeText(ctx, if (sent) "发了 等一下看上面那行" else "开关关着 没发", Toast.LENGTH_SHORT).show()
                    }) { Text("现在发一次") }
                    OutlinedButton(onClick = { NowPlaying.refresh() }) { Text("刷新会话") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------- 诊断：这首歌 metadata 里到底有哪些键（看 QQ 音乐给不给 songId） ----------
        if (extras.isNotEmpty()) {
            Text(if (showExtras) "▾ 诊断：这首歌的 metadata 键值（${extras.size} 个）" else "▸ 诊断：这首歌的 metadata 键值（${extras.size} 个）",
                fontSize = 13.sp, color = skin.muted, modifier = Modifier.clickable { showExtras = !showExtras })
            if (showExtras) {
                WhiteCard(Modifier.padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        extras.forEach { (k, v) -> Text("${k.removePrefix("android.media.metadata.")}：$v", fontSize = 11.sp, color = skin.ink) }
                        Spacer(Modifier.height(6.dp))
                        Text("第一次遇到一首歌时这份会随上报一起发给辰，之后同一首不再带。", fontSize = 11.sp, color = skin.muted)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Text("规矩：歌变了、播放暂停变了、拖了进度才发一条，播放中每 5 分钟报个平安；不发歌词不发音频，歌词辰自己按歌名去找。梯子不开发不出去，会攒着最新一条，连上补发。", fontSize = 12.sp, color = skin.muted)
        Spacer(Modifier.height(24.dp))
    }
}

private fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(Locale.US, s / 60, s % 60)
}

private fun hhmm(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMs))

private fun reasonLabel(r: String): String = when (r) {
    "song" -> "换歌"
    "state" -> "播放/暂停"
    "seek" -> "拖进度"
    "heartbeat" -> "心跳"
    "manual" -> "手动"
    else -> r
}
