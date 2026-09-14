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
        /** 空闲 / 响铃中 / 通话中 / 已挂断 */
        val callState = MutableLiveData("空闲")
        val speakerOn = MutableLiveData(false)
        @Volatile var running = false
    }

    private lateinit var client: OkHttpClient
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var ws: WebSocket? = null
    private var backoffMs = 1000L
    private var foregroundStarted = false
    private lateinit var audio: AudioEngine
    @Volatile private var inCall = false

    private val pingRunnable = object : Runnable {
        override fun run() {
            send(JSONObject().put("type", "ping"))
            handler.postDelayed(this, 30_000)
        }
    }

    // 0.48 查岗上报：替代 MacroDroid（免费天数到期停了）。每分钟看一眼前台 app，换了才 POST 一条到 8400，
    // 格式和 MacroDroid 一样（{"app": 中文名}，服务端打时间戳、同名去重），VPS 侧一行不用改。
    @Volatile private var lastReportedApp: String? = null
    private val usageRunnable = object : Runnable {
        override fun run() {
            try { reportForegroundApp() } catch (e: Exception) {
                usageStatus.postValue("异常:${e.javaClass.simpleName}")
            }
            handler.postDelayed(this, 60_000)
        }
    }

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
                    usageStatus.postValue("✓")
                    lastReportedApp = label
                } else usageStatus.postValue("被拒HTTP$code")
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        client = Tls.client(this)
        audio = AudioEngine(this, client, { send(it) }, { sttStatus.postValue(it) })
        me.chen.laidian.net.ChatClient.start(applicationContext)
        me.chen.laidian.net.ChatClient.onMessage = { m -> if (m.isChen && !AppState.visible) notifyMsg(m) }
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
            ACTION_HANGUP -> { send(JSONObject().put("type", "hangup")); endCall("已挂断"); return START_STICKY }
            ACTION_SPEAKER -> {
                if (inCall) { audio.setSpeaker(!audio.isSpeaker()); speakerOn.postValue(audio.isSpeaker()) }
                return START_STICKY
            }
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

    private fun connect() {
        if (!running || ws != null) return
        setStatus("连接中…")
        val url = "wss://${BuildConfig.SERVER_HOST}:${BuildConfig.SERVER_PORT}/ws"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("token", BuildConfig.WS_TOKEN).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDown("连接关闭")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                onDown("断线：${t.message ?: t.javaClass.simpleName}")
        })
    }

    private fun onDown(why: String) {
        ws = null
        handler.removeCallbacks(pingRunnable)
        if (!running) return
        val wait = backoffMs
        setStatus("$why，${wait / 1000}s 后重连")
        handler.postDelayed({ connect() }, wait)
        backoffMs = min(backoffMs * 2, 60_000L)
    }

    private fun send(o: JSONObject): Boolean = ws?.send(o.toString()) ?: false

    private fun handleMessage(text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        when (o.optString("type")) {
            "auth" -> {
                backoffMs = 1000L
                setStatus("辰在线")
                handler.removeCallbacks(pingRunnable)
                handler.postDelayed(pingRunnable, 30_000)
            }
            "pong" -> {}
            "status" -> sttStatus.postValue(o.optString("message"))
            "stt" -> lastText.postValue("你：" + o.optString("text"))
            "reply", "audio_reply" -> {
                lastText.postValue("辰：" + o.optString("text"))
                val url = o.optString("audio_url", "")
                if (inCall && url.isNotEmpty()) audio.enqueueReply(url)
            }
            "incoming_call" -> showIncomingCall(o.optString("text", "辰打电话来了"))
            "hangup" -> endCall("已挂断")
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
        send(JSONObject().put("type", "call_accept"))
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
        audio.startCall()
    }

    private fun endCall(finalState: String) {
        cancelCallNotif()
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
        android.os.Handler(mainLooper).post { FloatCall.hide() }   // 0.34 通话结束小窗必须消失(Activity不在时兜底)
    }
}
