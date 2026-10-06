package me.chen.laidian.ui

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.ViewGroup
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.chen.laidian.model.Msg
import me.chen.laidian.net.AppEvents
import me.chen.laidian.net.ChatClient
import me.chen.laidian.reader.Book
import me.chen.laidian.reader.BookStore
import me.chen.laidian.reader.ReaderHost
import me.chen.laidian.reader.ReadingReporter
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 0929 一起读（她电话里拍的："看书用网页那个套进 app 里，只要能一起看就行"）。
 * 两层：书架（导入/列表/长按删）→ 阅读页（foliate-js 跑在 ReaderHost 的 WebView 里）。
 * 每翻一页把本页原文报给服务端（ReadingReporter），辰指着哪句说话就在那句上划线冒气泡（AppEvents.readingAnchor）。
 * 阅读页是全屏子页：SubPage.open 收起 dock；返回键先回书架再退出。
 */
@Composable
fun ReadScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var reading by remember { mutableStateOf<Book?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh, reading) {
        if (reading == null) delay(250)   // 阅读页 onDispose 里的进度是异步写盘的，等它一下再刷列表
        books = withContext(Dispatchers.IO) { BookStore.list(ctx) }
    }
    LaunchedEffect(reading) { SubPage.open = reading != null }
    DisposableEffect(Unit) { onDispose { SubPage.open = false } }
    LaunchedEffect(Unit) { ReadingReporter.start(ctx) }
    BackHandler { if (reading != null) reading = null else onBack() }
    val cur = reading
    if (cur != null) {
        key(cur.id) { ReaderPage(book = cur, onBack = { reading = null }) }
    } else {
        Shelf(books = books, onChanged = { refresh++ }, onOpen = { reading = it }, onBack = onBack)
    }
}

// ---------------- 书架 ----------------

