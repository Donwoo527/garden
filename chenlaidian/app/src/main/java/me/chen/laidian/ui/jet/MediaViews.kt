package me.chen.laidian.ui.jet

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.Tls
import me.chen.laidian.net.ChatApi
import me.chen.laidian.net.ChatClient
import okhttp3.Request
import java.io.File

/**
 * 0915 她点的一批：图片/表情缩到 80 宽；点开全屏（整个聊天的图都能左右翻、双指缩放、双击复原、能保存）；
 * 连发的图叠成一摞（微信那种，滑动换最上面那张）；文件点开能在 app 里看（pdf/txt/docx）、长按能存到下载。
 */
private val THUMB = 80.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageImages(images: List<String>, loader: ImageLoader, onOpen: (String) -> Unit, onSticker: (String) -> Unit, onFav: () -> Unit, onForward: (String) -> Unit, onReact: (String) -> Unit) {
    if (images.isEmpty()) return
    var top by remember(images) { mutableIntStateOf(0) }    // 叠着时最上面那张
    var menuFor by remember { mutableStateOf<String?>(null) }
    Box {
        if (images.size == 1) {
            AsyncImage(model = ChatClient.mediaUrl(images[0]), imageLoader = loader, contentDescription = "图片", contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = THUMB).clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { onOpen(images[0]) }, onLongClick = { menuFor = images[0] }))
        } else {
            PhotoStack(images, loader, onOpen = { i -> onOpen(images[i]) }, onLongPress = { i -> menuFor = images[i] }, onIndex = { top = it })
        }
        DropdownMenu(expanded = menuFor != null, onDismissRequest = { menuFor = null }) {
            ReactionRow { e -> onReact(e); menuFor = null }   // 0920 她：图片消息靠长按菜单顶上这排表态（图点一下是看大图）
            DropdownMenuItem(text = { Text("存为表情") }, onClick = { menuFor?.let(onSticker); menuFor = null })
            DropdownMenuItem(text = { Text("收藏") }, onClick = { onFav(); menuFor = null })
            DropdownMenuItem(text = { Text("转发") }, onClick = { menuFor?.let(onForward); menuFor = null })   // 0920 她：这张图原样再发一遍
        }
    }
}

/**
 * 微信式合并照片卡片（0.69）。设计参数来自 PhotoStack by Wren036 (https://github.com/Wren036/PhotoStack)
 * —— 她逐帧量出来的：舞台 142×190、探边 15 每层再多 12、每层转 2.2° 缩 8%、快甩 0.4px/ms、恒定三层可见、
 * 手指即进度条（前半程跟手 后半程沿轨迹回落到对侧探边位）、首尾 24 的弹性。PolyForm Noncommercial 1.0.0，个人用。
 * 这是照参数在 Compose 里重写的，没有用它的代码。
 */
private val STAGE_W = 142.dp
private val STAGE_H = 190.dp
private const val PEEK = 15f
private const val PEEK_STEP = 12f
private const val ROT_STEP = 2.2f
private const val SCALE_STEP = 0.08f
private const val FLING_V = 0.4f        // px/ms
private const val ELASTIC = 24f         // dp

