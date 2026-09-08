package me.chen.laidian

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

/**
 * 通话音频（M2）：
 *  - 录：AudioRecord 16kHz 单声道 16bit → 简单能量 VAD 切句 → base64 → {"type":"audio","format":"pcm16k"}
 *  - 放：辰的 mp3 用信任自签证书的 OkHttp 下载到缓存，再交给 MediaPlayer
 *  - 半双工：辰在说的时候不录（省得把外放录回去）
 */
class AudioEngine(
    private val context: Context,
    private val client: OkHttpClient,
    private val send: (JSONObject) -> Boolean,
    private val onState: (String) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val FRAME_MS = 20
        private const val FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_MS / 1000      // 640
        private const val SPEECH_RMS = 700.0        // 判"在说话"的能量门槛（int16 尺度），之后按实机调
        private const val MIN_SPEECH_MS = 350       // 短于这个的当噪音丢掉
        private const val END_SILENCE_MS = 700      // 说完停顿多久算一句
        private const val MAX_UTTERANCE_MS = 10000  // 一句最长
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var capturing = false
    @Volatile private var muted = false
    private var captureThread: Thread? = null
    private var player: MediaPlayer? = null
    private val playQueue = LinkedBlockingQueue<String>()
    private var playThread: Thread? = null

    // ---------- 通话开始/结束 ----------

    fun startCall() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        startCapture()
        startPlayer()
    }

    fun endCall() {
        stopCapture()
        stopPlayer()
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    fun setSpeaker(on: Boolean) { audioManager.isSpeakerphoneOn = on }
    fun isSpeaker() = audioManager.isSpeakerphoneOn

    // ---------- 录音 ----------

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        if (capturing) return
        capturing = true
        captureThread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_BYTES * 8)
                )
            } catch (e: Exception) { onState("录音初始化失败：${e.message}"); capturing = false; return@Thread }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { onState("录音设备不可用"); capturing = false; return@Thread }
            rec.startRecording()
            onState("听着呢")
            val frame = ByteArray(FRAME_BYTES)
            val utter = ByteArrayOutputStream()
            var speechMs = 0
            var silenceMs = 0
            var inSpeech = false
            try {
                while (capturing) {
                    val n = rec.read(frame, 0, FRAME_BYTES)
                    if (n <= 0) continue
                    if (muted) { // 辰在说：丢帧，重置状态
                        utter.reset(); speechMs = 0; silenceMs = 0; inSpeech = false; continue
                    }
                    val loud = rms(frame, n) > SPEECH_RMS
                    if (loud) {
                        if (!inSpeech) { inSpeech = true; onState("你在说…") }
                        utter.write(frame, 0, n); speechMs += FRAME_MS; silenceMs = 0
                    } else if (inSpeech) {
                        utter.write(frame, 0, n); silenceMs += FRAME_MS
                    }
                    val done = inSpeech && (silenceMs >= END_SILENCE_MS || speechMs >= MAX_UTTERANCE_MS)
                    if (done) {
                        if (speechMs >= MIN_SPEECH_MS) {
                            val b64 = Base64.encodeToString(utter.toByteArray(), Base64.NO_WRAP)
                            send(JSONObject().put("type", "audio").put("audio", b64).put("format", "pcm16k"))
                            onState("发出去了，等辰…")
                        }
                        utter.reset(); speechMs = 0; silenceMs = 0; inSpeech = false
                    }
                }
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }, "chen-capture").apply { start() }
    }

    private fun stopCapture() {
        capturing = false
        captureThread?.join(1500)
        captureThread = null
    }

    private fun rms(buf: ByteArray, n: Int): Double {
        var sum = 0.0
        var i = 0
        val samples = n / 2
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        return if (samples == 0) 0.0 else sqrt(sum / samples)
    }

    // ---------- 放音 ----------

    /** 收到 audio_reply 时调用；audio_url 形如 /audio/reply_xxx.mp3 */
    fun enqueueReply(audioUrl: String) {
        playQueue.offer(audioUrl)
    }

    private fun startPlayer() {
        if (playThread != null) return
        playThread = Thread({
            while (capturing || playQueue.isNotEmpty()) {
                val url = try { playQueue.take() } catch (_: InterruptedException) { break }
                if (url == "__stop__") break
                playOne(url)
            }
        }, "chen-play").apply { start() }
    }

    private fun stopPlayer() {
        playQueue.clear()
        playQueue.offer("__stop__")
        try { player?.stop() } catch (_: Exception) {}
        player?.release(); player = null
        playThread?.join(1500)
        playThread = null
        muted = false
    }

    private fun playOne(audioUrl: String) {
        val full = "https://${BuildConfig.SERVER_HOST}:${BuildConfig.SERVER_PORT}$audioUrl"
        val file = File(context.cacheDir, "reply_" + audioUrl.substringAfterLast('/'))
        try {
            client.newCall(Request.Builder().url(full).build()).execute().use { resp ->
                if (!resp.isSuccessful) { onState("下载回复失败 ${resp.code}"); return }
                file.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
            }
        } catch (e: Exception) { onState("下载回复失败：${e.message}"); return }

        muted = true
        onState("辰在说…")
        val done = Object()
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { synchronized(done) { done.notifyAll() } }
                setOnErrorListener { _, _, _ -> synchronized(done) { done.notifyAll() }; true }
                prepare()
            }
            player = mp
            mp.start()
            synchronized(done) { done.wait(60_000) }
            mp.release()
            if (player === mp) player = null
        } catch (e: Exception) {
            onState("播放失败：${e.message}")
        } finally {
            file.delete()
            muted = false
            if (capturing) onState("听着呢")
        }
    }
}
