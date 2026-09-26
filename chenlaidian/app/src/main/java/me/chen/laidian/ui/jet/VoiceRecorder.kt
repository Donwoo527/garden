package me.chen.laidian.ui.jet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.R
import me.chen.laidian.net.ChatApi
import me.chen.laidian.net.VoicePlayer
import me.chen.laidian.ui.LocalSkin
import java.io.File

/**
 * 0926 她发语音消息：按住说话 → 松开发送 → 上滑（超过 80dp）取消；到 60 秒自动发这段。
 * 录成 m4a（AAC 16k 单声道 32kbps）放 cacheDir/voice_out/，传给 /upload_voice —— 服务端一步入库+广播（她的气泡靠 ws 回显出现），
 * 再后台转文字塞回那条的 text，语音气泡的「查看文字版」和辰的语音是同一套渲染。
 * 状态提示（录音中 0:05 / 松开取消 / 发送中…）用 Popup 飘在麦克风钮上方，不动输入栏的布局。
 */
private const val MAX_MS = 60_000L   // 微信同款上限：到顶自动发
private const val MIN_MS = 600L      // 比这短当误触

@Composable
fun VoiceMicButton(modifier: Modifier = Modifier, tint: Color, onRecordStart: () -> Unit = {}, onSent: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val skin = LocalSkin.current
    val recorder = remember { VoiceRecorder(ctx) }
    val sentCb by rememberUpdatedState(onSent)
    val startCb by rememberUpdatedState(onRecordStart)
    var recording by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }   // 手指上滑到取消区
    var sending by remember { mutableStateOf(false) }
    var startAt by remember { mutableStateOf(0L) }
    var elapsedMs by remember { mutableStateOf(0L) }
    // 没麦克风权限（MainActivity 启动时要过一次 她可能拒了）：按下先要，给了再按一次
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(ctx, if (granted) "好了 再按住说话" else "没给麦克风权限 发不了语音", Toast.LENGTH_SHORT).show()
    }

    // 收尾（松手 / 到顶 两处都走这里 幂等）：cancel=丢掉；否则停录→太短提示→上传
    fun finish(cancel: Boolean) {
        if (!recording) return
        recording = false
        cancelling = false
        if (cancel) { recorder.cancel(); return }
        val rec = recorder.stop()
        if (rec == null || rec.second < MIN_MS) {
            rec?.first?.delete()
            Toast.makeText(ctx, "太短了 按住多说一会", Toast.LENGTH_SHORT).show()
            return
        }
        val (file, ms) = rec
        sending = true
        scope.launch {
            ChatApi.lastError = null
            val url = withContext(Dispatchers.IO) { ChatApi.uploadVoice(ctx, file, ms / 1000.0) }
            sending = false
            file.delete()
            if (url == null) Toast.makeText(ctx, "语音没发出去：" + (ChatApi.lastError ?: ""), Toast.LENGTH_LONG).show()
            else sentCb()
        }
    }
    // 计时；到顶自动发
    LaunchedEffect(recording) {
        while (recording) {
            elapsedMs = System.currentTimeMillis() - startAt
            if (elapsedMs >= MAX_MS) {
                finish(cancel = false)
                Toast.makeText(ctx, "到 60 秒了 先发这段", Toast.LENGTH_SHORT).show()
                break
            }
            delay(200)
        }
    }
    // 这个钮离开组合（比如录着录着输入框来了字 换成发送钮）：录音器别悬着
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    val gapPx = with(LocalDensity.current) { 10.dp.roundToPx() }
    val provider = remember(gapPx) { AboveEndProvider(gapPx) }
    Box(
        modifier.pointerInput(Unit) {
            val cancelPx = 80.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                if (sending) return@awaitEachGesture   // 上一条还在传 别叠
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@awaitEachGesture
                }
                VoicePlayer.stop()   // 正在放的语音停掉 别录进去
                val err = recorder.start()
                if (err != null) {
                    Toast.makeText(ctx, "录不了：$err", Toast.LENGTH_SHORT).show()
                    return@awaitEachGesture
                }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                startAt = System.currentTimeMillis()
                elapsedMs = 0L
                recording = true
                cancelling = false
                startCb()
                var cancel = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.firstOrNull() ?: break
                    ch.consume()   // 吃掉：不让别的手势（列表滚动等）抢
                    if (!ch.pressed) break
                    cancel = (down.position.y - ch.position.y) > cancelPx
                    if (cancel != cancelling) cancelling = cancel
                }
                finish(cancel)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_mic), contentDescription = "按住说话", tint = if (recording) skin.accent else tint, modifier = Modifier.size(24.dp))
        if (recording || sending) Popup(popupPositionProvider = provider) {
            Row(
                Modifier.background(if (cancelling) Color(0xFFE53935) else skin.ink, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    sending -> Text("发送中…", color = Color.White, fontSize = 13.sp)
                    cancelling -> Text("松开取消", color = Color.White, fontSize = 13.sp)
                    else -> {
                        Box(Modifier.size(8.dp).background(Color(0xFFE53935), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("录音中 " + fmtClock(elapsedMs), color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("上滑取消", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private fun fmtClock(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "${s / 60}:${"%02d".format(s % 60)}"
}

/** 提示条飘在锚点（麦克风钮）正上方、右边对齐；不够位就贴边 */
private class AboveEndProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset(
            x = (anchorBounds.right - popupContentSize.width).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)),
            y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0),
        )
}

@Suppress("DEPRECATION")
private fun newRecorder(ctx: Context): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

/**
 * MediaRecorder 薄封装：m4a(AAC) 16k 单声道 32kbps，文件放 cacheDir/voice_out/。
 * start() 失败返回原因（麦克风被占——通话中 AudioEngine 拿着；或权限）；stop() 返回 (文件, 毫秒)，没录到东西返回 null 并删文件。
 */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start(): String? {
        cancel()
        val dir = File(ctx.cacheDir, "voice_out").apply { mkdirs() }
        val f = File(dir, "vm_${System.currentTimeMillis()}.m4a")
        val r = newRecorder(ctx)
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r; file = f; startedAt = System.currentTimeMillis()
            null
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            f.delete()
            "${e.javaClass.simpleName}${e.message?.let { " " + it.take(40) } ?: ""}（通话中麦克风被占？）"
        }
    }

    fun stop(): Pair<File, Long>? {
        val r = rec ?: return null
        val f = file
        val ms = System.currentTimeMillis() - startedAt
        rec = null; file = null
        // 按下立刻松：还没写出一帧 stop() 会抛 RuntimeException——当"太短"处理
        val ok = try { r.stop(); true } catch (_: Exception) { false }
        try { r.release() } catch (_: Exception) {}
        if (!ok || f == null || !f.exists() || f.length() == 0L) { f?.delete(); return null }
        return f to ms
    }

    fun cancel() {
        val r = rec ?: return
        rec = null
        try { r.stop() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
        file?.delete()
        file = null
    }
}
