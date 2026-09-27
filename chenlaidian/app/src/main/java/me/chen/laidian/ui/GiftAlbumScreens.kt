package me.chen.laidian.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import me.chen.laidian.net.ImageUtil
import me.chen.laidian.ui.jet.saveImageToGallery
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0928 她报的：主页「共享相册」「互送礼物」两个圆钮点了只弹 todo toast。网页版早有这两个功能、服务端接口现成，
 * 这里把 app 这两页补上。网络层没碰 ChatApi（那边 client/TOKEN 都是 private），照 AppUpdater/ChatApi 的写法自己写一份最小的 GET/POST。
 */

private data class PresetGift(val emoji: String, val label: String)

private val PRESET_GIFTS = listOf(
    PresetGift("🌹", "玫瑰"), PresetGift("💐", "花束"), PresetGift("🍫", "巧克力"), PresetGift("🍰", "蛋糕"), PresetGift("☕", "咖啡"),
    PresetGift("🧸", "小熊"), PresetGift("💍", "戒指"), PresetGift("🎁", "礼物"), PresetGift("🌙", "月亮"), PresetGift("⭐", "星星"),
)

private data class Gift(val from: String, val gift: String, val name: String, val note: String, val ts: Double?) {
    val fromXiaochen get() = from == "xiaochen"
    companion object {
        fun from(o: JSONObject) = Gift(
            from = o.optString("from", "chen"),
            gift = o.optString("gift", "🎁"),
            name = o.optString("name", ""),
            note = o.optString("note", ""),
            ts = o.optDouble("ts", Double.NaN).let { if (it.isNaN()) null else it },   // ts 字段可能没有
        )
    }
}

/** 相册一条：多图时 images 才有；封面/全屏列表统一从 cover/allImages 取，兼容单图只给 url 的情况 */
private data class AlbumItem(val id: String, val ts: Double, val who: String, val url: String, val caption: String, val images: List<String>) {
    val isMine get() = who == "xiaochen"
    val cover get() = images.firstOrNull() ?: url
    val allImages get() = images.ifEmpty { listOf(url) }
    companion object {
        fun from(o: JSONObject): AlbumItem {
            val imgs = o.optJSONArray("images")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { s -> s.isNotBlank() } } } ?: emptyList()
            return AlbumItem(o.optString("id"), o.optDouble("ts", 0.0), o.optString("who", "chen"), o.optString("url", ""), o.optString("caption", ""), imgs)
        }
    }
}

private const val GA_TOKEN = "chen_home_2026"
private fun gaHttp(ctx: Context) = Tls.client(ctx.applicationContext)

/** GET，取 items 数组；网络/HTTP 失败返回 null（调用方拿它区分"空列表"和"加载失败"，都要给提示） */
private fun gaGetItems(ctx: Context, path: String): JSONArray? {
    val req = Request.Builder().url(ChatClient.baseUrl() + path).header("X-Token", GA_TOKEN).get().build()
    return try {
        gaHttp(ctx).newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            JSONObject(r.body?.string() ?: return null).optJSONArray("items")
        }
    } catch (e: Exception) { null }
}

private fun gaPost(ctx: Context, path: String, o: JSONObject): Boolean {
    val req = Request.Builder().url(ChatClient.baseUrl() + path).header("X-Token", GA_TOKEN)
        .post(o.toString().toRequestBody("application/json".toMediaType())).build()
    return try { gaHttp(ctx).newCall(req).execute().use { it.isSuccessful } } catch (e: Exception) { false }
}

private fun tsLabel(ts: Double): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date((ts * 1000).toLong()))

