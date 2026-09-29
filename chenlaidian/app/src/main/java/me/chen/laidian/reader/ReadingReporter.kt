package me.chen.laidian.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.chen.laidian.Tls
import me.chen.laidian.net.ChatClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 0929 阅读事件上报：POST {baseUrl}/reading（X-Token），body 见三个分身共用的接口约定：
 *   {"event":"open|page|jump|close","book","book_id","format","chapter","cfi","fraction","dir","dwell_s","text","ts"}
 * 页面把除 dwell_s / ts 之外的字段填好交过来，这里补上这两个再发。
 *  - dwell_s = 上一次 relocate 到这一次的间隔秒数（超过 30 分钟按 0：她走开了）；open 一律 0
 *  - 离线/非 2xx 都入队（内存 + filesDir/reading_queue.jsonl，最多 200 条，满了丢最老的），连上后按顺序补发
 *  - 快速连翻也照发，节流服务端做
 */
object ReadingReporter {
    private const val TAG = "ReadingReporter"
    private const val TOKEN = "chen_home_2026"
    private const val MAX = 200
    private const val QUEUE_FILE = "reading_queue.jsonl"
    private const val AWAY_MS = 30 * 60_000L
    private const val RETRY_MS = 30_000L

    private val queue = ArrayDeque<JSONObject>()
    private val lock = Any()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "reading-reporter").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var loaded = false
    @Volatile private var watching = false
    @Volatile private var retryScheduled = false
    @Volatile private var lastEventAt = 0L
    /** 最近一次失败原因（给 Toast/日志） */
    @Volatile var lastError: String? = null

    private fun http(app: Context): OkHttpClient = client ?: Tls.client(app).newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build().also { client = it }

    /** 阅读页进来时调一次：把上次没发出去的补发；聊天 ws 一连上也补发 */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        flush(app)
        if (watching) return
        watching = true
        scope.launch { ChatClient.connected.collect { if (it) flush(app) } }
    }

    /** 页面调（主线程即可，不阻塞）。body 里 event/book/book_id/format/chapter/cfi/fraction/dir/text 由页面填 */
    fun report(ctx: Context, body: JSONObject) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val event = body.optString("event")
        val dwell = when {
            event == "open" || lastEventAt == 0L -> 0L
            now - lastEventAt > AWAY_MS -> 0L
            else -> (now - lastEventAt) / 1000
        }
        lastEventAt = if (event == "close") 0L else now
        body.put("dwell_s", dwell)
        body.put("ts", now / 1000.0)
        if (body.optString("text").length > 4000) body.put("text", body.optString("text").take(4000))
        exec.execute {
            ensureLoaded(app)
            synchronized(lock) {
                queue.addLast(body)
                while (queue.size > MAX) queue.removeFirst()
                persist(app)
            }
            drain(app)
        }
    }

    fun flush(ctx: Context) {
        val app = ctx.applicationContext
        exec.execute { ensureLoaded(app); drain(app) }
    }

    // ---------- 队列 ----------
    private fun queueFile(app: Context) = File(app.filesDir, QUEUE_FILE)

    private fun ensureLoaded(app: Context) {
        if (loaded) return
        loaded = true
        val f = queueFile(app)
        if (!f.exists()) return
        try {
            val items = f.readLines().mapNotNull { line -> line.trim().takeIf { it.isNotEmpty() }?.let { try { JSONObject(it) } catch (_: Exception) { null } } }
            synchronized(lock) { items.takeLast(MAX).forEach { queue.addLast(it) } }
        } catch (e: Exception) { Log.w(TAG, "load queue", e) }
    }

    /** 在 lock 里调 */
    private fun persist(app: Context) {
        try {
            val f = queueFile(app)
            if (queue.isEmpty()) { f.delete(); return }
            val tmp = File(app.filesDir, "$QUEUE_FILE.tmp")
            tmp.writeText(queue.joinToString("\n") { it.toString() } + "\n")
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        } catch (e: Exception) { Log.w(TAG, "persist queue", e) }
    }

    /** exec 线程里跑：从队头一条条发，发不出去就停下等重试 */
    private fun drain(app: Context) {
        while (true) {
            val head = synchronized(lock) { queue.firstOrNull() } ?: return
            if (send(app, head)) {
                synchronized(lock) {
                    if (queue.firstOrNull() === head) queue.removeFirst()
                    persist(app)
                }
            } else {
                scheduleRetry(app)
                return
            }
        }
    }

    private fun scheduleRetry(app: Context) {
        if (retryScheduled) return
        retryScheduled = true
        main.postDelayed({ retryScheduled = false; flush(app) }, RETRY_MS)
    }

    private fun send(app: Context, body: JSONObject): Boolean {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/reading")
            .header("X-Token", TOKEN)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            http(app).newCall(req).execute().use { r ->
                if (r.isSuccessful) { lastError = null; true }
                else { lastError = "上报 HTTP ${r.code}"; false }
            }
        } catch (e: Exception) {
            lastError = "上报 ${e.javaClass.simpleName}${e.message?.let { ": " + it.take(60) } ?: ""}"
            false
        }
    }
}
