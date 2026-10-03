package me.chen.laidian.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 0929 一起读的 WebView 壳——全 app 唯一被放行的 WebView（README 例外条款：只在前台看书这一页）。
 *
 * 页面从 https://reader.chen/index.html 加载（ES module 只肯在 http(s) 源下跑），资源全靠 shouldInterceptRequest 本地出：
 *   https://reader.chen/<path>      → assets/reader/<path>（index.html / reader.js / foliate-js/...）
 *   https://reader.chen/book/<id>   → filesDir/books/<id>.<ext>（BookStore 拷进来的书）
 * 不用 androidx.webkit 的 WebViewAssetLoader——离线编译缓存里没这个库。
 *
 * 桥：JS → Kotlin 走 window.Android.*（ReaderBridge，回调在 JS 线程来，这里统一切回主线程）；
 *     Kotlin → JS 走 window.reader.*（evaluateJavascript，只能在主线程调）。
 */
class ReaderHost(ctx: Context, private val listener: Listener) {
    interface Listener {
        fun onReady()
        /** 书打开了：{title, author, sections, toc, fixed, pageMode}（0.121 pageMode：fixed=PDF 物理页 / list=书里的页码表 / loc=按字数估的页） */
        fun onBookOpened(info: JSONObject)
        /**
         * 翻页/定位：{event: open|page|jump, dir, reason, cfi, fraction, chapter, index, text,
         *            pos, page, pages, pageLabel}（0.121 底栏用：pos=这页在进度条上的位置 0..1，page/pages=第几页/共几页，page=0 是没编号的页）
         */
        fun onRelocate(o: JSONObject)
        /** 点了页面中间：收/放顶栏 */
        fun onTap()
        fun onError(msg: String)
    }

    companion object {
        private const val TAG = "ReaderHost"
        const val HOST = "reader.chen"
        const val ORIGIN = "https://$HOST"
        fun bookUrl(id: String) = "$ORIGIN/book/$id"

        private val MIME = mapOf(
            "html" to "text/html", "htm" to "text/html",
            "js" to "text/javascript", "mjs" to "text/javascript",   // module script 对 MIME 是严格的
            "css" to "text/css", "json" to "application/json", "wasm" to "application/wasm",
            "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
            "epub" to "application/epub+zip", "pdf" to "application/pdf", "txt" to "text/plain",
            "xml" to "application/xml", "fb2" to "application/x-fictionbook+xml", "cbz" to "application/vnd.comicbook+zip",
            "mobi" to "application/x-mobipocket-ebook", "azw3" to "application/vnd.amazon.mobi8-ebook",
            "bcmap" to "application/octet-stream", "pfb" to "application/octet-stream",
        )
        private val TEXT = setOf("html", "htm", "js", "mjs", "css", "json", "svg", "txt", "xml", "fb2")
        fun mimeOf(name: String) = MIME[ext(name)] ?: "application/octet-stream"
        private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    }

    private val app: Context = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile var ready = false
        private set
    @Volatile private var dead = false
    /** JS 还没报 onReady 时收到的 open：先存着，ready 了再发 */
    private var pendingOpen: String? = null

    // 0.118 诊断：数系统画了这个 WebView 几次。她那边 PDF 整片米色——要分清是 WebView 根本没被系统画，还是画了但页里没东西
    private val drawStats = ReaderDiag.DrawStats()
    val webView: WebView = object : WebView(ctx) {
        override fun onDraw(canvas: Canvas) {
            drawStats.n++
            if (drawStats.firstAt == 0L) drawStats.firstAt = SystemClock.uptimeMillis()
            drawStats.lastHw = canvas.isHardwareAccelerated
            super.onDraw(canvas)
        }
    }
    private val diag = ReaderDiag(ctx, webView, drawStats)