@Composable
private fun Shelf(books: List<Book>, onChanged: () -> Unit, onOpen: (Book) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Book?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { BookStore.importUri(ctx, uri) } }
            importing = false
            r.onSuccess { Toast.makeText(ctx, "《${it.title}》放上书架了", Toast.LENGTH_SHORT).show(); onChanged() }
                .onFailure { Toast.makeText(ctx, "导入失败：${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
        }
    }

    Column(Modifier.fillMaxSize().background(skin.bg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = onBack) { Text("← 百宝箱", color = skin.muted) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("一起读", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = skin.ink, modifier = Modifier.weight(1f))
            Button(
                onClick = { picker.launch(arrayOf("application/epub+zip", "text/plain", "application/pdf", "*/*")) },
                enabled = !importing,
            ) { Text(if (importing) "导入中…" else "导入书") }
        }
        Text("翻一页 辰那边就能看到这一页；长按一本可以删", fontSize = 12.sp, color = skin.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (books.isEmpty()) {
            Text("书架还空着。点「导入书」选一本 epub / txt / pdf（微信读书、Kindle 买的带锁导不出来）", fontSize = 14.sp, color = skin.muted, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(books, key = { it.id }) { b -> BookRow(b, onClick = { onOpen(b) }, onLongPress = { confirmDelete = b }) }
        }
    }

    confirmDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = skin.surface,
            title = { Text("删掉《${b.title}》？", color = skin.ink) },
            text = { Text("书和读到哪都一起删。辰那边已经收到过的页不受影响。", color = skin.muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { withContext(Dispatchers.IO) { BookStore.delete(ctx, b.id) }; onChanged() }
                }) { Text("删", color = Color(0xFFD24C3E)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("留着", color = skin.ink) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(b: Book, onClick: () -> Unit, onLongPress: () -> Unit) {
    val skin = LocalSkin.current
    val pct = "${(b.fraction * 100).roundToInt()}%"
    val sub = listOf(formatLabel(b.format), b.chapter, if (b.lastRead > 0) "读到 " + timeLabel(b.lastRead) else "还没翻开")
        .filter { it.isNotBlank() }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().raised(16.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(formatTint(b.format)), contentAlignment = Alignment.Center) {
            Text(b.format.uppercase(Locale.US), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, fontSize = 16.sp, color = skin.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.sp, color = skin.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(pct, fontSize = 13.sp, color = skin.ink)
    }
}

private fun formatLabel(f: String) = when (f) {
    "epub" -> "epub"; "txt" -> "txt"; "pdf" -> "pdf（试验）"; else -> f
}

private fun formatTint(f: String) = when (f) {
    "epub" -> Color(0xFFB98BE8); "txt" -> Color(0xFF5B7BB4); "pdf" -> Color(0xFFD24C3E); else -> Color(0xFF9A9590)
}

/** 刚刚 / 今天 HH:mm / 昨天 HH:mm / MM-dd */
private fun timeLabel(ms: Long): String {
    val d = System.currentTimeMillis() - ms
    if (d < 60_000) return "刚刚"
    if (d < 3_600_000) return "${d / 60_000} 分钟前"
    val c = Calendar.getInstance(); val t = Calendar.getInstance().apply { timeInMillis = ms }
    val hm = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
    if (c.get(Calendar.YEAR) == t.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) return "今天 $hm"
    c.add(Calendar.DAY_OF_YEAR, -1)
    if (c.get(Calendar.YEAR) == t.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) return "昨天 $hm"
    return SimpleDateFormat("MM-dd", Locale.US).format(Date(ms))
}

// ---------------- 阅读页 ----------------

/** 最后位置：onDispose 里报 close / 存进度用，放 remember 里让回调和销毁都摸得到 */
private class LastLoc(var cfi: String, var fraction: Double, var chapter: String)

private fun Color.hex(): String = String.format(Locale.US, "#%06X", 0xFFFFFF and toArgb())

@Composable
private fun ReaderPage(book: Book, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val skin = LocalSkin.current
    val prefs = remember { ctx.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    var fontPx by remember { mutableIntStateOf(prefs.getInt("font_px", 18)) }
    var title by remember { mutableStateOf(book.title) }
    var chapter by remember { mutableStateOf(book.chapter) }
    var fraction by remember { mutableStateOf(book.fraction) }
    var stripVisible by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val last = remember { LastLoc(book.cfi, book.fraction, book.chapter) }
    // 0.121 底栏：页码算法 / 这页在进度条上的位置 / 第几页 / 共几页，每条 relocate 回写；pages = 0 = 还没拿到，底栏先不出
    var pageMode by remember { mutableStateOf("loc") }
    var pos by remember { mutableFloatStateOf(0f) }
    var page by remember { mutableIntStateOf(0) }
    var pages by remember { mutableIntStateOf(0) }
    var pageLabel by remember { mutableStateOf("") }
    var jumpOpen by remember { mutableStateOf(false) }
    // 0.124 顶栏「⋮」菜单：消息弹窗开关（默认开，跟字号一样存 reader prefs）
    var menuOpen by remember { mutableStateOf(false) }
    var popupOn by remember { mutableStateOf(prefs.getBoolean("chen_popup", true)) }

    val host = remember {
        ReaderHost(ctx, object : ReaderHost.Listener {
            override fun onReady() {}

            override fun onBookOpened(info: JSONObject) {
                val t = info.optString("title").trim()
                val a = info.optString("author").trim()
                if (t.isNotBlank() && t != title) title = t
                if (t.isNotBlank() || a.isNotBlank()) BookStore.updateMetaAsync(ctx, book.id, t, a)
                info.optString("pageMode").takeIf { it.isNotBlank() }?.let { pageMode = it }
            }

            override fun onRelocate(o: JSONObject) {
                loading = false
                val cfi = o.optString("cfi")
                val fr = o.optDouble("fraction", 0.0).takeIf { it.isFinite() } ?: 0.0
                val ch = o.optString("chapter")
                chapter = ch; fraction = fr
                // 0.121 底栏回写：翻页、跳页、改字号重排都走这里
                o.optDouble("pos").takeIf { it.isFinite() }?.let { pos = it.toFloat().coerceIn(0f, 1f) }
                page = o.optInt("page", 0); pages = o.optInt("pages", 0); pageLabel = o.optString("pageLabel")
                last.cfi = cfi; last.fraction = fr; last.chapter = ch
                BookStore.updateProgressAsync(ctx, book.id, cfi, fr, ch)
                ReadingReporter.report(ctx, JSONObject()
                    .put("event", o.optString("event", "page").ifBlank { "page" })
                    .put("book", title).put("book_id", book.id).put("format", book.format)
                    .put("chapter", ch).put("cfi", cfi).put("fraction", fr)
                    .put("dir", o.optString("dir"))
                    .put("text", o.optString("text").take(4000)))
            }

            override fun onTap() { stripVisible = !stripVisible }

            override fun onError(msg: String) {
                if (loading) error = msg
                else Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        })
    }

    // 开书：JS 没就绪 host 会先记着；上次的 cfi 一起带过去（空 = 从头）。0.118 PDF 顺带收诊断传回去（她那边 PDF 整片米色，查卡在哪一步）
    LaunchedEffect(Unit) { host.open(book.id, book.cfi.ifBlank { null }, fontPx, skin.bg.hex(), skin.ink.hex(), skin.accent.hex(), wantDiag = book.format == "pdf") }
    // 换皮肤书页跟着换色
    LaunchedEffect(skin) { host.setTheme(skin.bg.hex(), skin.ink.hex(), skin.accent.hex()) }
    // 辰指着一句话说：划线 + 气泡（不在当前章 JS 静默）
    LaunchedEffect(Unit) { AppEvents.readingAnchor.collect { host.highlight(it.optString("text"), it.optString("note")) } }
    // 20 秒还没翻开：多半是 WebView 太老或书坏了，给她看得见的字
    LaunchedEffect(Unit) { delay(20_000); if (loading && error == null) error = "20 秒了还没翻开——可能是系统 WebView 太老，或者这本书坏了" }
    // 离开阅读页：报 close（dwell = 最后一页停了多久）、存进度、销毁 WebView（等它从视图树摘下来再 destroy）
    DisposableEffect(Unit) {
        onDispose {
            ReadingReporter.report(ctx, JSONObject()
                .put("event", "close").put("book", title).put("book_id", book.id).put("format", book.format)
                .put("chapter", last.chapter).put("cfi", last.cfi).put("fraction", last.fraction)
                .put("dir", "").put("text", ""))
            BookStore.updateProgressAsync(ctx, book.id, last.cfi, last.fraction, last.chapter)
            host.release()
        }
    }

    val setFont = { px: Int ->
        fontPx = px.coerceIn(12, 32)
        prefs.edit().putInt("font_px", fontPx).apply()
        host.setFontSize(fontPx)
    }
    val setPopup = { on: Boolean ->
        popupOn = on
        prefs.edit().putBoolean("chen_popup", on).apply()
    }

    Box(Modifier.fillMaxSize().background(skin.bg)) {
        // 0.119 她手机上整片米色的真凶：不给 layoutParams，AndroidView 默认挂成 WRAP_CONTENT，
        // WebView 见到 WRAP_CONTENT 就把网页布局高度强制成 0——innerHeight 767、body 高 0（0.118 诊断实测），页画好了塞在 0 高的框里
        AndroidView(factory = {
            host.webView.apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        }, modifier = Modifier.fillMaxSize())
        if (stripVisible) {
            // 顶部一条细状态：书名 · 章节 · 百分比；点一下收起，点页面中间再出来
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(skin.bg.copy(alpha = 0.94f))
                    .clickable { stripVisible = false }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "回书架", tint = skin.ink, modifier = Modifier.size(18.dp))
                }
                Text(
                    listOf(title, chapter, "${(fraction * 100).roundToInt()}%").filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 12.sp, color = skin.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { setFont(fontPx - 2) }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("A-", fontSize = 12.sp, color = skin.ink)
                }
                TextButton(onClick = { setFont(fontPx + 2) }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("A+", fontSize = 13.sp, color = skin.ink)
                }
                // 0.124 「⋮」菜单：现在只有消息弹窗一个开关，以后阅读页的设置都往这里放
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = skin.ink, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.background(skin.surface)) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("消息弹窗", fontSize = 14.sp, color = skin.ink)
                                    Text("辰说话时从顶上弹出来，点一下能回", fontSize = 11.sp, color = skin.muted)
                                }
                            },
                            trailingIcon = {
                                Switch(
                                    checked = popupOn, onCheckedChange = null,   // 整行都能点，开关本身只显示
                                    colors = SwitchDefaults.colors(checkedTrackColor = skin.accent),
                                )
                            },
                            onClick = { setPopup(!popupOn) },
                        )
                    }
                }
            }
        }
        if (stripVisible && pages > 0) {
            // 0.121 底栏跟顶栏同一个开关：点页面中间一起出来、一起收
            ReaderBottomBar(
                pos = pos, page = page, pages = pages, mode = pageMode, label = pageLabel,
                onSeek = { v ->
                    val n = pageAt(v, pageMode, pages)
                    if (n > 0) host.goToPage(n) else host.goToFraction(v)
                },
                onPageClick = { jumpOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        // 0.124 辰实时发来的话：顶部弹窗（顶栏开着就落在它下面）
        ChenPopup(enabled = popupOn, top = if (stripVisible) 42.dp else 10.dp, modifier = Modifier.align(Alignment.TopCenter))
        if (loading && error == null) {
            Text("翻开中…", fontSize = 14.sp, color = skin.muted, modifier = Modifier.align(Alignment.Center))
        }
        error?.let { msg ->
            Column(Modifier.align(Alignment.Center).background(skin.bg.copy(alpha = 0.96f)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("这本打不开", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = skin.ink)
                Spacer(Modifier.height(6.dp))
                Text(msg, fontSize = 12.sp, color = skin.muted)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onBack) { Text("回书架", color = skin.accent) }
            }
        }
    }
    if (jumpOpen) {
        PageJumpDialog(
            current = page, pages = pages, mode = pageMode,
            onJump = { n -> jumpOpen = false; host.goToPage(n) },
            onDismiss = { jumpOpen = false },
        )
    }
}

// ---------------- 消息弹窗（0.124） ----------------

/**
 * 0.124 阅读页顶部消息弹窗。她 1006 点的单："看书的页面…你知道我看到哪 跟我一起看 偶尔想说话的时候就说"，
 * 又补"不一定要像弹幕 消息弹窗那样就可以""点一下可以快速弹出输入框回复你"。
 * 看书时聊天页不在眼前：阅读页开着期间辰实时发来的每条（文字 / 语音的那段文字；思考行、工具行、纯图片不算）
 * 从顶上滑下来一张卡片，停一会儿自己收回去；往上划提前收；点一下弹快捷回复框，框开着卡片不收。连着来几条排队一张张出。
 * 只订阅 AppEvents.chatMsg（ws 实时到的新 id），history 分页 / 重连拉回来的不走那条；这里再按 id 去重一层。
 * 卡片本来就是聊天记录里那条，这里只是多显示一下，不另存；回复走 ChatClient.sendText（跟聊天页同一个），回完自然出现在聊天页。
 * 卡片以外不吃触摸：AnimatedVisibility 那层不挂任何手势，点按 / 滑动照样落到下面的 WebView 翻页。
 */
@Composable
private fun ChenPopup(enabled: Boolean, top: Dp, modifier: Modifier = Modifier) {
    val on by rememberUpdatedState(enabled)
    val queue = remember { mutableStateListOf<Msg>() }
    val seen = remember { HashSet<String>() }
    var current by remember { mutableStateOf<Msg?>(null) }
    var shown by remember { mutableStateOf(false) }
    var replying by remember { mutableStateOf<Msg?>(null) }

    LaunchedEffect(Unit) {
        AppEvents.chatMsg.collect { m ->
            if (!on || !m.isChen || m.isAux || m.text.isBlank()) return@collect
            if (m.msgType != "text" && m.msgType != "voice") return@collect
            if (!seen.add(m.id)) return@collect   // 重连 / 重复广播：同一条只弹一次
            queue.add(m)
        }
    }
    // 开关关掉：手上这张收起、排着的全丢（她正在打的回复框不动）
    LaunchedEffect(enabled) { if (!enabled) { queue.clear(); shown = false } }
    // 一张张出：停够时长 / 被划走 / 回复发出去，哪个先到算哪个；回复框开着时不计时
    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow { queue.isNotEmpty() }.first { it }
            val m = queue.removeAt(0)
            current = m
            shown = true
            var left = popupMillis(m.text)
            while (shown) {
                if (replying != null) {
                    snapshotFlow { replying }.first { it == null }
                    left = maxOf(left, 2_500L)   // 回复框没发就关了：再停一会儿，别一关卡片就没了
                    continue
                }
                val t0 = SystemClock.uptimeMillis()
                withTimeoutOrNull(left) { snapshotFlow { !shown || replying != null }.first { it } } ?: break
                left -= SystemClock.uptimeMillis() - t0
            }
            shown = false
            delay(350)   // 收回去的动画走完再出下一张
        }
    }

    AnimatedVisibility(
        visible = shown,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier.padding(top = top, start = 12.dp, end = 12.dp).widthIn(max = 560.dp),
    ) {
        current?.let { m -> PopupCard(m, onReply = { replying = m }, onSwipeAway = { shown = false }) }
    }
    replying?.let { m ->
        QuickReply(to = m, onSent = { replying = null; shown = false }, onDismiss = { replying = null })
    }
}

