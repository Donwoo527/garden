package me.chen.laidian.net

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.chen.laidian.Tls
import okhttp3.Request
import java.io.File

/** 播放聊天里的语音消息（mp3 在 8300 的 /media 下，证书自签，得用信任的客户端先下再放）。 */
object VoicePlayer {
    /** 0915 她要的播放进度条：正在放哪条、放到几成。null = 没在放 */
    data class State(val url: String, val progress: Float)

    private var player: MediaPlayer? = null
    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    /** 同一条再点=停；不同条=换 */
    fun toggle(context: Context, url: String) {
        if (_state.value?.url == url) stop() else play(context, url)
    }

    /** 0915 她要的：进度条能拖。fraction 0..1 */
    fun seekTo(url: String, fraction: Float) {
        val mp = player ?: return
        if (_state.value?.url != url) return
        try {
            val d = mp.duration
            if (d > 0) { mp.seekTo((d * fraction.coerceIn(0f, 1f)).toInt()); _state.value = State(url, fraction.coerceIn(0f, 1f)) }
        } catch (_: Exception) {}
    }

    fun play(context: Context, url: String, onDone: () -> Unit = {}) {
        stop()
        _state.value = State(url, 0f)
        Thread {
            val file = File(context.cacheDir, "vm_" + url.substringAfterLast('/'))
            try {
                if (!file.exists()) {
                    Tls.client(context).newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) { _state.value = null; return@Thread }
                        file.outputStream().use { out -> r.body?.byteStream()?.copyTo(out) }
                    }
                }
                val mp = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { it.release(); if (player === it) { player = null; _state.value = null }; onDone() }
                    prepare()
                }
                player = mp
                mp.start()
                // 每 100ms 报一次进度，直到这条放完或被换掉
                while (player === mp) {
                    try {
                        val d = mp.duration
                        if (d > 0) _state.value = State(url, (mp.currentPosition.toFloat() / d).coerceIn(0f, 1f))
                    } catch (_: Exception) { break }
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                _state.value = null
                onDone()
            }
        }.start()
    }

    fun stop() {
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
        _state.value = null
    }
}
