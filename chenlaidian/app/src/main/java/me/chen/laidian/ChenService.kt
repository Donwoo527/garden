package me.chen.laidian

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import okhttp3.OkHttpClient
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

        val status = MutableLiveData("未启动")
        val lastText = MutableLiveData("")
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        client = Tls.client(this)
        audio = AudioEngine(this, client, { send(it) }, { lastText.postValue(it) })
        me.chen.laidian.net.ChatClient.start(applicationContext)
        me.chen.laidian.net.ChatClient.onMessage = { m -> if (m.isChen && !AppState.visible) notifyMsg(m) }
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
        return START_STICKY
    }

    override fun onDestroy() {
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
            "status" -> lastText.postValue(o.optString("message"))
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
    }
}