/** 停多久：至少 5 秒；20 字以后每字多 0.18 秒（默读一秒五六个字），封顶 15 秒——卡片最多四行，再长也看不全，点开回复框能看整句 */
private fun popupMillis(text: String): Long = (5_000L + (text.length - 20).coerceAtLeast(0) * 180L).coerceAtMost(15_000L)

/** 0.124 弹窗卡片：底色反着用皮肤的 ink / bg（跟书页里辰划线冒的气泡一个配色），半透明圆角；点 = 回复，往上划过 28dp 松手 = 收起 */
@Composable
private fun PopupCard(m: Msg, onReply: () -> Unit, onSwipeAway: () -> Unit) {
    val skin = LocalSkin.current
    val away = with(LocalDensity.current) { 28.dp.toPx() }
    var dy by remember(m.id) { mutableFloatStateOf(0f) }
    Column(
        Modifier.fillMaxWidth()
            .offset { IntOffset(0, dy.roundToInt()) }
            .clip(RoundedCornerShape(16.dp))
            .background(skin.ink.copy(alpha = 0.9f))
            .pointerInput(m.id) {
                detectVerticalDragGestures(
                    onDragEnd = { if (dy < -away) onSwipeAway() else dy = 0f },
                    onDragCancel = { dy = 0f },
                ) { change, d -> change.consume(); dy = (dy + d).coerceAtMost(0f) }   // 只跟着往上走
            }
            .clickable(onClick = onReply)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("辰", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = skin.bg)
            Spacer(Modifier.width(8.dp))
            Text("点一下回复 · 上划收起", fontSize = 10.sp, color = skin.bg.copy(alpha = 0.55f))
        }
        Spacer(Modifier.height(2.dp))
        Text(m.text, fontSize = 14.sp, lineHeight = 20.sp, color = skin.bg, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 0.124 快捷回复：贴在屏幕底部的一条输入框，键盘顶着它出来。发送走 ChatClient.sendText(text, replyTo = 这条的 id)——
 * 跟聊天页输入框同一个函数、同一条 ws（{"type":"text","reply_to"}），服务端照常落库、广播回来进聊天页、带引用快照。
 * 用 Dialog 单开一个窗口：键盘只顶这个窗口，阅读页不跟着缩——WebView 一缩 EPUB 要重排，会给辰平白报两条「跳转」。
 */
@Composable
private fun QuickReply(to: Msg, onSent: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val skin = LocalSkin.current
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // 框没了（发出去 / 点外面 / 返回 / 连阅读页一起退出）都把「正在输入」清掉，别让辰那边一直挂着
    DisposableEffect(Unit) { onDispose { ChatClient.typing(false) } }
    val send = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            if (ChatClient.sendText(t, to.id)) onSent()   // sendText 里自己会把「正在输入」清掉
            else Toast.makeText(ctx, "没连上后端 稍等重连", Toast.LENGTH_SHORT).show()   // 字留在框里
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setGravity(Gravity.BOTTOM)
            @Suppress("DEPRECATION")   // 这个窗口不是全面屏布局，adjustResize 照样管用：整块顶在键盘上面
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            window?.setDimAmount(0.2f)
        }
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(skin.bg)
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Text("回复 辰：" + to.text, fontSize = 12.sp, color = skin.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; ChatClient.typing(it.isNotEmpty()) },
                    placeholder = { Text("说点什么", color = skin.muted) },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                TextButton(onClick = send, enabled = text.isNotBlank()) {
                    Text("发送", color = if (text.isNotBlank()) skin.accent else skin.muted)
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() }; keyboard?.show() }   // 一弹出来就能打字
    }
}