/** 互送礼物：选一个预设 + 附言 → 送出；下面记录列表（服务端已按新的在前排好） */
@Composable
fun GiftsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val skin = LocalSkin.current

    var gifts by remember { mutableStateOf<List<Gift>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<PresetGift?>(null) }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true; loadError = false
        val arr = withContext(Dispatchers.IO) { gaGetItems(ctx, "/gifts") }
        if (arr == null) loadError = true else gifts = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(Gift::from) }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }

    fun send() {
        val p = picked ?: return
        sending = true
        scope.launch {
            val body = JSONObject().put("from", "xiaochen").put("gift", p.emoji).put("name", p.label).put("note", note.trim())
            val ok = withContext(Dispatchers.IO) { gaPost(ctx, "/gifts", body) }
            sending = false
            if (ok) { picked = null; note = ""; reload() } else Toast.makeText(ctx, "送出失败", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(skin.bg)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = skin.ink) }
            Text("互送礼物", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = skin.ink)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                WhiteCard(Modifier.padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("送一个", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = skin.ink)
                        Spacer(Modifier.height(10.dp))
                        PRESET_GIFTS.chunked(5).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                row.forEach { g -> GiftPickItem(g, selected = picked == g) { picked = g } }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        if (picked != null) {
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).sunken(10.dp).padding(horizontal = 14.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                                BasicTextField(
                                    value = note, onValueChange = { note = it }, singleLine = true,
                                    cursorBrush = SolidColor(skin.ink),
                                    textStyle = LocalTextStyle.current.copy(color = skin.ink, fontSize = 14.sp),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { inner -> Box { if (note.isEmpty()) Text("附言（可空）", fontSize = 14.sp, color = skin.muted); inner() } },
                                )
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                Text("取消", fontSize = 14.sp, color = skin.muted, modifier = Modifier.padding(end = 16.dp).clickable { picked = null; note = "" })
                                Box(
                                    Modifier.height(36.dp).then(if (!sending) Modifier.pressable(18.dp) { send() } else Modifier.flat(18.dp)).padding(horizontal = 18.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(if (sending) "…" else "送出", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (!sending) skin.accent else skin.muted) }
                            }
                        }
                    }
                }
            }
            item { SectionTitle("记录") }
            when {
                loading -> item { Text("加载中…", fontSize = 13.sp, color = skin.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                loadError -> item { Text("加载失败，点一下重试", fontSize = 13.sp, color = skin.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clickable { scope.launch { reload() } }) }
                gifts.isEmpty() -> item { Text("还没有礼物记录", fontSize = 13.sp, color = skin.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                else -> items(gifts) { g -> GiftRow(g) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun GiftPickItem(g: PresetGift, selected: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Column(
        Modifier.width(56.dp).then(if (selected) Modifier.sunken(14.dp) else Modifier.pressable(14.dp, onClick = onClick)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(g.emoji, fontSize = 22.sp)
        Spacer(Modifier.height(2.dp))
        Text(g.label, fontSize = 10.sp, color = if (selected) skin.accent else skin.muted)
    }
}

@Composable
private fun GiftRow(g: Gift) {
    val skin = LocalSkin.current
    WhiteCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(g.gift, fontSize = 30.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(g.name.ifBlank { "礼物" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = skin.ink)
                if (g.note.isNotBlank()) Text(g.note, fontSize = 13.sp, color = skin.muted, modifier = Modifier.padding(top = 2.dp))
                Text(
                    listOfNotNull(if (g.fromXiaochen) "你送的" else "辰送的", g.ts?.let { tsLabel(it) }).joinToString(" · "),
                    fontSize = 11.sp, color = skin.muted, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 共享相册：三列网格 → 点开全屏（多图可翻页）；右上角＋加照片；自己传的能长按删 */
@Composable
fun AlbumScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val skin = LocalSkin.current
    val loader = remember { ImageLoader.Builder(ctx).okHttpClient { Tls.client(ctx) }.build() }

    var albumItems by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var caption by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true; loadError = false
        val arr = withContext(Dispatchers.IO) { gaGetItems(ctx, "/album") }
        if (arr == null) loadError = true else albumItems = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(AlbumItem::from) }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }

    // 选图：照 JetConversation 的相册权限+选图那一套（她 OPPO 相册给的地址没权限直接读会翻车），别自己重新发明
    val mediaPerm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris -> if (uris.isNotEmpty()) pendingUris = uris }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        else Toast.makeText(ctx, "没给相册权限 选不了照片", Toast.LENGTH_SHORT).show()
    }
    fun pickImages() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, mediaPerm) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        else permLauncher.launch(mediaPerm)
    }
    fun upload() {
        val uris = pendingUris
        uploading = true
        scope.launch {
            ChatApi.lastError = null
            // 压缩+上传拿地址：跟聊天发图同一条路（ImageUtil.compressOrRawUpload 内部就是走 ChatApi.uploadImage/uploadBytes）
            val urls = withContext(Dispatchers.IO) { uris.mapNotNull { u -> ImageUtil.compressOrRawUpload(ctx, u) } }
            val ok = urls.isNotEmpty() && withContext(Dispatchers.IO) {
                gaPost(ctx, "/album", JSONObject().put("who", "xiaochen").put("urls", JSONArray(urls)).put("caption", caption.trim()))
            }
            uploading = false
            when {
                !ok -> Toast.makeText(ctx, "发布失败：" + (ChatApi.lastError ?: if (urls.isEmpty()) "读图/压缩失败" else "上传被拒"), Toast.LENGTH_LONG).show()
                urls.size < uris.size -> {   // 0915 的教训：部分张数没传上不能悄悄吞掉
                    pendingUris = emptyList(); caption = ""; reload()
                    Toast.makeText(ctx, "发了 ${urls.size}/${uris.size} 张，有几张没传上", Toast.LENGTH_LONG).show()
                }
                else -> { pendingUris = emptyList(); caption = ""; reload() }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(skin.bg)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = skin.ink) }
            Text("共享相册", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = skin.ink, modifier = Modifier.weight(1f))
            IconButton(onClick = { pickImages() }) { Icon(Icons.Default.Add, contentDescription = "加照片", tint = skin.ink) }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中…", color = skin.muted) }
            loadError -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载失败，点一下重试", color = skin.muted, modifier = Modifier.clickable { scope.launch { reload() } })
            }
            albumItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有照片，点右上角加一张", color = skin.muted, modifier = Modifier.clickable { pickImages() })
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(albumItems, key = { _, a -> a.id }) { idx, a ->
                    AlbumThumb(a, loader, onClick = { viewerIndex = idx },
                        onLongPress = { if (a.isMine) confirmDeleteId = a.id else Toast.makeText(ctx, "辰传的照片删不了", Toast.LENGTH_SHORT).show() })
                }
            }
        }
    }

    viewerIndex?.let { idx -> albumItems.getOrNull(idx)?.let { AlbumViewer(it, loader, onClose = { viewerIndex = null }) } }

    if (pendingUris.isNotEmpty()) AlertDialog(
        onDismissRequest = { if (!uploading) { pendingUris = emptyList(); caption = "" } },
        containerColor = skin.bg,
        title = { Text("发 ${pendingUris.size} 张照片", color = skin.ink) },
        text = {
            Column {
                OutlinedTextField(value = caption, onValueChange = { caption = it }, placeholder = { Text("配一句文字（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (uploading) { Spacer(Modifier.height(8.dp)); Text("上传中…", fontSize = 12.sp, color = skin.muted) }
            }
        },
        confirmButton = { Button(enabled = !uploading, onClick = { upload() }) { Text("发布") } },
        dismissButton = { TextButton(enabled = !uploading, onClick = { pendingUris = emptyList(); caption = "" }) { Text("取消") } },
    )

    confirmDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            containerColor = skin.bg,
            title = { Text("删除这张照片？", color = skin.ink) },
            text = { Text("删掉就没了", color = skin.muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteId = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { gaPost(ctx, "/album/delete", JSONObject().put("id", id)) }
                        if (ok) reload() else Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("删除", color = Color(0xFFE53935)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteId = null }) { Text("取消", color = skin.muted) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumThumb(item: AlbumItem, loader: ImageLoader, onClick: () -> Unit, onLongPress: () -> Unit) {
    val count = item.images.size
    Box(Modifier.fillMaxWidth().aspectRatio(1f).combinedClickable(onClick = onClick, onLongClick = onLongPress)) {
        AsyncImage(
            model = ChatClient.mediaUrl(item.cover), imageLoader = loader, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        if (count > 1) Box(
            Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        ) { Text("$count 张", fontSize = 10.sp, color = Color.White) }
    }
}

/**
 * 相册专用全屏看图。MediaViews.ImageViewer 是自己开一个不透明黑底的独立 Dialog、没有配文插槽，
 * 两个 Dialog 没法安全叠在一起同时显示配文，所以这里没有直接调它，自己搭了个最简版：
 * 翻页用 HorizontalPager（跟 ImageViewer 同款 API，没有新依赖），保存直接复用 MediaViews.saveImageToGallery，没有重写。
 * 没做双指缩放（ImageViewer 有，这里没做——她没点这个单，先保证配文常驻显示）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumViewer(item: AlbumItem, loader: ImageLoader, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val images = item.allImages
    val pager = rememberPagerState(initialPage = 0) { images.size }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = ChatClient.mediaUrl(images[page]), imageLoader = loader, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
                )
            }
            if (images.size > 1) Text(
                "${pager.currentPage + 1}/${images.size}", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 44.dp),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 36.dp),
            ) {
                Text(if (item.isMine) "你传的" else "辰传的", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (item.caption.isNotBlank()) Text(item.caption, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Text("保存", color = Color.White, fontSize = 14.sp, modifier = Modifier.clickable {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { saveImageToGallery(ctx, images[pager.currentPage]) }
                            Toast.makeText(ctx, if (ok) "存到相册了" else "没存上", Toast.LENGTH_SHORT).show()
                        }
                    })
                    Text("关闭", color = Color.White, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onClose))
                }
            }
        }
    }
}
