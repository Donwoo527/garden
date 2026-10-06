package me.chen.laidian

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.min

/**
 * 前台服务：常驻，挂着到 VPS 的 WebSocket。
 * 收到 incoming_call 就全屏响铃（CallActivity）。
 * M0：连上 + 显示"辰在线" + 来电弹屏。声音在 M2。
 */
class ChenService : Service() {

    companion object {
        const val ACTION_START = "me.chen.laidian.START"
        const val ACTION_STOP = "me.chen.laidian.STOP"
        const val ACTION_TEST_CALL = "me.chen.laidian.TEST_CALL"
        const val ACTION_ACCEPT = "me.chen.laidian.ACCEPT"
        const val ACTION_HANGUP = "me.chen.laidian.HANGUP"
        const val ACTION_SPEAKER = "me.chen.laidian.SPEAKER"
        const val ACTION_MUTE = "me.chen.laidian.MUTE"      // 0.106 通话静音
        const val ACTION_REGAIN = "me.chen.laidian.REGAIN"   // 0.94 回到通话页：把音频焦点要回来

        const val CH_SERVICE = "chen_service"
        const val CH_CALL = "chen_call"
        const val CH_MSG = "chen_msg"
        const val NOTIF_SERVICE = 1
        const val NOTIF_CALL = 2
        const val NOTIF_USAGE = 3

        val status = MutableLiveData("未启动")
        val lastText = MutableLiveData("")
        /** 0.41 STT/引擎状态（听着呢/翻译中/你在说…）与字幕分流：状态走这里 不再占字幕区 */
        val sttStatus = MutableLiveData("")
        /** 0.52 查岗上报状态（主页"聊天✓ · 语音"那行末尾显示）：✓ / 未授权(mode=?) / 无事件 / 失败:… */
        val usageStatus = MutableLiveData("")
        @Volatile var callStartTs = 0L   // 0.34 通话开始时间(悬浮小窗计时用)
        /** 空闲 / 响铃中 / 拨号中 / 通话中 / 已挂断（0929 加「拨号中」：她拨出时 CallActivity 先写它，盖掉上一通留下的「已挂断」） */
        val callState = MutableLiveData("空闲")
        val speakerOn = MutableLiveData(false)
        val micMuted = MutableLiveData(false)   // 0.106 通话页静音钮的状态
        /** 0.126 通话中语音 ws 的状态（通话页标题 / 小窗跟着变）："" 正常 / "down" 断了在重连 / "back" 刚连回来（挂 2.5 秒） */
        val callLink = MutableLiveData("")
        @Volatile var running = false

        // 0.126 连接看门狗：发起连接后这么久还没 auth 就掐掉重来。OkHttp 这个 client 的 readTimeout=0 且没设 callTimeout，
        // TLS 握手 / 升级响应卡住时会一直等；走梯子的 SOCKS 口时本机这一跳秒通，上游死了它也不说，以前能干等好几分钟
        private const val CONNECT_TIMEOUT_MS = 12_000L
        // 0.126 通话中 5 秒一个 ping，15 秒什么都没收到就当线死了（以前只靠 OkHttp 25 秒一轮的协议 ping，最慢 50 秒才发现）
        private const val PING_CALL_MS = 5_000L
        private const val PING_IDLE_MS = 30_000L
        private const val LINK_DEAD_MS = 15_000L
        // 0.126 通话中重连退避封顶 10 秒（空闲还是 60 秒）
        private const val CALL_BACKOFF_MAX_MS = 10_000L
        // 0.126 挂断时线断着、没交出去的存话：这么久以内连上才补发（再久就只发挂断）
        private const val LEFT_MAX_AGE_MS = 10 * 60_000L
    }

    private lateinit var client: OkHttpClient
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var ws: WebSocket? = null
    private var backoffMs = 1000L
    private var foregroundStarted = false
    private lateinit var audio: AudioEngine
    @Volatile private var inCall = false

