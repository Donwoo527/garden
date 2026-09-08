package me.chen.laidian.net

import android.content.Context
import android.media.MediaPlayer
import me.chen.laidian.Tls
import okhttp3.Request
import java.io.File

/** 播放聊天里的语音消息（mp3 在 8300 的 /media 下，证书自签，得用信任的客户端先下再放）。 */
object VoicePlayer {
    private var player: MediaPlayer? = null

    fun play(context: Context, url: String, onDone: () -> Unit = {}) {
        stop()
        Thread {
            val file = File(context.cacheDir, "vm_" + url.substringAfterLast('/'))
            try {
                if (!file.exists()) {
                    Tls.client(context).newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) return@Thread
                        file.outputStream().use { out -> r.body?.byteStream()?.copyTo(out) }
                    }
                }
                val mp = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { it.release(); if (player === it) player = null; onDone() }
                    prepare()
                }
                player = mp
                mp.start()
            } catch (e: Exception) {
                onDone()
            }
        }.start()
    }

    fun stop() {
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
    }
}