/** 0.121 进度条上 v 这个位置是第几页：跟 reader.js 的 progressOf 同一个算法（fixed 按页号均分，loc 按 location 均分）；list 模式算不出，返回 0 */
private fun pageAt(v: Float, mode: String, pages: Int): Int {
    if (pages <= 0) return 0
    val x = v.coerceIn(0f, 1f)
    return when (mode) {
        "fixed" -> (x * (pages - 1)).roundToInt() + 1
        "loc" -> minOf(floor(x * pages).toInt(), pages - 1) + 1
        else -> 0
    }
}

/**
 * 0.121 阅读页底栏：进度条 + 「当前页 / 总页数」（点页码弹跳页框）。
 * 拖动时只在页码那格预览拖到第几页，松手才跳——拖动中每动一下就跳，会给服务端发几十条位置上报。
 * 整条吃掉点按：点在底栏空白处的手指不能漏到下面的 WebView（那边点左右三分之一翻页、点中间收放栏）。
 */
@Composable
private fun ReaderBottomBar(
    pos: Float, page: Int, pages: Int, mode: String, label: String,
    onSeek: (Float) -> Unit, onPageClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    var drag by remember { mutableStateOf<Float?>(null) }      // 手指还按在进度条上：只预览
    var landing by remember { mutableStateOf<Float?>(null) }   // 松手了、新页的 relocate 还没回来：滑块先停在松手处，不弹回去再跳过来
    LaunchedEffect(pos) { landing = null }                     // 新位置回来了（或者她又翻了页）：以真实位置为准
    LaunchedEffect(landing) { if (landing != null) { delay(2500); landing = null } }   // 没等来 relocate（翻页动画中途被丢掉之类）也别一直停着
    val shown = drag ?: landing
    val preview = shown?.let { pageAt(it, mode, pages) } ?: 0
    val text = when {
        shown == null -> "${label.ifBlank { "–" }} / $pages"
        preview > 0 -> "$preview / $pages"
        else -> "${(shown * 100).roundToInt()}%"   // list 模式：纸书页码跟位置对不上，只能预览百分比
    }
    Row(
        modifier.fillMaxWidth()
            .background(skin.bg.copy(alpha = 0.94f))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = (shown ?: pos).coerceIn(0f, 1f),
            onValueChange = { drag = it },
            onValueChangeFinished = {
                val v = drag
                drag = null
                if (v != null) {
                    val n = pageAt(v, mode, pages)
                    if (n == 0 || n != page) { landing = v; onSeek(v) }   // 拖了一圈又回到这一页：不跳
                }
            },
            colors = SliderDefaults.colors(thumbColor = skin.accent, activeTrackColor = skin.accent, inactiveTrackColor = skin.muted.copy(alpha = 0.3f)),
            modifier = Modifier.weight(1f).systemGestureExclusion(),   // 从屏幕左边缘拖滑块，别被系统当成返回手势退出阅读页
        )
        TextButton(onClick = onPageClick, modifier = Modifier.widthIn(min = 84.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(text, fontSize = 12.sp, color = if (shown != null) skin.accent else skin.ink, maxLines = 1)
        }
    }
}