private data class Xf(val dx: Float, val scale: Float, val rot: Float, val alpha: Float)
private fun lerp(a: Xf, b: Xf, t: Float) = Xf(a.dx + (b.dx - a.dx) * t, a.scale + (b.scale - a.scale) * t, a.rot + (b.rot - a.rot) * t, a.alpha + (b.alpha - a.alpha) * t)
/** 静止摆位：相对顶卡的层深 d（负=左侧探边 正=右侧） */
private fun slot(d: Int): Xf {
    if (d == 0) return Xf(0f, 1f, 0f, 1f)
    val k = kotlin.math.abs(d); val s = if (d < 0) -1f else 1f
    return Xf(s * (PEEK + PEEK_STEP * (k - 1)), 1f - SCALE_STEP * k, s * ROT_STEP * k, if (k <= 2) 1f else 0f)
}
/** 恒定三层：顶卡 + 左右各一；到了首/末张，探边配额转到另一侧 */
private fun visible(idx: Int, d: Int, n: Int): Boolean {
    val i = idx + d
    if (i !in 0 until n || kotlin.math.abs(d) > 2) return false
    if (kotlin.math.abs(d) <= 1) return true
    val other = idx - d / 2          // ±2 只在对侧 ±1 不存在时露出
    return other !in 0 until n
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoStack(images: List<String>, loader: ImageLoader, onOpen: (Int) -> Unit, onLongPress: (Int) -> Unit, onIndex: (Int) -> Unit = {}) {
    val n = images.size
    var index by remember(images) { mutableIntStateOf(0) }
    val p = remember { androidx.compose.animation.core.Animatable(0f) }   // 擦洗进度 -1..1 正=翻到下一张
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val halfW = with(density) { (STAGE_W / 2).toPx() }
    val stageW = with(density) { STAGE_W.toPx() }
    val prog = p.value
    val dir = if (prog >= 0f) 1 else -1
    val t = kotlin.math.abs(prog)
    val t2 = ((t - 0.5f) / 0.5f).coerceIn(0f, 1f)
    val canGo = (index + dir) in 0 until n

    fun commit(d: Int) { scope.launch { p.animateTo(d.toFloat(), androidx.compose.animation.core.tween(220)); index = (index + d).coerceIn(0, n - 1); onIndex(index); p.snapTo(0f) } }
    fun cancel() { scope.launch { p.animateTo(0f, androidx.compose.animation.core.spring(stiffness = 600f)) } }

    Box(
        Modifier.width(STAGE_W + 44.dp).height(STAGE_H + 8.dp).pointerInput(images) {
            var startX = 0f; var denom = 1f; var lastT = 0L; var lastX = 0f; var v = 0f; var moved = 0f
            detectHorizontalDragGestures(
                onDragStart = { o -> startX = o.x; lastX = o.x; lastT = System.currentTimeMillis(); v = 0f; moved = 0f; denom = 1f },
                onDragEnd = {
                    val cur = p.value; val d = if (cur >= 0) 1 else -1
                    val go = (index + d) in 0 until n
                    val fling = kotlin.math.abs(v) >= FLING_V && (v < 0) == (d > 0) && kotlin.math.abs(moved) >= 10f
                    if (go && (kotlin.math.abs(cur) >= 0.5f || fling)) commit(d) else cancel()
                },
                onDragCancel = { cancel() },
            ) { change, dx ->
                change.consume()
                val now = System.currentTimeMillis(); val dt = (now - lastT).coerceAtLeast(1)
                v = 0.7f * (dx / dt) + 0.3f * v; lastT = now; lastX = change.position.x
                moved = change.position.x - startX
                val d = if (moved < 0) 1 else -1
                // 行程归一：手指起点到该方向屏幕边缘
                denom = if (moved < 0) startX.coerceAtLeast(1f) else (screenW - startX).coerceAtLeast(1f)
                val raw = (kotlin.math.abs(moved) / denom).coerceIn(0f, 1f) * d
                val go = (index + d) in 0 until n
                // 首尾弹性：没有下一张时只让走 24dp
                val elastic = with(density) { ELASTIC.dp.toPx() }
                val value = if (go) raw else (kotlin.math.abs(moved).coerceAtMost(elastic) / halfW * 0.5f) * d
                scope.launch { p.snapTo(value) }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        // 要画哪些：静止可见的 + 翻完后会可见的（进场卡）
        val slots = (-3..3).filter { d -> (index + d) in 0 until n && (visible(index, d, n) || (t > 0.5f && canGo && visible(index + dir, d - dir, n))) }
        // 远的先画
        for (d in slots.sortedByDescending { kotlin.math.abs(it) }) {
            val from = slot(d).let { if (visible(index, d, n)) it else it.copy(alpha = 0f) }
            val toD = d - dir
            val to = if (canGo) slot(toD).let { if (visible(index + dir, toD, n)) it else it.copy(alpha = 0f) } else from
            val xf = when {
                d == 0 -> {
                    // 顶卡：前半程跟手滑出（最多半卡宽 微旋）后半程沿轨迹落入对侧探边位
                    val peak = Xf(-dir * halfW / with(density) { 1.dp.toPx() }, 1f, -dir * 3f, 1f)
                    if (t <= 0.5f) lerp(slot(0), peak, t / 0.5f) else lerp(peak, to, t2)
                }
                else -> if (t <= 0.5f) from else lerp(from, to, t2)
            }
            val i = index + d
            val zTop = d == 0 && t <= 0.5f
            AsyncImage(
                model = ChatClient.mediaUrl(images[i]), imageLoader = loader, contentDescription = "图片", contentScale = ContentScale.Crop,
                modifier = Modifier.size(STAGE_W, STAGE_H)
                    .graphicsLayer {
                        translationX = with(density) { xf.dx.dp.toPx() }; scaleX = xf.scale; scaleY = xf.scale; rotationZ = xf.rot; alpha = xf.alpha
                        shadowElevation = if (zTop) 6f else 0f
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (d == 0) Modifier.combinedClickable(onClick = { onOpen(index) }, onLongClick = { onLongPress(index) }) else Modifier),
            )
        }
        Text("${index + 1}/$n", fontSize = 10.sp, color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 26.dp, bottom = 10.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

/** 全屏看图：整个聊天里的图左右翻，双指缩放、缩放后单指拖，双击 1x↔2.5x，没放大时点图关，底下 保存/关闭 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewer(images: List<String>, start: Int, loader: ImageLoader, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, images.size - 1)) { images.size }
    var zoomed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                var scale by remember(page) { mutableStateOf(1f) }
                var off by remember(page) { mutableStateOf(Offset.Zero) }
                LaunchedEffect(scale, pager.currentPage) { if (page == pager.currentPage) zoomed = scale > 1f }
                AsyncImage(
                    model = ChatClient.mediaUrl(images[page]), imageLoader = loader, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(page) {
                            // 两指 或 已放大时的单指 才接管；平常单指留给翻页
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val ev = awaitPointerEvent()
                                    val pressed = ev.changes.count { it.pressed }
                                    if (pressed >= 2 || scale > 1f) {
                                        val z = ev.calculateZoom(); val pan = ev.calculatePan()
                                        scale = (scale * z).coerceIn(1f, 5f)
                                        off = if (scale > 1f) off + pan else Offset.Zero
                                        ev.changes.forEach { it.consume() }
                                    }
                                } while (ev.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(page) {
                            detectTapGestures(
                                onDoubleTap = { if (scale > 1f) { scale = 1f; off = Offset.Zero } else scale = 2.5f },
                                onTap = { if (scale == 1f) onClose() },
                            )
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y },
                )
            }
            if (images.size > 1) Text("${pager.currentPage + 1}/${images.size}", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 44.dp))
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Text("保存", color = Color.White, fontSize = 15.sp, modifier = Modifier.clickable {
                    val u = images[pager.currentPage]
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { saveImageToGallery(ctx, u) }
                        Toast.makeText(ctx, if (ok) "存到相册了（Pictures/辰来电）" else "没存上：" + (ChatApi.lastError ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }.padding(8.dp))
                Text("关闭", color = Color.White, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
            }
        }
    }
}

/** 文档在 app 里看：pdf 用系统 PdfRenderer 一页页画；txt/md/log/json/csv 直接读；docx 让服务端抽文字。老 .doc 没法渲染→"其他app" */
@Composable
fun DocViewer(url: String, filename: String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val name = filename?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/')
    val ext = name.substringAfterLast('.', "").lowercase()
    var pages by remember(url) { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }
    var text by remember(url) { mutableStateOf<String?>(null) }
    var image by remember(url) { mutableStateOf<String?>(null) }   // tiff 等服务端转成 png 后的地址
    var err by remember(url) { mutableStateOf<String?>(null) }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                when (ext) {
                    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "wps" -> {
                        // 0915 她要一键适配：Office 家族先让服务端 LibreOffice 转成 pdf 再画
                        val pdfUrl = if (ext == "pdf") url else (ChatApi.convertToPdf(ctx, url) ?: throw Exception(ChatApi.lastError ?: "转换失败"))
                        val f = cacheFile(ctx, pdfUrl, pdfUrl.substringAfterLast('/'))
                        android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                            android.graphics.pdf.PdfRenderer(pfd).use { r ->
                                val out = ArrayList<android.graphics.Bitmap>()
                                for (i in 0 until minOf(r.pageCount, 60)) r.openPage(i).use { p ->
                                    val bmp = android.graphics.Bitmap.createBitmap(widthPx, maxOf(1, widthPx * p.height / p.width), android.graphics.Bitmap.Config.ARGB_8888)
                                    bmp.eraseColor(android.graphics.Color.WHITE)
                                    p.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    out += bmp
                                    pages = out.toList()
                                }
                            }
                        }
                    }
                    "tif", "tiff", "bmp" -> image = ChatApi.convertToPdf(ctx, url) ?: throw Exception(ChatApi.lastError ?: "转换失败")
                    else -> text = cacheFile(ctx, url, name).readText()
                }
            } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 40.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Color.White, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text("其他app", color = Color.White, fontSize = 13.sp, modifier = Modifier.clickable { openFile(ctx, url, filename) }.padding(8.dp))
                Text("关闭", color = Color.White, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
            }
            val t = text
            val img = image
            when {
                err != null -> Text("打不开：$err", color = Color.White, modifier = Modifier.padding(16.dp))
                img != null -> AsyncImage(model = ChatClient.mediaUrl(img), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                pages.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    items(pages.size) { i -> Image(bitmap = pages[i].asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) }
                }
                t != null -> Text(t, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp))
                else -> Text("加载中…", color = Color.White, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/** 0915 她问 mp3 能不能发：音频文件在气泡里直接放（复用 VoicePlayer 的下载+进度） */
fun isAudioFile(name: String?): Boolean =
    (name ?: "").substringAfterLast('.', "").lowercase() in setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "amr")

@Composable
fun AudioFileBubble(url: String, name: String, accent: Color, ink: Color, muted: Color) {
    val ctx = LocalContext.current
    val vs by me.chen.laidian.net.VoicePlayer.state.collectAsState()
    val full = ChatClient.mediaUrl(url)
    val playing = vs?.url == full
    val progress = if (playing) vs?.progress ?: 0f else 0f
    Column(Modifier.widthIn(min = 200.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(accent.copy(alpha = 0.25f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { me.chen.laidian.net.VoicePlayer.toggle(ctx, full) },
                contentAlignment = Alignment.Center,
            ) {
                if (playing) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.width(4.dp).height(14.dp).background(accent, RoundedCornerShape(1.dp)))
                    Box(Modifier.width(4.dp).height(14.dp).background(accent, RoundedCornerShape(1.dp)))
                } else androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.PlayArrow, contentDescription = "播放", tint = accent)
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(name, color = ink, fontSize = 14.sp, maxLines = 1)
                Text("音频", color = muted, fontSize = 11.sp)
            }
        }
        if (playing) Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(12.dp)
            .pointerInput(full) { detectTapGestures { off -> me.chen.laidian.net.VoicePlayer.seekTo(full, off.x / size.width) } }
            .pointerInput(full) { detectHorizontalDragGestures { change, _ -> change.consume(); me.chen.laidian.net.VoicePlayer.seekTo(full, change.position.x / size.width) } },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(accent.copy(alpha = 0.2f), RoundedCornerShape(1.dp)))
            Box(Modifier.fillMaxWidth(progress.coerceIn(0.005f, 1f)).height(2.dp).background(accent, RoundedCornerShape(1.dp)))
        }
    }
}

/** 这些后缀在 app 里直接看 其余交给系统 */
fun canViewInApp(name: String?): Boolean =
    (name ?: "").substringAfterLast('.', "").lowercase() in setOf(
        "pdf", "txt", "md", "log", "json", "csv", "xml", "html", "py", "kt", "js",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "wps",   // 服务端 LibreOffice 转 pdf
        "tif", "tiff", "bmp",   // 服务端 PIL 转 png
    )

private fun cacheFile(ctx: Context, url: String, name: String): File {
    val dir = File(ctx.cacheDir, "files").apply { mkdirs() }
    val f = File(dir, name)
    if (!f.exists() || f.length() == 0L) {
        Tls.client(ctx).newCall(Request.Builder().url(ChatClient.mediaUrl(url)).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            f.outputStream().use { o -> r.body?.byteStream()?.copyTo(o) }
        }
    }
    return f
}

private fun download(ctx: Context, url: String): ByteArray? =
    Tls.client(ctx).newCall(Request.Builder().url(ChatClient.mediaUrl(url)).build()).execute().use { r ->
        if (!r.isSuccessful) { ChatApi.lastError = "下载 HTTP ${r.code}"; return null }
        r.body?.bytes()
    }

/** 存到系统相册 Pictures/辰来电（API 29+ 自己写的文件不用存储权限） */
fun saveImageToGallery(ctx: Context, url: String): Boolean {
    return try {
        val bytes = download(ctx, url) ?: return false
        val name = url.substringAfterLast('/')
        val mime = when (name.substringAfterLast('.', "").lowercase()) { "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/jpeg" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (android.os.Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/辰来电")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: run { ChatApi.lastError = "相册拒绝写入"; return false }
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: run { ChatApi.lastError = "写不进相册"; return false }
        true
    } catch (e: Exception) { ChatApi.lastError = "保存 ${e.javaClass.simpleName}"; false }
}

/** 文件存到 Download/辰来电 */
fun saveFileToDownloads(ctx: Context, url: String, filename: String?): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 29) { ChatApi.lastError = "系统版本太老"; return false }
    return try {
        val name = filename?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/')
        val bytes = download(ctx, url) ?: return false
        val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.RELATIVE_PATH, "Download/辰来电") }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run { ChatApi.lastError = "下载目录拒绝写入"; return false }
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: run { ChatApi.lastError = "写不进下载"; return false }
        true
    } catch (e: Exception) { ChatApi.lastError = "保存 ${e.javaClass.simpleName}"; false }
}

/** 点开文件：先下到 cache/files 再交给系统能打开它的 app */
fun openFile(ctx: Context, url: String, filename: String?) {
    Thread {
        try {
            val name = filename?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/')
            val f = cacheFile(ctx, url, name)
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, "打不开：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_SHORT).show() }
        }
    }.start()
}