    // 0.126 语音 ws 断线处理（1006 21:53 门禁那通：断了界面照样「通话中 · 听着呢」，三分钟没连回来）。
    // 连接状态的改动（onDown / onAuthed / 看门狗）都放在主线程上做；OkHttp 回调只认当前这条 ws（被掐掉的旧连接迟到的回调一律不理）
    @Volatile private var authed = false          // 这条连接收到过 auth：服务端认了，可以发业务消息
    @Volatile private var lastRxAt = 0L           // 最近一次收到服务端任何消息（elapsedRealtime）
    // 0.126 接通/挂断只在认证过的连接上发；线断着时先记下，连上再发（以前 ws=null 时直接丢：断线时挂断，服务端就一直以为还在通话）。
    // 只管"没发出去"的，不管"发出去了没回音"的——宁可漏不重发，免得服务端多写一行 [电话接通]/[电话挂断]。下面三个只在主线程上碰
    private var pendingAccept = false
    private var pendingHangup = false
    private var pendingLeft: List<AudioEngine.Held> = emptyList()   // 挂断时线断着、没交出去的存话，补在挂断前面
    private var pendingLeftAt = 0L
    private val reconnect = Runnable { connect() }
    private val connectWatch = Runnable {
        val w = ws
        if (w != null && !authed) { ws = null; w.cancel(); onDown("${CONNECT_TIMEOUT_MS / 1000} 秒没连上") }
    }
    private val clearBack = Runnable { if (callLink.value == "back") callLink.value = "" }

    private val pingRunnable = object : Runnable {
        override fun run() {
            // 0.126 通话中：15 秒没收到服务端任何东西（pong 也没有）= 线死了，掐掉重连，别让她对着死线说
            if (inCall && authed && android.os.SystemClock.elapsedRealtime() - lastRxAt > LINK_DEAD_MS) {
                val w = ws
                if (w != null) { ws = null; w.cancel(); onDown("通话中 ${LINK_DEAD_MS / 1000} 秒没收到服务器回音") }
                return
            }
            send(JSONObject().put("type", "ping"))
            handler.postDelayed(this, if (inCall) PING_CALL_MS else PING_IDLE_MS)
        }
    }

    // 0.48 查岗上报：替代 MacroDroid（免费天数到期停了）。每分钟看一眼前台 app，换了才 POST 一条到 8400，
    // 格式和 MacroDroid 一样（{"app": 中文名}，服务端打时间戳、同名去重），VPS 侧一行不用改。
    @Volatile private var lastReportedApp: String? = null
    private var usageTick = 0
    private val usageRunnable = object : Runnable {
        override fun run() {
            try {
                reportForegroundApp()
                if (++usageTick % 10 == 0) postHeartbeat()
            } catch (e: Exception) {
                usageStatus.postValue("异常:${e.javaClass.simpleName}")
            }
            handler.postDelayed(this, 60_000)
        }
    }