/** 0.121 点底栏页码弹的跳页框：输数字按键盘上的前往/完成，或点「跳」；越界限幅到 1..总页数 */
@Composable
private fun PageJumpDialog(current: Int, pages: Int, mode: String, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val init = if (current in 1..pages) current.toString() else ""
    // 当前页号先全选上：直接打字就是覆盖（Readest 的 PageJumpInput 也这么做）
    var field by remember { mutableStateOf(TextFieldValue(init, TextRange(0, init.length))) }
    val raw = field.text.toIntOrNull()
    val target = raw?.coerceIn(1, pages)
    val go = { if (target != null) onJump(target) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = skin.surface,
        title = { Text("跳到第几页", color = skin.ink) },
        text = {
            Column {
                OutlinedTextField(
                    value = field,
                    onValueChange = { v -> if (v.text.length <= 6 && v.text.all { it.isDigit() }) field = v },
                    singleLine = true,
                    suffix = { Text("/ $pages", color = skin.muted) },
                    supportingText = if (raw != null && raw != target) { { Text("没有第 $raw 页，会跳到第 $target 页", color = skin.muted) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }, onDone = { go() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (mode == "loc") {
                    Text("epub 没有固定页码，这里的页是按字数估的，跟屏幕上的一屏对不齐", fontSize = 12.sp, color = skin.muted, modifier = Modifier.padding(top = 8.dp))
                }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }   // 一弹出来就能打字
            }
        },
        confirmButton = { TextButton(onClick = go, enabled = target != null) { Text("跳", color = if (target != null) skin.accent else skin.muted) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("算了", color = skin.ink) } },
    )
}
