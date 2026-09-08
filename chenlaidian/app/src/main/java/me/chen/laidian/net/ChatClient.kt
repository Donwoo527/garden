package me.chen.laidian.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import me.chen.laidian.BuildConfig
import me.chen.laidian.Tls
import me.chen.laidian.model.Msg
import me.chen.laidian.model.Moment
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.min

/**
 * 聊天后端（chat_server.py 8300 的 /ws）的原生客户端。
 * 协议与网页版 index.html 一致：连上先发 {"token"}，收 auth/history/msg/status/profile/session_status/user_profile。
 */
object ChatClient {
    private const val LIMIT = 50
    private const val TOKEN = "chen_home_2026"

    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val connected = MutableStateFlow(false)
    val sessionAlive = MutableStateFlow(false)
    val status = MutableStateFlow("idle")       // idle | thinking
    val mood = MutableStateFlow("")
    val signature = MutableStateFlow("")
    val myMood = MutableStateFlow("")
    val mySignature = MutableStateFlow("")
    val moments = MutableStateFlow<List<Moment>>(emptyList())
    val momentsUnread = MutableStateFlow(0)
    /** 新消息回调（服务用它在 app 不在前台时弹通知） */
    @Volatile var onMessage: ((Msg) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var client: OkHttpClient? = null
    @Volatile private var ws: WebSocket? = null
    private var started = false
    private var backoffMs = 1000L
    @Volatile private var loading = false
    @Volatile private var noMore = false
    private var typingState = false

    fun baseUrl() = "https://${BuildConfig.SERVER_HOST}:${BuildConfig.CHAT_PORT}"
    fun mediaUrl(path: String) = if (path.startsWith("http")) path else baseUrl() + path

    fun start(context: Context) {
        if (started) return
        started = true
        client = Tls.client(context.applicationContext)
        connect()
    }

    private fun connect() {
        if (ws != null) return
        val c = client ?: return
        val req = Request.Builder().url("wss://${BuildConfig.SERVER_HOST}:${BuildConfig.CHAT_PORT}/ws").build()
        ws = c.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("token", TOKEN).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = down()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = down()
        })
    }

    private fun down() {
        ws = null
        connected.value = false
        val wait = backoffMs
        backoffMs = min(backoffMs * 2, 30_000L)
        handler.postDelayed({ connect() }, wait)
    }

    private fun handle(text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        when (o.optString("type")) {
            "auth" -> { connected.value = true; backoffMs = 1000L }
            "history" -> {
                val list = Msg.list(o.optJSONArray("items"))
                if (o.optBoolean("initial")) {
                    messages.value = list.sortedBy { it.ts }
                    noMore = false
                } else {
                    loading = false
                    if (list.size < LIMIT) noMore = true
                    val have = messages.value.map { it.id }.toHashSet()
                    messages.value = (list.filter { it.id !in have } + messages.value).sortedBy { it.ts }
                }
            }
            "msg" -> {
                val m = Msg.from(o)
                if (m.id.isNotEmpty() && messages.value.none { it.id == m.id }) {
                    messages.value = messages.value + m
                    onMessage?.invoke(m)
                }
            }
            "status" -> status.value = o.optString("state", "idle")
            "session_status" -> sessionAlive.value = o.optBoolean("alive", false)
            "profile" -> {
                o.optString("mood", "").takeIf { it.isNotBlank() }?.let { mood.value = it }
                o.optString("signature", "").takeIf { it.isNotBlank() }?.let { signature.value = it }
            }
            "user_profile" -> {
                myMood.value = o.optString("mood", "")
                mySignature.value = o.optString("signature", "")
            }
            "moment" -> o.optJSONObject("item")?.let { m ->
                val it = Moment.from(m)
                moments.value = listOf(it) + moments.value.filter { x -> x.id != it.id }
            }
            "moment_update" -> o.optJSONObject("item")?.let { m ->
                val it = Moment.from(m)
                moments.value = moments.value.map { x -> if (x.id == it.id) it else x }
            }
            "moment_delete" -> { val id = o.optString("id"); moments.value = moments.value.filter { it.id != id } }
            "moments_unread" -> momentsUnread.value = o.optInt("count", 0)
        }
    }

    fun sendText(text: String, replyTo: String? = null): Boolean {
        val o = JSONObject().put("type", "text").put("text", text)
        if (!replyTo.isNullOrBlank()) o.put("reply_to", replyTo)
        typing(false)
        return ws?.send(o.toString()) ?: false
    }

    fun loadMore() {
        if (loading || noMore) return
        val first = messages.value.firstOrNull() ?: return
        loading = true
        ws?.send(JSONObject().put("type", "history").put("before", first.ts).put("limit", LIMIT).toString())
    }

    fun typing(state: Boolean) {
        if (state == typingState) return
        typingState = state
        ws?.send(JSONObject().put("type", "typing").put("state", state).toString())
    }

    fun poke() {
        ws?.send(JSONObject().put("type", "poke").toString())
    }
}