    // 0.55 心跳：每 10 分钟给 8400 发一个空 /hb，服务端只刷新"最后一次听到手机"的时间，不进记录。
    // 没有它，服务被杀和她一晚没碰手机在 VPS 侧长得一模一样（0914 17:17 起断报，四小时后才从群聊反推出来）。
    // 主页那行显示的 ✓ 后面带上时间，她也能一眼看出上报是不是还活着
    private fun postHeartbeat() {
        val req = Request.Builder().url("http://${BuildConfig.SERVER_HOST}:8400/hb").post("".toRequestBody(null)).build()
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                usageStatus.postValue("失败:${e.javaClass.simpleName}")
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                val code = response.code; response.close()
                usageStatus.postValue(if (code in 200..299) "✓${hhmm()}" else "被拒HTTP$code")
            }
        })
    }
    private fun hhmm(): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())

    // 0.49：没权限不能静默——0.48 她装完什么提示都没有，抓包四分钟零请求才知道卡在这。每次服务启动最多提醒一次
    @Volatile private var usagePermNotified = false
    private fun notifyUsagePermission() {
        val pi = PendingIntent.getActivity(
            this, 3, Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("查岗上报还没开")
            .setContentText("点这里→找到「辰来电」→打开「使用情况访问」")
            .setStyle(NotificationCompat.BigTextStyle().bigText("替代 MacroDroid 的上报需要这个权限。点这里→列表里找到「辰来电」→打开「使用情况访问」，开完就自动上报了，不用再管。"))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build()
        nm().notify(NOTIF_USAGE, n)
    }

    private fun reportForegroundApp() {
        if (!hasUsagePermission(this)) {
            // 0.51：把系统返回的原始 mode 打出来——OPPO 到底回了什么，一眼可见
            val ops = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            usageStatus.postValue("未授权(mode=$mode)")
            if (!usagePermNotified) { usagePermNotified = true; notifyUsagePermission() }
            return
        }
        nm().cancel(NOTIF_USAGE)
        val usm = getSystemService(USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val now = System.currentTimeMillis()
        // 0.50：窗口从 2 分钟放到 6 小时——她一锁屏 2 分钟内就没事件，第一条永远发不出去（0914 实测）；取窗口内最后一个前台即可
        val events = usm.queryEvents(now - 6 * 3600_000L, now)
        val ev = android.app.usage.UsageEvents.Event()
        var pkg: String? = null
        var ts = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED && ev.timeStamp >= ts) {
                ts = ev.timeStamp; pkg = ev.packageName
            }
        }
        val p = pkg ?: run { usageStatus.postValue("无事件"); return }
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { p.substringAfterLast('.') }
        postApp(label)
    }

    private fun postApp(label: String) {
        if (label == lastReportedApp) return
        val body = JSONObject().put("app", label).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("http://${BuildConfig.SERVER_HOST}:8400/report").post(body).build()
        client.newCall(req).enqueue(object : okhttp3.Callback {
            // 0.50：失败不再静默——写到主页字幕区，她一眼能看到卡在哪
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                usageStatus.postValue("失败:${e.javaClass.simpleName}")
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                val code = response.code; response.close()
                if (code in 200..299) {
                    usageStatus.postValue("✓${hhmm()}")
                    lastReportedApp = label
                } else usageStatus.postValue("被拒HTTP$code")
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 0.114 一起听：点歌/切歌指令的订阅挂在常驻服务上，没给通知使用权时「辰点了一首歌」的通知也能收到（分身 B 的建议）
        me.chen.laidian.music.MusicCommands.start(this, "service")
        createChannels()
        // 0.88 玩具页：把 ws 交给它，收到辰的指令走 onRemote，状态回传给服务端
        ToyController.init(this) { send(it) }
        client = Tls.client(this)
        // 0.115 onSpeaker：通话中途戴上耳机引擎替她关掉免提 / 耳机都摘了还原时，通话页的免提钮跟着变
        audio = AudioEngine(this, client, { send(it) }, { sttStatus.postValue(it) }, onSpeaker = { speakerOn.postValue(it) })
        me.chen.laidian.net.ChatClient.start(applicationContext)
        // 0926 工具行（"读 memory.md"之类）不弹通知——那不是辰说的话；思考行照旧
        me.chen.laidian.net.ChatClient.onMessage = { m -> if (m.isChen && !AppState.visible && m.msgType != "tool") notifyMsg(m) }
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // 0.54 息屏也当一条上报（切断前一个 app 的计时——0914 查岗里锁屏时段全算给了前一个 app），亮屏立刻重报当前 app
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> postApp("锁屏")
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    lastReportedApp = null
                    handler.postDelayed({ try { reportForegroundApp() } catch (_: Exception) {} }, 1500)
                }
            }
        }
    }

    private fun notifyMsg(m: me.chen.laidian.model.Msg) {
        val body = when {
            m.text.isNotBlank() -> m.text
            m.msgType == "image" || m.msgType == "images" -> "[图片]"
            m.msgType == "voice" -> "[语音]"
            else -> "[消息]"
        }
        val pi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("辰").setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true).setContentIntent(pi).build()
        nm().notify(1000 + (m.id.hashCode() and 0xffff), n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                ws?.close(1000, "bye"); ws = null
                handler.removeCallbacksAndMessages(null)
                setStatus("已下线")
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST_CALL -> { showIncomingCall("测试来电（本地）"); return START_STICKY }
            ACTION_ACCEPT -> { acceptCall(); return START_STICKY }
            ACTION_HANGUP -> {
                // 0.126 先停录音（断线时存着的话不再变），存话 + 挂断一起交出去；线断着就记下，连上再发
                val left = if (inCall) { audio.endCall(); audio.takeBacklog() } else emptyList()
                hangupFromHere(left)
                endCall("已挂断")
                return START_STICKY
            }
            ACTION_SPEAKER -> {
                if (inCall) { audio.setSpeaker(!audio.isSpeaker()); speakerOn.postValue(audio.isSpeaker()) }
                return START_STICKY
            }
            ACTION_MUTE -> {
                if (inCall) { audio.userMuted = !audio.userMuted; micMuted.postValue(audio.userMuted) }
                return START_STICKY
            }
            ACTION_REGAIN -> { if (inCall) audio.regainFocus(); return START_STICKY }
        }
        running = true
        connect()
        KeepAliveReceiver.schedule(this)
        handler.removeCallbacks(usageRunnable)
        handler.postDelayed(usageRunnable, 5_000)
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        // 被系统杀了也让闹钟把我们拉回来（ACTION_STOP 主动下线的路径里 running 早已置 false）
        if (running) KeepAliveReceiver.schedule(this)
        running = false
        if (inCall) endCall("已挂断")
        handler.removeCallbacksAndMessages(null)
        ws?.close(1000, "destroy"); ws = null
        super.onDestroy()
    }

    // ---------- WebSocket ----------

    /** 只在主线程上调（onStartCommand / reconnect / acceptCall） */
    private fun connect() {
        if (!running || ws != null) return
        handler.removeCallbacks(reconnect)
        setStatus("连接中…")
        authed = false
        audio.resetStream()   // 0.113 新连接从干净开始：流式先关、手里的 sid 作废，等这条连接自己的 hello
        val url = "wss://${BuildConfig.SERVER_HOST}:${BuildConfig.SERVER_PORT}/ws"
        // 0.126 回调都先认是不是当前这条：看门狗/死线检测掐掉的旧连接，迟到的 onFailure 不能把新连接当成断了
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("token", BuildConfig.WS_TOKEN).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return
                lastRxAt = android.os.SystemClock.elapsedRealtime()
                handleMessage(webSocket, text)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post { if (webSocket === ws) onDown("连接关闭") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post { if (webSocket === ws) onDown("断线：${t.message ?: t.javaClass.simpleName}") }
            }
        })
        // 0.126 看门狗：CONNECT_TIMEOUT_MS 内没 auth 就掐掉重来（以前一次连接卡住，ws 一直不为空，connect() 再也进不来）
        handler.removeCallbacks(connectWatch)
        handler.postDelayed(connectWatch, CONNECT_TIMEOUT_MS)
    }

    /** 0.126 起只在主线程上调 */
    private fun onDown(why: String) {
        val wasUp = authed
        ws = null
        authed = false
        handler.removeCallbacks(connectWatch)
        audio.resetStream()   // 0.113 断了：正在传的那句服务端自己收尾，本地不补发；重连后等新 hello 再流式
        audio.onLinkDown()    // 0.126 通话中状态行换成「线断了，正在重连…」，之后说完的整句先存着
        handler.removeCallbacks(pingRunnable)
        // 0.126 通话中：通话页标题换成「线断了」；从通着变成断了的那一下短震两下（每次重连失败不再震）
        if (inCall && callLink.value != "down") {
            handler.removeCallbacks(clearBack)
            callLink.value = "down"
            if (wasUp) buzz(longArrayOf(0, 120, 100, 120))
        }
        if (!running) return
        // 0.126 通话中退避封顶 10 秒（1/2/4/8/10/10…）；挂断后回到空闲的封顶 60 秒——服务还得连着等辰来电，不是不连了
        val cap = if (inCall) CALL_BACKOFF_MAX_MS else 60_000L
        val wait = min(backoffMs, cap)
        setStatus("$why，${wait / 1000}s 后重连")
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, wait)
        backoffMs = min(backoffMs * 2, cap)
    }

    /** 0.126 收到 auth（主线程）：服务端认了这条连接 */
    private fun onAuthed(webSocket: WebSocket) {
        if (webSocket !== ws) return
        authed = true
        lastRxAt = android.os.SystemClock.elapsedRealtime()
        handler.removeCallbacks(connectWatch)
        backoffMs = 1000L
        setStatus("辰在线")
        ToyController.pushStatus()   // 0.89 重连后把玩具状态报一遍 辰那边不用等下一次变化
        handler.removeCallbacks(pingRunnable)
        handler.postDelayed(pingRunnable, if (inCall) PING_CALL_MS else PING_IDLE_MS)
        // 0.126 断线时按的挂断：存话（老的整句 audio，服务端 worker 按顺序先认完再处理挂断）+ 挂断。
        // 重连本身什么通话事件都不发：服务端只在收到 call_accept/hangup 时写 [电话接通]/[电话挂断]，auth/hello 不写
        if (pendingHangup) {
            pendingHangup = false
            val left = pendingLeft
            pendingLeft = emptyList()
            if (System.currentTimeMillis() - pendingLeftAt < LEFT_MAX_AGE_MS) left.forEach { send(AudioEngine.heldAsAudio(it)) }
            send(JSONObject().put("type", "hangup"))
            if (inCall) {
                // 挂了又拨了一通（线一直没通）：服务端处理完挂断会关掉这条连接，新一通的接通和声音等下一条连接再发。
                // 万一 20 秒还没关，自己关
                val hw = webSocket
                handler.postDelayed({ if (ws === hw) hw.close(1000, "after hangup") }, 20_000)
                return
            }
        }
        if (pendingAccept && inCall && send(JSONObject().put("type", "call_accept"))) pendingAccept = false
        audio.onLinkUp()   // 0.126 断线时存的话由录音线程等到 hello 后按顺序补发
        if (inCall && callLink.value == "down") {
            callLink.value = "back"
            buzz(longArrayOf(0, 60))
            handler.removeCallbacks(clearBack)
            handler.postDelayed(clearBack, 2500)
        }
    }

    private fun send(o: JSONObject): Boolean = ws?.send(o.toString()) ?: false

    /** 0.126 她这边按的挂断（主线程）。left = 断线时存着、还没交出去的话 */
    private fun hangupFromHere(left: List<AudioEngine.Held>) {
        // 这一通的接通压根没送到服务端（拨的时候线就断着，一直没连上）：服务端不知道有过这通，挂断也不发，存话一起作罢
        if (pendingAccept) { pendingAccept = false; return }
        if (authed) {
            left.forEach { send(AudioEngine.heldAsAudio(it)) }
            if (send(JSONObject().put("type", "hangup"))) return
            pendingHangup = true; pendingLeft = emptyList()   // 存话刚才已经发过了，不再补
            return
        }
        pendingHangup = true
        pendingLeft = left
        pendingLeftAt = System.currentTimeMillis()
    }

    /** 0.126 短震（照来电页的取法：12+ 走 VibratorManager） */
    private fun buzz(pattern: LongArray) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
            }
            v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        when (o.optString("type")) {
            // 0.126 挪到主线程（onAuthed）：连接状态的改动都在主线程上做，跟看门狗/onDown 不打架
            "auth" -> handler.post { onAuthed(webSocket) }
            "pong" -> {}
            "status" -> sttStatus.postValue(o.optString("message"))
            "stt" -> lastText.postValue("你：" + o.optString("text"))
            // 0.113 流式识别：hello 说这条连接能边听边认；半句字只挂状态行，不进字幕
            "hello" -> audio.onHello(o.optBoolean("stream_stt", false))
            "stt_partial" -> audio.onSttPartial(o.optString("sid"), if (o.isNull("text")) "" else o.optString("text"))
            // 0.114 她 0929 电话里报的："我说的话只在聊天页显示，通话页没同步"——流式路径服务端收尾只发 stt_final 不发 stt，
            // 字幕一直空着。stt_final 带字就照 stt 一样上字幕（服务端 0929 起也补发 stt，重复一次同样的字无害）
            "stt_final" -> {
                audio.onSttFinal(o.optString("sid"))
                val t = if (o.isNull("text")) "" else o.optString("text")
                if (t.isNotBlank()) lastText.postValue("你：" + t)
            }
            "reply", "audio_reply" -> {
                lastText.postValue("辰：" + o.optString("text"))
                // 0926 她截图的 "Invalid URL port: 8200null"：配音超时时服务端发 audio_url: null，
                // optString 把 JSON null 读成字符串 "null" 拼进了地址。空/null/不是路径的一律不下载。
                val url = if (o.isNull("audio_url")) "" else o.optString("audio_url", "")
                if (inCall && url.startsWith("/")) audio.enqueueReply(url)
                else if (inCall && o.optBoolean("tts_failed", false)) setStatus("这条没配上音，字在上面")
            }
            "incoming_call" -> showIncomingCall(o.optString("text", "辰打电话来了"))
            "hangup" -> endCall("已挂断")
            "toy" -> ToyController.onRemote(o)
            "error" -> setStatus("错误：" + o.optString("message"))
        }
    }

    // ---------- 通知 / 前台 ----------

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        nm().createNotificationChannel(NotificationChannel(CH_SERVICE, "辰在线", NotificationManager.IMPORTANCE_LOW))
        nm().createNotificationChannel(NotificationChannel(CH_MSG, "辰的消息", NotificationManager.IMPORTANCE_HIGH))
        nm().createNotificationChannel(
            NotificationChannel(CH_CALL, "辰来电", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)   // 铃声由 CallActivity 自己放
                enableVibration(true)
            }
        )
    }

    private fun serviceNotif(text: String) = NotificationCompat.Builder(this, CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("辰")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun ensureForeground() {
        if (foregroundStarted) return
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_SERVICE, serviceNotif(status.value ?: "启动中…"), type)
        foregroundStarted = true
    }

    private fun setStatus(text: String) {
        status.postValue(text)
        handler.post { if (foregroundStarted) nm().notify(NOTIF_SERVICE, serviceNotif(text)) }
    }

    private fun showIncomingCall(text: String) {
        callState.postValue("响铃中")
        val intent = Intent(this, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("text", text)
        val pi = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CH_CALL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("辰来电")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        nm().notify(NOTIF_CALL, n)
        // app 在前台时直接弹；在后台时系统会拦下这次 startActivity，靠上面的 fullScreenIntent 亮屏
        try { startActivity(intent) } catch (e: Exception) {}
    }

    private fun cancelCallNotif() = nm().cancel(NOTIF_CALL)

    // ---------- 通话 ----------

    private fun hasMic() = androidx.core.content.ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun acceptCall() {
        cancelCallNotif()
        if (!running) { running = true; connect() }
        // 0.126 只在认证过的连接上发；线断着/还在连就先记下，连上（onAuthed）再发。
        // （以前 ws=null 时直接丢了，服务端不写 [电话接通]；还在连时排在 token 前面，靠服务端 pre_auth 重放）
        pendingAccept = !(authed && send(JSONObject().put("type", "call_accept")))
        callLinkStart()
        if (!hasMic()) {
            callState.postValue("通话中")
            lastText.postValue("没有麦克风权限，只能听不能说")
            inCall = true
            return
        }
        // 通话期间前台服务类型加上麦克风（Android 14 强制）
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIF_SERVICE, serviceNotif("通话中"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        }
        inCall = true
        callStartTs = System.currentTimeMillis()   // 0.34 悬浮小窗的时长起点
        callState.postValue("通话中")
        speakerOn.postValue(false)
        micMuted.postValue(false)
        audio.startCall()
        audio.userMuted = false
    }

    /** 0.126 开一通时（主线程）：线断着就马上重连（别等空闲时最长 60 秒的退避），通话页一上来就说线断了；连着就把 ping 换成 5 秒一个 */
    private fun callLinkStart() {
        // 空闲时 30 秒才一个 pong，lastRxAt 不能沿用（不然一开通话就被判成死线）；但也不给满 15 秒：
        // 空闲时悄悄死掉的连接（换网络时 OkHttp 的 25 秒协议 ping 还没轮到）要尽快认出来——当场发个 ping，
        // 服务端对 call_accept / ping 都是马上回的，开场 ~10 秒内什么都没收到就当死线重连
        lastRxAt = android.os.SystemClock.elapsedRealtime() - (LINK_DEAD_MS - 6_000L)
        backoffMs = 1000L
        handler.removeCallbacks(clearBack)
        callLink.value = if (authed) "" else "down"
        if (ws == null) {
            connect()   // 里面会撤掉还在等的 reconnect
        } else if (authed) {
            handler.removeCallbacks(pingRunnable)
            handler.post(pingRunnable)
        }
    }

    private fun endCall(finalState: String) {
        cancelCallNotif()
        callLink.postValue("")   // 0.126 可能在 ws 线程上（辰挂断）
        if (inCall) {
            inCall = false
            audio.endCall()
            if (Build.VERSION.SDK_INT >= 29 && foregroundStarted) {
                ServiceCompat.startForeground(
                    this, NOTIF_SERVICE, serviceNotif(status.value ?: "辰在线"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
        }
        callState.postValue(finalState)
        callStartTs = 0L
        android.os.Handler(mainLooper).post {
            FloatCall.hide()   // 0.34 通话结束小窗必须消失(Activity不在时兜底)
            CallActivity.finishLive()   // 0929 她报的：重打直接缩成小窗——通话页退在后台时收不到「已挂断」不会自己关，这里直接关，不留给下一通复用
        }
    }
}
