package me.chen.laidian.music

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.chen.laidian.Tls
import me.chen.laidian.net.ChatClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 0929 一起听：把「正在播放」POST 到 {ChatClient.baseUrl()}/music（X-Token，Tls.client + 15s 连接超时，后台线程）。
 * 什么时候发：歌变了 / 播放暂停停止变了 / 进度跳变（新进度和"上次进度+经过时间"外推差超过 3 秒 = 拖动了）/ 播放中每 5 分钟心跳 / 她按「现在发一次」。
 * 500ms 防抖合并（切歌时 metadata 和 playbackState 分两次到，合成一条）。失败只留最新一条：30 秒后重试，聊天 ws 重连上也立刻补发。
 * 开关（SharedPreferences listen/report，默认开）关着时什么都不发；关的那一刻发最后一条 state=none，辰知道听不见了，不会对着旧歌自言自语。
 * 所有状态改动都在 music-reporter 这条 HandlerThread 上做，不加锁。
 */
object MusicReporter {
    private const val TOKEN = "chen_home_2026"
    private const val PREF = "listen"
    private const val KEY_ON = "report"
    private const val DEBOUNCE_MS = 500L
    private const val CMD_DEBOUNCE_MS = 1500L      // 指令回执多等一会：next/pause 之后真正的 song/state 事件 1 秒内会到，合成一条
    private const val HEARTBEAT_MS = 5 * 60_000L
    private const val RETRY_MS = 30_000L
    private const val SEEK_JUMP_MS = 3000L
    /** 合并时 reason 取最靠前的那个 */
    private val REASON_ORDER = listOf("song", "state", "seek", "manual", "heartbeat")
    private val JSON = "application/json".toMediaType()

    /** 开关：让辰听见我在听什么 */
    val enabled = MutableStateFlow(true)
    /** 上次成功发出去的时刻（epoch ms；0 = 还没发过）和原因 */
    val lastSentAt = MutableStateFlow(0L)
    val lastReason = MutableStateFlow("")
    /** 最近一次失败原因（"" = 没错）。照 AppUpdater.lastError 的路子：给她看得见的字，别只剩"失败" */
    val lastError = MutableStateFlow("")

    /** 一次要发的东西：快照只留最新，reason 合并，extras / last_cmd 各留一份 */
    private class Batch(val snap: Snapshot, val reasons: Set<String>, val extras: Map<String, String>?, val cmd: JSONObject?)

