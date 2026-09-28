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
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * 通话音频（M2）：
 *  - 录：AudioRecord 16kHz 单声道 16bit → 简单能量 VAD 切句 → base64 → {"type":"audio","format":"pcm16k"}
 *    0.113 这条连接上服务端发过 hello{stream_stt:true} 就改成边说边传：一开口 stream_start → 每 200ms 一块 stream_chunk → 说完 stream_end；
 *    没收到 hello（老服务端）还是上面那样说完整句发
 *  - 放：辰的 mp3 用信任自签证书的 OkHttp 下载到缓存，再交给 MediaPlayer
 *  - 半双工：辰在说的时候不录（省得把外放录回去）；0.112 起只在开免提时这样，没开免提照样边放边收
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
        private const val MIN_SPEECH_MS = 350       // 短于这个的当噪音丢掉（0.113 流式时已经传上去了 → stream_end 带 cancel）
        private const val END_SILENCE_MS = 1500      // 说完停顿多久算一句（0908 实测 700 会把她的话切碎）
        private const val MAX_UTTERANCE_MS = 15000  // 一句最长
        // 0.113 流式识别（服务端 hello 里 stream_stt=true 才用）：字是边传边认的，说完只差收尾——停顿 1.1 秒就收（老路还是 1.5）；
        // 一句最长放到 60 秒（她嫌长句被截断；老路还是 15 秒）；攒够 200ms 发一块
        private const val END_SILENCE_MS_STREAM = 1100
        private const val MAX_UTTERANCE_MS_STREAM = 60000
        private const val STREAM_CHUNK_BYTES = SAMPLE_RATE * 2 * 200 / 1000   // 6400
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
    // 0.107：当前这段的完成锁 插话时用来叫醒放音线程
    // （0.112 撤了 bargeInOk：它靠 headsetRouted() 判断走没走耳机，而那正是坏掉的路由——判错就把她的话丢了。现在只看免提）
    @Volatile private var currentDone: Object? = null
    private var noiseRms = 150.0
    // 0.107 诊断：当前录音实际走的麦（AudioRecord.routedDevice 才是真的输入设备 不是猜的路由）
    @Volatile private var currentRec: AudioRecord? = null
    // 0.112 录音跟着耳机麦走（0928 20:02 那通：她戴着耳机，我的声音在耳机里，录音却是手机麦——站远就收不到；那通中途切过免提）。
    // 放音每句都是新的 MediaPlayer，开播时按当时的路由重新选设备；录音是开场建的那一个 AudioRecord，
    // 我们从来没管过它往哪录，全靠系统在 SCO 连上/切免提之后自己把它挪过去——看来这台手机上并不总挪。
    // 现在：通话实际走哪副耳机，就用 setPreferredDevice 把录音钉到同一副耳机的麦；免提/听筒/没麦的耳机 → 交还系统。
    // 设备或通话设备一变就置脏，由录音线程自己去改（AudioRecord 只在录音线程上碰）
    @Volatile private var micDirty = true
    @Volatile private var commListener: Any? = null   // 0.112 Android 12+ 的 OnCommunicationDeviceChangedListener（Any 免得老系统加载这个类型；endCall 可能在 ws 线程上跑）
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
        // 0.112 通话设备真的换了（蓝牙 SCO 连上/断开、免提切过去了）→ 录音线程重钉麦。
        // SCO 是异步连的：setSpeaker 那一刻往往还没连上（通话设备还是听筒），要等这个回调才知道能钉蓝牙麦了
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                val l = AudioManager.OnCommunicationDeviceChangedListener { micDirty = true }
                audioManager.addOnCommunicationDeviceChangedListener(context.mainExecutor, l)
                commListener = l
            } catch (_: Exception) {}
        }
    }

    fun endCall() {
        calling = false
        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            (commListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let {
                try { audioManager.removeOnCommunicationDeviceChangedListener(it) } catch (_: Exception) {}
            }
        }
        commListener = null
        stopCapture()
        focusWatch?.interrupt(); focusWatch = null
        lostForGood = false
        stopPlayer()
        focusRequest?.let { try { audioManager.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        focusRequest = null
        interrupted = false
        userMuted = false
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            audioManager.clearCommunicationDevice()
        } else {
            // 0.112 Android 11 及以下：startBluetoothSco 以前从没配过 stop（缺 MODIFY_AUDIO_SETTINGS 时 start 本来就被系统拒掉 没事；
            // 权限补上以后 start 会真的开 SCO，挂了不关耳机就一直卡在通话音质）
            @Suppress("DEPRECATION")
            try { audioManager.isBluetoothScoOn = false; audioManager.stopBluetoothSco() } catch (_: Exception) {}
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        speaker = false
    }

    @Volatile private var speaker = false

    /** Android 12+ 的 isSpeakerphoneOn 不可靠（0908 实测免提一直显示关），改走 communication device。
     *  （0.112：那次多半是缺 MODIFY_AUDIO_SETTINGS——setSpeakerphoneOn/setMode 没这个权限会被系统静默拒掉，manifest 已补） */
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
        micDirty = true   // 0.112 切免提/换耳机后重钉麦（蓝牙这一下多半还钉不上：SCO 还在连，等通话设备回调）
    }
    fun isSpeaker() = speaker

    /** 0.112 现在该用哪个麦：通话实际走哪副耳机就用那副耳机的麦；null = 交还系统（免提/听筒/没麦的有线耳机/蓝牙 SCO 还没连上）。
     *  只看"实际走的"通话设备、不看我们要的：SCO 没连上就钉蓝牙麦 会录到一片空白，比手机麦还糟 */
    private fun wantedMic(): android.media.AudioDeviceInfo? {
        if (android.os.Build.VERSION.SDK_INT < 31) return null   // 老系统走 setBluetoothScoOn 那套 录音跟着 force use 走 不钉
        if (speaker) return null
        val out = audioManager.communicationDevice ?: return null
        val types = when (out.type) {
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> setOf(out.type)
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET ->
                setOf(android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE)
            else -> return null
        }
        val mics = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in types }
        return mics.firstOrNull { it.address == out.address } ?: mics.firstOrNull()   // 同一副（地址相同）优先
    }

    /** 0.112 只在录音线程上调：把 rec 钉到 wantedMic()。已经是它就不动——真换设备时录音会在底层重建一下 断一两百毫秒 */
    private fun pinMic(rec: AudioRecord) {
        try {
            val want = wantedMic()
            if (rec.preferredDevice?.id != want?.id) rec.setPreferredDevice(want)
        } catch (_: Exception) {}
    }

    /** 0.112 诊断行用的设备短名 */
    private fun devName(t: Int?): String = when (t) {
        android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手机"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙耳机"
        android.media.AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙LE耳机"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "无麦有线耳机"
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB耳机"
        null -> "未知"
        else -> "类型$t"
    }

    /** 0.107 诊断：录音实际走的麦。0.112 加三样：钉了别的麦却没钉上 →「(要X麦)」；通话声音实际走哪 →「声:Y」；
     *  通话模式没设上 →「非通话模式」。正常戴蓝牙耳机应是「蓝牙耳机麦 声:蓝牙耳机」 */
    private fun micLabel(): String {
        val rec = currentRec
        val got = rec?.routedDevice?.type
        val want = rec?.preferredDevice?.type
        var s = if (got == null) "麦未知" else devName(got) + "麦"
        if (want != null && want != got) s += "(要${devName(want)}麦)"
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            s += " 声:" + devName(try { audioManager.communicationDevice?.type } catch (_: Exception) { null })
        }
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) s += " 非通话模式"
        return s
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
            micDirty = false; pinMic(rec)   // 0.112 开录前先钉一次：有线/USB 这时就钉上；蓝牙要等 SCO 连上、通话设备回调再钉
            rec.startRecording()
            currentRec = rec
            onState("听着呢 · " + micLabel())
            val frame = ByteArray(FRAME_BYTES)
            // 这句还没发出去的部分：老路攒整句、说完一次发；0.113 流式时只剩不到一块（200ms）的那点，够一块就发走
            val utter = ByteArrayOutputStream()
            var speechMs = 0
            var silenceMs = 0
            var inSpeech = false
            var peak = 0.0
            var lastShow = 0L
            try {
                while (capturing) {
                    if (micDirty) { micDirty = false; pinMic(rec) }   // 0.112 切过免提/换过耳机/SCO 刚连上：重钉麦
                    val n = rec.read(frame, 0, FRAME_BYTES)
                    if (n <= 0) continue
                    // 0.113 流到一半换过连接（断线/重连）：这个 sid 是上一条连接上开的，服务端那边自己收尾，新连接上一个字不补；
                    // 本地这句就此作罢——她要是还在说，下一声起按新的一句算（新连接收到 hello 就流式，没收到就走老路整句发）
                    if (curSid != null && sidGen != connGen.get()) {
                        curSid = null
                        utter.reset(); speechMs = 0; silenceMs = 0; inSpeech = false; userSpeaking = false
                    }
                    // 0.112 辰在说时只有开着免提才丢帧（外放才会录回去）；没开免提照样收。
                    // 以前看的是 headsetRouted()——它看的正是坏掉的那条路由，判成"没走耳机"就把她在我说话时说的全丢了
                    if ((muted && speaker) || interrupted || userMuted) { // 辰在说且开着免提 / 0.45 被真电话打断 / 0.106 她按了静音：丢帧，重置状态
                        // 0.96：辰开口那一下她那句正说到一半——先把已说的发出去，不再整句扔掉
                        // （以前这里直接 reset：0925 夜她"说了一大堆好像没识别出来"。被真电话打断的不发）
                        val keep = (muted || userMuted) && !interrupted && inSpeech && speechMs >= MIN_SPEECH_MS
                        if (curSid != null) {
                            // 0.113 流式：已说的早一块块传上去了，这里只收尾（剩下不到一块的补上再 stream_end）；
                            // 老路会整句扔掉的（被真电话打断 / 不够 MIN_SPEECH_MS）带 cancel
                            streamEnd(utter, cancel = !keep)
                        } else if (keep) {
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
                        pinMic(rec)   // 0.112 兜底：两边都没说话时每秒对一次该用的麦——回调漏了/来晚了也能跟上
                        onState("听着呢 · ${micLabel()} · 音量${peak.toInt()}/门槛${gate.toInt()}")
                        peak = 0.0; lastShow = now
                    }
                    if (loud) {
                        // 0.113 streamStart：这条连接能流式就开流（发 stream_start），不能就什么都不做、这句走老路
                        if (!inSpeech) { inSpeech = true; userSpeaking = true; onState("你在说…"); streamStart() }
                        utter.write(frame, 0, n); speechMs += FRAME_MS; silenceMs = 0
                        // 0.111 不再插话掐我：0928 通话一点杂音就把辰的话掐断三回，她"没有必要擦掉你说的话"。
                        // 没开免提时照样边放边收（0.112 起不再看走没走耳机），她说的一句不丢，只是不停我的播放
                    } else if (inSpeech) {
                        utter.write(frame, 0, n); silenceMs += FRAME_MS
                    }
                    // 0.113 流式：攒够 200ms（含句尾那段静音）就发一块；说完的判定换成流式那套（停 1.1 秒 / 最长 60 秒）
                    val streaming = curSid != null
                    if (streaming && utter.size() >= STREAM_CHUNK_BYTES) streamChunk(utter)
                    val endMs = if (streaming) END_SILENCE_MS_STREAM else END_SILENCE_MS
                    val maxMs = if (streaming) MAX_UTTERANCE_MS_STREAM else MAX_UTTERANCE_MS
                    val done = inSpeech && (silenceMs >= endMs || speechMs >= maxMs)
                    if (done) {
                        if (streaming) {
                            // 0.113 说完：剩的补一块再 stream_end；不够 MIN_SPEECH_MS 的（老路直接丢）叫服务端作废
                            val ok = speechMs >= MIN_SPEECH_MS
                            streamEnd(utter, cancel = !ok)
                            if (ok) onState("发出去了，等辰…")
                        } else if (speechMs >= MIN_SPEECH_MS) {
                            val b64 = Base64.encodeToString(utter.toByteArray(), Base64.NO_WRAP)
                            send(JSONObject().put("type", "audio").put("audio", b64).put("format", "pcm16k"))
                            onState("发出去了，等辰…")
                        }
                        utter.reset(); speechMs = 0; silenceMs = 0; inSpeech = false; userSpeaking = false
                    }
                }
            } finally {
                // 0.113 挂断/录音停了时她这句还没说完：老路是整句扔掉，流式对应带 cancel 收尾（不然服务端那句一直悬着）
                if (curSid != null) { try { streamEnd(utter, cancel = true) } catch (_: Exception) {} }
                curSid = null
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

    // ---------- 0.113 流式识别 ----------

    // 这条连接上收到过 hello{stream_stt:true} 才开；ChenService 每次新建/断开连接都先 resetStream() 关掉，等新 hello
    @Volatile private var streamStt = false
    // 连接代数：每换一次连接 +1。开流时记下当时的代数，对不上 = 这个 sid 是上一条连接的，不再往新连接上发
    private val connGen = AtomicInteger()
    // 正在传的这句的 sid（null = 没在说 / 这句走老路）。只有录音线程写；stt_partial/stt_final 来时拿它对号
    @Volatile private var curSid: String? = null
    private var sidGen = 0   // 这句开流时的连接代数（只在录音线程上碰）
    // 状态行上挂着的半句字是哪一句的（stt_partial/stt_final 都在 ws 收消息的线程上 先后有序）
    @Volatile private var partialSid: String? = null

    /** 服务端连上就发 hello；stream_stt=true 才边说边传。只认这条连接上的——换连接前 resetStream 已经关掉了 */
    fun onHello(streamOk: Boolean) { streamStt = streamOk }

    /** 连接换了/断了（ChenService 新建连接前、断线时调）：流式先关等新 hello；代数 +1，录音线程下一帧就把手里的 sid 作废 */
    fun resetStream() { streamStt = false; connGen.incrementAndGet() }

    /** 边说边认出来的字：只在她还说着这一句时挂到状态行「你在说：…」（最后十来个字）；不进字幕，字幕服务端照旧发 stt */
    fun onSttPartial(sid: String, text: String) {
        val cur = curSid ?: return            // 这句已经说完了：迟到的半句不上屏（状态行已经换成「发出去了」之类）
        if (sid.isNotEmpty() && sid != cur) return
        val t = text.trim()
        if (t.isEmpty()) return
        partialSid = cur
        onState("你在说：" + (if (t.length > 12) "…" + t.takeLast(12) else t))
    }

    /** 认完一句：状态行上那半句撤掉。还在这句里 → 回到「你在说…」；这句已经收尾的，状态行一般早换成「发出去了」/辰在说/诊断行了，
     *  不动——只有她按静音掐断的那句会一直挂着（静音时不刷诊断行），清空 */
    fun onSttFinal(sid: String) {
        val shown = partialSid ?: return
        if (sid.isNotEmpty() && sid != shown) return
        partialSid = null
        if (curSid == shown) onState("你在说…")
        else if (userMuted) onState("")
    }

    /** 这句刚开口（只在录音线程上调）：这条连接能流式就发 stream_start，不能就什么都不做。
     *  这里没有预录缓冲：起音那一帧就是流的开头，跟老路整句的开头一样，随第一块发出去 */
    private fun streamStart() {
        val gen = connGen.get()   // 先取代数再看开关：两次读之间断了线，开出来的流下一帧也会被认成旧连接的
        if (!streamStt) return
        val sid = UUID.randomUUID().toString()
        sidGen = gen
        curSid = sid
        send(JSONObject().put("type", "stream_start").put("sid", sid).put("format", "pcm16k"))
    }

    /** 攒着的这点音频作为一块发走（只在录音线程上调） */
    private fun streamChunk(buf: ByteArrayOutputStream) {
        val sid = curSid ?: return
        if (buf.size() == 0) return
        if (sidGen == connGen.get()) {
            val b64 = Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
            send(JSONObject().put("type", "stream_chunk").put("sid", sid).put("audio", b64))
        }
        buf.reset()
    }

    /** 这句收尾（只在录音线程上调）。cancel=false：剩下不到一块的先补发，再 stream_end；cancel=true：告诉服务端这句作废。
     *  换过连接的 sid 什么都不发（服务端断线时自己收尾了） */
    private fun streamEnd(buf: ByteArrayOutputStream, cancel: Boolean) {
        val sid = curSid ?: return
        if (sidGen == connGen.get()) {
            if (!cancel) streamChunk(buf)
            val o = JSONObject().put("type", "stream_end").put("sid", sid)
            if (cancel) o.put("cancel", true)
            send(o)
        }
        curSid = null
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

        // 0.96 轮流说：她正说着就先别开口，等她这句说完（静音 1.5 秒发出去；0.113 流式是 1.1 秒）再播；
        // 最多等 WAIT_TURN_MS，还在说就开口——capture 那边会先把她已说的半句发出去，不扔
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (userSpeaking && capturing && android.os.SystemClock.elapsedRealtime() - t0 < WAIT_TURN_MS) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
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
