package me.chen.laidian.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import me.chen.laidian.BuildConfig
import me.chen.laidian.Tls
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

/**
 * 终端：chat_server.py 的 /term（pty ⇄ tmux attach / bash）。
 * 服务端发二进制帧 = pty 输出，喂给 Termux 的 TerminalEmulator 解析；我们发二进制 = 按键，发文本 JSON = resize。
 * 单例：切 tab 不丢终端。
 */
object TermClient : TerminalOutput() {
    private const val TOKEN = "chen_home_2026"

    var emulator: TerminalEmulator? = null
        private set
    val redraw = MutableStateFlow(0)
    val status = MutableStateFlow("未连接")
    var mode = "attach"          // attach = 辰的 session；shell = 纯 bash
        private set
    var cols = 80; private set
    var rows = 24; private set

    private val main = Handler(Looper.getMainLooper())
    private var client: OkHttpClient? = null
    @Volatile private var ws: WebSocket? = null
    private var wantConnected = false
    private var appCtx: Context? = null
    @Volatile private var gotData = false   // 0.23 本次连接是否收到过数据(区分真连上/假连上)

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession?) {}
        override fun onTitleChanged(changedSession: TerminalSession?) {}
        override fun onSessionFinished(finishedSession: TerminalSession?) {}
        override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession?) {}
        override fun onColorsChanged(session: TerminalSession?) { bump() }
        override fun onTerminalCursorStateChange(state: Boolean) { bump() }
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private fun bump() { main.post { redraw.value = redraw.value + 1 } }

    /** 第一次拿到画布尺寸时调用；之后尺寸变化走 resize() */
    fun ensure(context: Context, c: Int, r: Int) {
        appCtx = context.applicationContext
        if (emulator == null) {
            cols = c; rows = r
            emulator = TerminalEmulator(this, cols, rows, 2000, sessionClient)
        }
        if (ws == null) { wantConnected = true; connect() }
    }

    fun resize(c: Int, r: Int) {
        if (c == cols && r == rows) return
        cols = c; rows = r
        emulator?.resize(c, r)
        ws?.send(JSONObject().put("resize", JSONArray(listOf(c, r))).toString())
        status.value = "${modeLabel()} · ${cols}×${rows}"
        bump()
    }

    fun switchMode(newMode: String) {
        if (newMode == mode) return
        mode = newMode
        ws?.close(1000, "switch"); ws = null
        emulator = TerminalEmulator(this, cols, rows, 2000, sessionClient)
        bump()
        connect()
    }

    fun reconnect() { wantConnected = true; ws?.close(1000, "reconnect"); ws = null; connect() }

    /** 0.22 回前台探活：熄屏冻结后连接常半死，OkHttp 的 ping 要等最多两个周期才发现。
     *  主动写一帧无害消息（服务端只认 resize，别的忽略）——写不进去 = 连接已死，立刻重连不等它。 */
    fun poke() {
        if (!wantConnected) return
        val alive = ws?.send(JSONObject().put("ping", 1).toString()) ?: false
        if (!alive) { ws = null; connect() }
    }

    /** 0.40 离开终端页/退后台时调用：copy-mode 是共享 pane 的状态，一人定格全端冻屏
     *  (0910 一夜冻屏教训)。服务端只在真挂着 copy-mode 时才 cancel，旧服务端会忽略此消息。 */
    fun exitCopyMode() { if (mode != "shell") ws?.send(JSONObject().put("exit_copy", 1).toString()) }

    fun disconnect() { wantConnected = false; ws?.close(1000, "bye"); ws = null; status.value = "已断开" }

    private fun modeLabel() = if (mode == "shell") "shell" else "辰的session"

    private fun connect() {
        if (!wantConnected || ws != null) return
        val ctx = appCtx ?: return
        val c = client ?: Tls.client(ctx).also { client = it }
        status.value = "终端连接中…"
        val url = "wss://${BuildConfig.SERVER_HOST}:${BuildConfig.CHAT_PORT}/term?token=$TOKEN&mode=$mode&cols=$cols&rows=$rows"
        gotData = false
        ws = c.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("resize", JSONArray(listOf(cols, rows))).toString())
                // 0.23 诚实状态栏：握手成功≠数据能通（被墙时握手能过数据全丢）。收到第一帧才算真连上
                status.value = "已连接 等数据…"
                main.postDelayed({
                    if (ws === webSocket && !gotData) status.value = "⚠连上但收不到数据 可能被墙 检查VPN"
                }, 5000)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!gotData) { gotData = true; main.post { status.value = "${modeLabel()} · ${cols}×${rows}" } }
                val arr = bytes.toByteArray()
                main.post { emulator?.append(arr, arr.size); redraw.value = redraw.value + 1 }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {}
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = down()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = down()
        })
    }

    private fun down() {
        ws = null
        status.value = "终端已断开"
        if (wantConnected) main.postDelayed({ connect() }, 3000)
    }

    fun sendBytes(b: ByteArray) { ws?.send(b.toByteString()) }
    fun sendText(s: String) = sendBytes(s.toByteArray(Charsets.UTF_8))

    // ---- TerminalOutput：模拟器要回写给"进程"的（比如终端应答序列） ----
    override fun write(data: ByteArray, offset: Int, count: Int) { sendBytes(data.copyOfRange(offset, offset + count)) }
    override fun titleChanged(oldTitle: String?, newTitle: String?) {}
    override fun onCopyTextToClipboard(text: String?) {}
    override fun onPasteTextFromClipboard() {}
    override fun onBell() {}
    override fun onColorsChanged() { bump() }
}