    private var app: Context? = null
    private var http: OkHttpClient? = null
    private val thread = HandlerThread("music-reporter").apply { start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var inited = false

    /** 上一条看过的快照：判断歌变 / 状态变 / 拖动的基准（每条都更新，不管发没发） */
    private var last: Snapshot? = null
    private var pending: Batch? = null
    private var inFlight = false

    private val flushRunnable = Runnable { flush() }
    private val retryRunnable = Runnable { if (pending != null) flush() }
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val s = NowPlaying.current()
            if (s.state == "playing" && enabled.value && pending == null && !inFlight) enqueue(s.extrapolated(System.currentTimeMillis()), "heartbeat", DEBOUNCE_MS)
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        val a = ctx.applicationContext
        app = a
        // Tls.client 的 readTimeout 是 0（给 ws 长连用）；上报要能超时，不然梯子一断就卡死
        http = Tls.client(a).newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        enabled.value = try { a.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ON, true) } catch (_: Exception) { true }
        // 聊天 ws 重连上 = 网络回来了：有没发出去的就补发
        scope.launch {
            ChatClient.connected.collect { up -> if (up) handler.post { if (pending != null && !inFlight) flush() } }
        }
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)
    }

    fun isEnabled(ctx: Context): Boolean { init(ctx); return enabled.value }

    fun setEnabled(ctx: Context, on: Boolean) {
        init(ctx)
        try { ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply() } catch (_: Exception) {}
        if (on) {
            enabled.value = true
            sendNow()
        } else {
            // 先告别再关门：最后一条 state=none（不带歌名），辰那边知道听不见了
            handler.post {
                pending = null
                handler.removeCallbacks(flushRunnable)
                send(Batch(Snapshot(at = System.currentTimeMillis()), setOf("manual"), null, null))
            }
            enabled.value = false
        }
    }

    /** NowPlaying 每次变化都调（主线程）；这里只排队，判断放到 reporter 线程 */
    fun onSnapshot(s: Snapshot) {
        handler.post {
            val reason = classify(s)
            last = s
            if (reason != null) enqueue(s, reason, DEBOUNCE_MS)
        }
    }

    /** 她按「现在发一次」/ 开关打开：强制发当前状态。开关关着返回 false（不发） */
    fun sendNow(): Boolean {
        if (!enabled.value) return false
        handler.post { enqueue(NowPlaying.current().extrapolated(System.currentTimeMillis()), "manual", 0L) }
        return true
    }

    /** 辰的指令执行结果：挂在下一条上报的 last_cmd 里（多等 1.5 秒，让 next/pause 引起的真事件合进来；没有事件就单独发一条 manual） */
    fun reportCmd(action: String, ok: Boolean, err: String) {
        handler.post {
            val cmd = JSONObject().put("action", action).put("ok", ok).put("err", err)
            val snap = NowPlaying.current().extrapolated(System.currentTimeMillis())
            pending = merge(pending, Batch(snap, setOf("manual"), null, cmd))
            handler.removeCallbacks(flushRunnable)
            handler.postDelayed(flushRunnable, CMD_DEBOUNCE_MS)
        }
    }

    // ---------- 以下都在 reporter 线程 ----------

    /** 和上一条比：歌变 / 状态变 / 拖动。都没变返回 null */
    private fun classify(s: Snapshot): String? {
        val p = last ?: return if (s.state == "none") null else "song"
        if (s.songKey != p.songKey) return if (s.songKey.isEmpty() || s.title.isBlank()) "state" else "song"
        if (s.state != p.state) return "state"
        if (s.state == "none") return null
        // 播放中：按上次进度 + 经过时间外推；暂停中：直接比进度（暂停时拖动也算）
        val expected = p.positionAt(s.at)
        return if (abs(s.positionMs - expected) > SEEK_JUMP_MS) "seek" else null
    }

    private fun merge(old: Batch?, new: Batch): Batch =
        if (old == null) new else Batch(new.snap, old.reasons + new.reasons, new.extras ?: old.extras, new.cmd ?: old.cmd)

    private fun enqueue(s: Snapshot, reason: String, delayMs: Long) {
        pending = merge(pending, Batch(s, setOf(reason), s.extras, null))
        handler.removeCallbacks(flushRunnable)
        handler.postDelayed(flushRunnable, delayMs)
    }

    private fun flush() {
        val b = pending ?: return
        if (!enabled.value) { pending = null; return }
        if (inFlight) return   // 发完会看 pending 还有没有东西
        pending = null
        send(b)
    }

    private fun send(b: Batch) {
        val client = http ?: run { lastError.value = "还没初始化"; pending = pending?.let { merge(b, it) } ?: b; return }
        val reason = REASON_ORDER.firstOrNull { it in b.reasons } ?: "state"
        val body = b.snap.toJson()
            .put("ts", System.currentTimeMillis() / 1000.0)
            .put("reason", reason)
        b.extras?.let { body.put("extras", JSONObject(it)) }
        b.cmd?.let { body.put("last_cmd", it) }
        val req = Request.Builder().url(ChatClient.baseUrl() + "/music").header("X-Token", TOKEN)
            .post(body.toString().toRequestBody(JSON)).build()
        inFlight = true
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { failed(b, "${e.javaClass.simpleName}${e.message?.let { ": " + it.take(60) } ?: ""}") }
            }
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                response.close()
                handler.post { if (code in 200..299) succeeded(reason) else failed(b, "HTTP $code") }
            }
        })
    }

    private fun succeeded(reason: String) {
        inFlight = false
        lastSentAt.value = System.currentTimeMillis()
        lastReason.value = reason
        lastError.value = ""
        handler.removeCallbacks(retryRunnable)
        // 心跳从上一次成功上报起算 5 分钟
        handler.removeCallbacks(heartbeatRunnable)
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)
        if (pending != null) { handler.removeCallbacks(flushRunnable); handler.postDelayed(flushRunnable, DEBOUNCE_MS) }
    }

    private fun failed(b: Batch, why: String) {
        inFlight = false
        lastError.value = why
        // 失败只留最新一条：发失败的这条和期间新来的合并，新快照优先，extras / last_cmd 不丢
        pending = pending?.let { merge(b, it) } ?: b
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, RETRY_MS)
    }
}
