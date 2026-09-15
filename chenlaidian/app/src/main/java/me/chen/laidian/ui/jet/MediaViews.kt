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
fun MessageImages(images: List<String>, loader: ImageLoader, onOpen: (String) -> Unit, onSticker: (String) -> Unit, onFav: () -> Unit) {
    if (images.isEmpty()) return
    var top by remember(images) { mutableIntStateOf(0) }    // 叠着时最上面那张
    var menuFor by remember { mutableStateOf<String?>(null) }
    Box {
        if (images.size == 1) {
            AsyncImage(model = ChatClient.mediaUrl(images[0]), imageLoader = loader, contentDescription = "图片", contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = THUMB).clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { onOpen(images[0]) }, onLongClick = { menuFor = images[0] }))
        } else {
            val n = images.size
            val h = THUMB * 4 / 3
            Box(
                Modifier.width(THUMB + 12.dp).height(h + 12.dp).pointerInput(images) {
                    var acc = 0f
                    detectHorizontalDragGestures(onDragEnd = { acc = 0f }, onDragCancel = { acc = 0f }) { change, dx ->
                        change.consume(); acc += dx
                        if (acc > 40) { top = (top - 1 + n) % n; acc = 0f } else if (acc < -40) { top = (top + 1) % n; acc = 0f }
                    }
                },
            ) {
                // 后面的两张露 6dp 的边（0.69 照 PhotoStack 量的参数重画）
                for (k in minOf(2, n - 1) downTo 1) {
                    Box(Modifier.offset(x = (6 * k).dp, y = (6 * k).dp).size(THUMB, h)
                        .background(Color.Black.copy(alpha = 0.10f + 0.06f * (2 - k)), RoundedCornerShape(12.dp)))
                }
                AsyncImage(model = ChatClient.mediaUrl(images[top]), imageLoader = loader, contentDescription = "图片", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(THUMB, h).clip(RoundedCornerShape(12.dp))
                        .combinedClickable(onClick = { onOpen(images[top]) }, onLongClick = { menuFor = images[top] }))
                Text("${top + 1}/$n", fontSize = 10.sp, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(top = 4.dp, start = 4.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
        DropdownMenu(expanded = menuFor != null, onDismissRequest = { menuFor = null }) {
            DropdownMenuItem(text = { Text("存为表情") }, onClick = { menuFor?.let(onSticker); menuFor = null })
            DropdownMenuItem(text = { Text("收藏") }, onClick = { onFav(); menuFor = null })
        }
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
    var err by remember(url) { mutableStateOf<String?>(null) }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                when (ext) {
                    "pdf" -> {
                        val f = cacheFile(ctx, url, name)
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
                    "docx" -> text = ChatApi.docText(ctx, url) ?: throw Exception(ChatApi.lastError ?: "服务端没抽出文字")
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
            when {
                err != null -> Text("打不开：$err", color = Color.White, modifier = Modifier.padding(16.dp))
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

/** 这些后缀在 app 里直接看 其余交给系统 */
fun canViewInApp(name: String?): Boolean =
    (name ?: "").substringAfterLast('.', "").lowercase() in setOf("pdf", "txt", "md", "log", "json", "csv", "docx")

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