    init {
        setup()
        webView.loadUrl("$ORIGIN/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false          // 一切资源都从 reader.chen 拦截给，不碰 file://
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            textZoom = 100                   // 系统字体缩放不掺和，字号全靠页面里的 A-/A+
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportMultipleWindows(false)
        }
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER
        webView.keepScreenOn = true          // 看书时不熄屏（View 层的 FLAG_KEEP_SCREEN_ON，离开页面 view 销毁自动清）
        WebView.setWebContentsDebuggingEnabled(true)   // 电脑 chrome://inspect 能看页面；自家 app 不怕
        webView.addJavascriptInterface(ReaderBridge(), "Android")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d(TAG, "[js ${m.messageLevel()}] ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != HOST) return null   // 不是我们的源就不管（CSP 已经把外部资源拦了）
                val path = (url.path ?: "/").trimStart('/')
                return try {
                    if (path.startsWith("book/")) serveBook(path.removePrefix("book/"))
                    else serveAsset(path.ifEmpty { "index.html" })
                } catch (e: Exception) {
                    Log.w(TAG, "serve $path failed", e)
                    notFound("${e.javaClass.simpleName}: ${e.message}")
                }
            }

            // 主框架只许待在 reader.chen；书里的外链 reader.js 已经 preventDefault 了，这里再兜一层
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return request.url.host != HOST
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                dead = true
                main.post { listener.onError("阅读页的渲染进程没了（内存不够或 WebView 崩了）退出重进一次") }
                return true   // 返回 true 表示我们自己处理，不让整个 app 跟着崩
            }
        }
    }

    private fun serveAsset(path: String): WebResourceResponse {
        val stream: InputStream = try { app.assets.open("reader/$path") } catch (e: Exception) { return notFound("asset $path") }
        val e = ext(path)
        return WebResourceResponse(mimeOf(path), if (e in TEXT) "utf-8" else null, stream).apply {
            responseHeaders = mapOf("Cache-Control" to "no-store")
        }
    }

    private fun serveBook(rawId: String): WebResourceResponse {
        val id = rawId.substringBefore('?').substringBefore('/')
        val f = BookStore.fileFor(app, id) ?: return notFound("book $id")
        val headers = mapOf("Content-Length" to f.length().toString(), "Cache-Control" to "no-store")
        return WebResourceResponse(mimeOf(f.name), null, 200, "OK", headers, f.inputStream().buffered(64 * 1024))
    }

    private fun notFound(why: String) = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(why.toByteArray()),
    )

    // ---------- Kotlin → JS ----------
    private fun js(code: String) {
        if (dead) return
        main.post { if (!dead && ready) try { webView.evaluateJavascript(code, null) } catch (e: Exception) { Log.w(TAG, "js failed", e) } }
    }
    private fun q(s: String?): String = JSONObject.quote(s ?: "")
    private fun themeJson(bg: String, ink: String, accent: String) =
        JSONObject().put("bg", bg).put("ink", ink).put("accent", accent).toString()

    /** 开书。JS 没就绪就先记着，onReady 时补发。wantDiag：0.118 PDF 开书时页面收诊断、经 onDiag 交回来上传（见 ReaderDiag） */
    fun open(bookId: String, cfi: String?, fontPx: Int, bg: String, ink: String, accent: String, wantDiag: Boolean = false) {
        diag.bookId = bookId
        val code = "reader.open(${q(bookUrl(bookId))}, ${q(cfi)}, $fontPx, ${themeJson(bg, ink, accent)}, $wantDiag)"
        main.post { if (ready) js(code) else pendingOpen = code }
    }
    fun next() = js("reader.next()")
    fun prev() = js("reader.prev()")
    fun goTo(cfi: String) = js("reader.goTo(${q(cfi)})")
    /** 0.121 跳到第 n 页（从 1 起，按开书时定的 pageMode 算）。越界 JS 那边限幅；就在这一页不动。落地后照常来一条 onRelocate（event=jump） */
    fun goToPage(n: Int) = js("reader.goToPage($n)")
    /** 0.121 按全书比例跳（0..1）：进度条在 list 模式下松手用（纸书页码和位置对不上，没法按页号跳） */
    fun goToFraction(f: Float) = js("reader.goToFraction($f)")
    fun setFontSize(px: Int) = js("reader.setFontSize($px)")
    fun setTheme(bg: String, ink: String, accent: String) = js("reader.setTheme(${themeJson(bg, ink, accent)})")
    /** 辰指的那句：当前章节里找到就划线冒气泡，找不到 JS 静默 */
    fun highlight(text: String, note: String) = js("reader.highlight(${q(text)}, ${q(note)})")

    /** 页面离开组合后调（要等 WebView 从视图树摘下来）；之后这个对象不能再用 */
    fun release() {
        diag.onClose()   // 0.118 还没传出去的诊断趁 WebView 还在补传（之后页面再交来的一律不收）
        dead = true
        main.post {
            try {
                webView.stopLoading()
                webView.removeJavascriptInterface("Android")
                webView.webChromeClient = null
                webView.loadUrl("about:blank")
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            } catch (e: Exception) { Log.w(TAG, "release", e) }
        }
    }

    // ---------- JS → Kotlin ----------
    inner class ReaderBridge {   // 不设 private：WebView 靠反射找 @JavascriptInterface 方法
        private fun parse(json: String?): JSONObject? {
            if (json == null) return null
            return try { JSONObject(json) } catch (e: Exception) { null }
        }

        @JavascriptInterface
        fun onReady() {
            main.post {
                ready = true
                pendingOpen?.let { pendingOpen = null; js(it) }
                listener.onReady()
            }
        }

        @JavascriptInterface
        fun onBookOpened(json: String?) { val o = parse(json) ?: return; main.post { listener.onBookOpened(o) } }

        @JavascriptInterface
        fun onRelocate(json: String?) { val o = parse(json) ?: return; main.post { listener.onRelocate(o) } }

        @JavascriptInterface
        fun onTap() { main.post { listener.onTap() } }

        @JavascriptInterface
        fun onError(msg: String?) { main.post { listener.onError(msg ?: "未知错误") } }

        /** 0.118 PDF 诊断：页面每次交来的都是到目前为止的整包（diag.js），ReaderDiag 决定什么时候传 */
        @JavascriptInterface
        fun onDiag(json: String?, reason: String?) { if (json != null) main.post { diag.onJs(json, reason ?: "") } }
    }
}
