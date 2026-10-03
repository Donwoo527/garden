package me.chen.laidian.reader

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.WebView
import me.chen.laidian.BuildConfig
import me.chen.laidian.net.ChatApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.118 一起读 PDF 诊断的 Kotlin 这头（页面那头是 assets/reader/diag.js）。
 * 她手机上 PDF 打开后整片米色、连白页框都没有，桌面上同一本书画得好好的；这一版不修，只让手机把卡在哪一步报回来。
 *
 * 传到哪：聊天后端现成的 POST /upload?silent=1——只落盘成 chat-app/data/media/<毫秒>_<6位>.json，不进聊天、不通知辰。
 *   没走 /reading：together.py 只挑 ts/chapter/fraction/cfi/dwell_s/text 几个字段存，多塞的 diag 字段会被丢掉，又不想为诊断改服务端。
 * 什么时候传：页面 8 秒那包快照到了（或者 view.open 就失败了）传一次；25 秒还没等到也把手上有的传一次；
 *   之后页面又交来新东西（翻页的渲染打点、迟到的报错），离开阅读页时补一次。一次开书最多两回。
 * 页面交来的 JSON 原样放进 "js"；这边补设备 / WebView 版本，和每次收到时 WebView 自己的状态（"kt"：多大、挂没挂上、系统画过它几次）。
 */
class ReaderDiag(ctx: Context, private val webView: WebView, private val draws: DrawStats) {
    /** ReaderHost 的 WebView 子类在 onDraw 里数：n=0 就是系统一次都没画过这个 WebView */
    class DrawStats {
        var n = 0
        var firstAt = 0L
        var lastHw: Boolean? = null
    }

    companion object { private const val TAG = "ReaderDiag" }

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val born = SystemClock.uptimeMillis()
    var bookId = ""
    private var latest: String? = null
    private var latestSeq = 0
    private var sentSeq = 0
    private var uploads = 0
    private var timerSet = false
    private var closed = false
    private val kt = JSONArray()

    /** 页面交来一包（主线程）。reason：start / opened / 1s / 3s / 8s / late / fail */
    fun onJs(json: String, reason: String) {
        if (closed) return
        latest = json
        latestSeq++
        if (kt.length() < 16) kt.put(viewState(reason))
        if (!timerSet) {
            timerSet = true
            main.postDelayed({ if (!closed && uploads == 0) upload("timeout") }, 25_000)
        }
        if ((reason == "8s" || reason == "fail") && uploads == 0) upload(reason)
    }

    /** 离开阅读页：ReaderHost.release 里、WebView 销毁之前调（主线程） */
    fun onClose() {
        if (closed) return
        if (latest != null && latestSeq != sentSeq) upload("close")
        closed = true
    }

    private fun upload(reason: String) {
        if (uploads >= 3) return
        uploads++
        sentSeq = latestSeq
        val js: Any = latest?.let { runCatching { JSONObject(it) }.getOrNull() ?: it } ?: JSONObject.NULL
        val body = JSONObject()
            .put("kind", "reader_diag").put("app", BuildConfig.VERSION_NAME).put("reason", reason)
            .put("ts", System.currentTimeMillis() / 1000.0).put("book_id", bookId)
            .put("device", device())
            .put("kt", JSONArray(kt.toString()).put(viewState("upload:$reason")))
            .put("js", js)
        val bytes = body.toString().toByteArray()   // 不缩进：量小，看的时候 python3 -m json.tool
        // 网络在后台线程；连不上隔 5 秒、20 秒再试，还不行就算了（下次开书还会再报）
        Thread({
            for (wait in longArrayOf(0, 5_000, 20_000)) {
                if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) { return@Thread }
                val url = ChatApi.uploadBytes(app, bytes, "reader_diag.json", "application/json", silent = true)
                if (url != null) { Log.i(TAG, "诊断已传 $reason ${bytes.size}B → $url"); return@Thread }
                Log.w(TAG, "诊断上传失败 $reason：${ChatApi.lastError}")
            }
        }, "reader-diag").apply { isDaemon = true }.start()
    }

    private fun device(): JSONObject {
        val o = JSONObject().put("brand", Build.BRAND).put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
        try { WebView.getCurrentWebViewPackage()?.let { o.put("webview", "${it.packageName} ${it.versionName}") } } catch (_: Exception) {}
        val dm = app.resources.displayMetrics
        o.put("density", dm.density.toDouble()).put("px", JSONArray().put(dm.widthPixels).put(dm.heightPixels))
        return o
    }

    /** WebView 此刻在 Android 这边的样子：页面里量到的尺寸对不上这里，或者 draws 一直是 0，问题就不在 pdf.js */
    private fun viewState(reason: String): JSONObject {
        val o = JSONObject().put("reason", reason).put("t", SystemClock.uptimeMillis() - born)
        try {
            val v = webView
            o.put("w", v.width).put("h", v.height)
                .put("shown", v.isShown).put("attached", v.isAttachedToWindow)
                .put("vis", v.visibility).put("winVis", v.windowVisibility).put("focus", v.hasWindowFocus())
                .put("hw", v.isHardwareAccelerated).put("layer", v.layerType).put("alpha", v.alpha.toDouble())
                .put("draws", draws.n).put("drawHw", draws.lastHw ?: JSONObject.NULL)
                .put("firstDraw", if (draws.firstAt > 0) draws.firstAt - born else JSONObject.NULL)
            val r = Rect()
            o.put("gvr", if (v.getGlobalVisibleRect(r)) JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom) else JSONObject.NULL)
            (v.parent as? View)?.let { o.put("parent", JSONArray().put(it.width).put(it.height)) }
        } catch (e: Exception) { o.put("err", e.toString().take(200)) }
        return o
    }
}
