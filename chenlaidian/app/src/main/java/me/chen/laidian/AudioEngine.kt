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
        // 0.107 门槛改自适应（0927 夜她"认真说的话一句没录进去 买东西的杂音全在"：实测她对我说话九成的帧在 870 以下、
        // 店里别人 1400~2900，写死 700 等于只收大嗓门）：门槛 = 底噪 × 3，夹在 300~900 之间；底噪只在没人说话时慢慢跟
        private const val SPEECH_RMS_MIN = 300.0
        private const val SPEECH_RMS_MAX = 900.0
        private const val NOISE_MULT = 3.0
        // 0.107 的插话（她说满 300ms 我就闭嘴）0.111 撤了：一点杂音就掐断我，见 capture 循环里的说明
        private const val MIN_SPEECH_MS = 350       // 短于这个的当噪音丢掉
        private const val END_SILENCE_MS = 1500      // 说完停顿多久算一句（0908 实测 700 会把她的话切碎）
        private const val MAX_UTTERANCE_MS = 15000  // 一句最长
        // 0.96 轮流说：她正说着时我的回复先等她这句说完再播，最多等这么久（0925 夜"你一说话我的话就没识别了"）
        private const val WAIT_TURN_MS = 8000L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var capturing = false
    @Volatile private var muted = false
    // 0.96 她此刻是不是正说到一半（capture 线程写，play 线程读：用来等她说完再开口）
    @Volatile private var userSpeaking = false
    // 0.45 音频焦点：真电话/别的app抢走声音时暂停 抢完自动恢复（0911她通话被打断只能重拨的坑）
    @Volatile private var interrupted = false
    // 0.106 她点的通话静音键（0927 地铁上整节车厢都被收进来）：开着时麦克风帧一律丢掉 一个字不传；按下那一刻她说到一半的先发出去
    @Volatile var userMuted = false
    // 0.107：本段回复开播时是不是走耳机（耳机=我的声音漏不进麦 可以边放边收）；当前这段的完成锁 插话时用来叫醒放音线程
    @Volatile private var bargeInOk = false
    @Volatile private var currentDone: Object? = null
    private var noiseRms = 150.0
    // 0.107 诊断：当前录音实际走的麦（AudioRecord.routedDevice 才是真的输入设备 不是猜的路由）
    @Volatile private var currentRec: AudioRecord? = null
    private var focusRequest: android.media.AudioFocusRequest? = null
    // 0.94 永久失焦：小红书/视频这类 app 放带声音的东西 = AUDIOFOCUS_LOSS（不是 TRANSIENT）。
    // 系统对永久失焦不会再回调 GAIN——以前 interrupted 就一直 true，整通电话聋掉，只能挂了重打
    // （0924 17:30 她切小红书后说话四十多分钟一句没传上来）。现在：外面没声了自己把焦点要回来。
    @Volatile private var lostForGood = false
    private var focusWatch: Thread? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                interrupted = true; lostForGood = true
                try { player?.pause() } catch (_: Exception) {}
                onState("别的app在出声 我先不听；声音一停我自己接着听")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                interrupted = true
                try { player?.pause() } catch (_: Exception) {}
                onState("被打断了 我等着 回来继续")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                interrupted = false; lostForGood = false
                try { player?.start() } catch (_: Exception) {}
                onState("回来了 听着呢")
            }
        }
    }

    /** 0.94 把焦点要回来（外面没声了 / 她回到通话页）。拿到就接着听；requestAudioFocus 批准时不会再回调 GAIN，所以这里自己复位。 */
    fun regainFocus(): Boolean {
        val req = focusRequest ?: return false
        val r = try { audioManager.requestAudioFocus(req) } catch (_: Exception) { AudioManager.AUDIOFOCUS_REQUEST_FAILED }
        if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        val was = interrupted
        interrupted = false; lostForGood = false
        if (was) {
            try { player?.start() } catch (_: Exception) {}
            onState("回来了 听着呢")
        }
        return true
    }

    /** 0.94 外面有人在用声音就别抢：真电话/响铃（不能把她的真电话录进来）、在放视频音乐、别的 app 也在录（微信语音等）。 */
    private fun othersBusy(): Boolean {
        val m = audioManager.mode
        if (m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_RINGTONE) return true
        if (audioManager.isMusicActive) return true
        if (android.os.Build.VERSION.SDK_INT >= 24 && audioManager.activeRecordingConfigurations.size > 1) return true
        return false
    }

    /** 0.94 永久失焦时每秒看一眼：外面连续 2 秒没人用声音 → 要回焦点。别人在用时不抢（不打断她看视频 不录她的真电话）。 */
    private fun startFocusWatch() {
        focusWatch = Thread({
            var quietMs = 0
            while (capturing) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                if (!lostForGood || othersBusy()) { quietMs = 0; continue }
                quietMs += 1000
                if (quietMs >= 2000) { quietMs = 0; regainFocus() }
            }
        }, "chen-focus-watch").apply { isDaemon = true; start() }
    }
    private var captureThread: Thread? = null
    private var player: MediaPlayer? = null
    private val playQueue = LinkedBlockingQueue<String>()
    private var playThread: Thread? = null

    // ---------- 通话开始/结束 ----------

    // 0.95 通话中途插拔/连断耳机：系统不会替我们改 communication device（之前手动定死在听筒）→ 声音还往听筒走
    // 设备一变就按当前免提状态重新选一次。注册时系统会先把现有设备回调一遍，等于开场再选一次，无害。
    @Volatile private var calling = false
    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { if (calling) setSpeaker(speaker) }
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { if (calling) setSpeaker(speaker) }
    }

    fun startCall() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        // 0.45 正式声明"我在通话"：拿语音焦点 别人抢了会通知我们 抢完自动还
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener).build()
            .also { audioManager.requestAudioFocus(it) }
        interrupted = false; lostForGood = false
        setSpeaker(false)
        startCapture()
        startPlayer()
        startFocusWatch()
        calling = true
        try { audioManager.registerAudioDeviceCallback(deviceCallback, android.os.Handler(android.os.Looper.getMainLooper())) } catch (_: Exception) {}
    }

    fun endCall() {
        calling = false
        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        stopCapture()
        focusWatch?.interrupt(); focusWatch = null
        lostForGood = false
        stopPlayer()
        focusRequest?.let { try { audioManager.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        focusRequest = null
        interrupted = false
        userMuted = false
        if (android.os.Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
        speaker = false
    }

    @Volatile private var speaker = false

    /** Android 12+ 的 isSpeakerphoneOn 不可靠（0908 实测免提一直显示关），改走 communication device。 */
    fun setSpeaker(on: Boolean) {
        speaker = on
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val devices = audioManager.availableCommunicationDevices
            // 0.31 蓝牙耳机优先(她0910地铁实测:耳机麦收不到音才补的)：
            // 非免提且蓝牙耳机在场 → 通话收放全走SCO耳机；免提或无蓝牙 → 原来的扬声器/听筒逻辑
            // 0.95 耳机优先扩到有线/USB/BLE（她 0925 早上"听筒改成耳机就没声了"）：非免提时有哪种耳机走哪种
            val headsetTypes = setOf(
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET
            )
            val bt = if (!on) devices.firstOrNull { it.type in headsetTypes } else null
            val want = if (on) android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val dev = bt ?: devices.firstOrNull { it.type == want }
            if (dev != null) audioManager.setCommunicationDevice(dev) else audioManager.isSpeakerphoneOn = on
        } else {
            @Suppress("DEPRECATION")
            if (!on) { try { audioManager.startBluetoothSco(); audioManager.isBluetoothScoOn = true } catch (_: Exception) {} }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }
    fun isSpeaker() = speaker

    /** 0.107：现在的通话声音是不是走耳机（蓝牙/有线/USB）。走耳机时我的声音不会漏进她的麦，可以边放边收。 */
    private fun headsetRouted(): Boolean {
        if (speaker) return false
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            val t = audioManager.communicationDevice?.type
            t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn || audioManager.isWiredHeadsetOn
        }
    }

    /** 0.107 诊断：录音实际走的麦 */
    private fun micLabel(): String = when (currentRec?.routedDevice?.type) {
        android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手机麦"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙耳机麦"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机麦"
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB耳机麦"
        null -> "麦未知"
        else -> "麦${currentRec?.routedDevice?.type}"
    }

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
            currentRec = rec
            onState("听着呢 · " + micLabel())
            val frame = ByteArray(FRAME_BYTES)
            val utter = ByteArrayOutputStream()
            var speechMs = 0
            var silenceMs = 0
            var inSpeech = false
            var peak = 0.0
            var lastShow = 0L
            try {
                while (capturing) {
                    val n = rec.read(frame, 0, FRAME_BYTES)
                    if (n <= 0) continue
                    if ((muted && !bargeInOk) || interrupted || userMuted) { // 辰在说(外放/听筒怕回声) / 0.45 被真电话打断 / 0.106 她按了静音：丢帧，重置状态
                        // 0.96：辰开口那一下她那句正说到一半——先把已说的发出去，不再整句扔掉
                        // （以前这里直接 reset：0925 夜她"说了一大堆好像没识别出来"。被真电话打断的不发）
                        if ((muted || userMuted) && !interrupted && inSpeech && speechMs >= MIN_SPEECH_MS) {
                            val b64 = Base64.encodeToString(utter.toByteArray(), Base64.NO_WRAP)
                            send(JSONObject().put("type", "audio").put("audio", b64).put("format", "pcm16k"))
                        }
                        utter.reset(); speechMs = 0; silenceMs = 0; inSpeech = false; userSpeaking = false; continue
                    }
                    val level = rms(frame, n)
                    val gate = (noiseRms * NOISE_MULT).coerceIn(SPEECH_RMS_MIN, SPEECH_RMS_MAX)
                    val loud = level > gate
                    if (!inSpeech && !loud) noiseRms = noiseRms * 0.98 + level * 0.02   // 只在没人说话时跟底噪
                    // 0.107 诊断：没在说话时每秒刷一次状态行「麦 · 这一秒最大音量 · 门槛」——她说话时数字没过门槛=麦的问题；过了却没出字=后面的问题
                    if (level > peak) peak = level
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!inSpeech && !muted && now - lastShow >= 1000) {   // 我在说话时不刷 免得盖掉「辰在说…」
                        onState("听着呢 · ${micLabel()} · 音量${peak.toInt()}/门槛${gate.toInt()}")
                        peak = 0.0; lastShow = now
                    }
                    if (loud) {
                        if (!inSpeech) { inSpeech = true; userSpeaking = true; onState("你在说…") }
                        utter.write(frame, 0, n); speechMs += FRAME_MS; silenceMs = 0
                        // 0.111 不再插话掐我：0928 通话一点杂音就把辰的话掐断三回，她"没有必要擦掉你说的话"。
                        // 走耳机时照样边放边收，她说的一句不丢，只是不停我的播放
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
                        utter.reset(); speechMs = 0; silenceMs = 0; inSpeech = false; userSpeaking = false
                    }
                }
            } finally {
                userSpeaking = false
                currentRec = null
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

        // 0.96 轮流说：她正说着就先别开口，等她这句说完（静音 1.5 秒发出去）再播；
        // 最多等 WAIT_TURN_MS，还在说就开口——capture 那边会先把她已说的半句发出去，不扔
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (userSpeaking && capturing && android.os.SystemClock.elapsedRealtime() - t0 < WAIT_TURN_MS) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
        bargeInOk = headsetRouted()
        muted = true
        onState("辰在说…")
        val done = Object()
        currentDone = done
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // 0.31 回归通信流：配合 setCommunicationDevice 路由——蓝牙在则收放全走SCO耳机
                        // (0.30 的 MEDIA/A2DP 只救了"听"救不了"说"，她耳机麦收不了音，废弃)
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
            currentDone = null
            muted = false
            if (capturing) onState("听着呢 · " + micLabel())
        }
    }
}
