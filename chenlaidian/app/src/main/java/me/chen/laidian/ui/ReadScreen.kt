package me.chen.laidian.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.net.AppEvents
import me.chen.laidian.reader.Book
import me.chen.laidian.reader.BookStore
import me.chen.laidian.reader.ReaderHost
import me.chen.laidian.reader.ReadingReporter
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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

    val host = remember {
        ReaderHost(ctx, object : ReaderHost.Listener {
            override fun onReady() {}

            override fun onBookOpened(info: JSONObject) {
                val t = info.optString("title").trim()
                val a = info.optString("author").trim()
                if (t.isNotBlank() && t != title) title = t
                if (t.isNotBlank() || a.isNotBlank()) BookStore.updateMetaAsync(ctx, book.id, t, a)
            }

            override fun onRelocate(o: JSONObject) {
                loading = false
                val cfi = o.optString("cfi")
                val fr = o.optDouble("fraction", 0.0).takeIf { it.isFinite() } ?: 0.0
                val ch = o.optString("chapter")
                chapter = ch; fraction = fr
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

    // 开书：JS 没就绪 host 会先记着；上次的 cfi 一起带过去（空 = 从头）
    LaunchedEffect(Unit) { host.open(book.id, book.cfi.ifBlank { null }, fontPx, skin.bg.hex(), skin.ink.hex(), skin.accent.hex()) }
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

    Box(Modifier.fillMaxSize().background(skin.bg)) {
        AndroidView(factory = { host.webView }, modifier = Modifier.fillMaxSize())
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
            }
        }
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
}
