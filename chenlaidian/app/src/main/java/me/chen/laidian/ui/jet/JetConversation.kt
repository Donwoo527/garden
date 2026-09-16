package me.chen.laidian.ui.jet
import me.chen.laidian.ui.ProfileHistoryDialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.R
import me.chen.laidian.Tls
import me.chen.laidian.model.Msg
import me.chen.laidian.net.ChatApi
import me.chen.laidian.net.ChatClient
import me.chen.laidian.net.ImageUtil
import me.chen.laidian.net.VoicePlayer
import me.chen.laidian.ui.C
import me.chen.laidian.ui.DotsAvatar
import me.chen.laidian.ui.raised
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天页 —— 结构照 Google 的 Jetchat 示例（Apache-2.0）搬：顶栏 / 消息列（头像+名字+时间+气泡，同人连发合并）/ 日期分隔 / 回到底部 / 输入栏带表情。
 * 数据换成我们的 ChatClient；辰在左、小陈在右（她的规矩）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JetConversation(onCall: () -> Unit) {
    val ctx = LocalContext.current
    val msgs by ChatClient.messages.collectAsState()
    val connected by ChatClient.connected.collectAsState()
    val alive by ChatClient.sessionAlive.collectAsState()
    val status by ChatClient.status.collectAsState()
    val mood by ChatClient.mood.collectAsState()
    val sig by ChatClient.signature.collectAsState()
    val readIds by ChatClient.readIds.collectAsState()
    var replyTo by remember { mutableStateOf<Msg?>(null) }
    var forwardText by remember { mutableStateOf<String?>(null) }   // 0.36 转发:原文进输入框
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    // 0915 她：点开图能翻整个聊天里的图——看图器放在这一层 拿全部图的顺序列表
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    val allImages = remember(msgs) { msgs.flatMap { m -> if (m.msgType == "images") m.images else if (m.msgType == "image" && m.media != null) listOf(m.media) else emptyList() } }
    val scrollState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val scope = rememberCoroutineScope()
    val loader = remember { ImageLoader.Builder(ctx).okHttpClient { Tls.client(ctx) }.build() }
    val shown = remember(msgs, query) { (if (query.isBlank()) msgs else msgs.filter { it.text.contains(query, true) }).asReversed() }
    val lastId = msgs.lastOrNull()?.id
    LaunchedEffect(lastId) { if (scrollState.firstVisibleItemIndex <= 1) scrollState.scrollToItem(0) }
    val nearTop by remember { derivedStateOf { val info = scrollState.layoutInfo; info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 3 } }
    LaunchedEffect(nearTop) { if (nearTop && query.isBlank()) ChatClient.loadMore() }
    // 0915 ④ 发图可勾"原图"：选完先弹一个小框 勾了传原文件 不勾走压缩（长边 1600 JPEG 82）；失败 Toast 带原因
    var pendingImages by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var sendOriginal by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris -> if (uris.isNotEmpty()) pendingImages = uris }
    // 0915 相册权限：她的 OPPO 相册给的地址没权限读不到（0914 起"图片发送失败"、0915"表情没存上"都是它）。先要权限再开相册
    val mediaPerm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        else Toast.makeText(ctx, "没给相册权限 发不了图", Toast.LENGTH_SHORT).show()
    }
    fun pickImages() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, mediaPerm) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        else permLauncher.launch(mediaPerm)
    }
    fun sendPicked(uris: List<android.net.Uri>, original: Boolean) {
        sending = true
        scope.launch {
            ChatApi.lastError = null
            val urls = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    if (original) {
                        val mime = ctx.contentResolver.getType(u) ?: "image/jpeg"
                        val ext = when (mime) { "image/png" -> ".png"; "image/webp" -> ".webp"; "image/gif" -> ".gif"; "image/heic", "image/heif" -> ".heic"; else -> ".jpg" }
                        ctx.contentResolver.openInputStream(u)?.use { it.readBytes() }?.let { ChatApi.uploadBytes(ctx, it, "orig$ext", mime) }
                    } else ImageUtil.compressOrRawUpload(ctx, u)   // 0915 压不动的（动图/webp）原样传 别再"发送失败"
                }
            }
            val ok = urls.isNotEmpty() && withContext(Dispatchers.IO) { ChatApi.sendImages(ctx, urls, "") }
            sending = false
            if (!ok) Toast.makeText(ctx, "图片发送失败：" + (ChatApi.lastError ?: if (urls.isEmpty()) "读图/压缩失败" else "发送被拒"), Toast.LENGTH_LONG).show()
            // 0915 教训：兜底路径不能静默——走了"原样上传"就得说出来，不然根因被盖住（这次盖了一整天）
            else if (!original && ChatApi.lastError != null) Toast.makeText(ctx, "发了 但压缩没走通（${ChatApi.lastError}）发的是原图", Toast.LENGTH_LONG).show()
        }
    }
    // 0915 ③ 文件（不压 服务端直接入库当她发的一条）和拍照（系统相机预览图 → JPEG 88）
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        sending = true
        scope.launch {
            ChatApi.lastError = null
            val ok = withContext(Dispatchers.IO) {
                var name = "file"
                try { ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name } } catch (_: Exception) {}
                val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
                bytes != null && ChatApi.uploadBytes(ctx, bytes, name, mime, silent = false) != null
            }
            sending = false
            if (!ok) Toast.makeText(ctx, "文件发送失败：" + (ChatApi.lastError ?: "读不到文件"), Toast.LENGTH_LONG).show()
        }
    }
    // 0915 拍照全尺寸：相机把原图写进 cache/camera/ 再走和相册一样的压缩上传（0.62 用的预览图只有 144×192）
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok0 ->
        val u = cameraUri
        if (!ok0 || u == null) return@rememberLauncherForActivityResult
        sending = true
        scope.launch {
            ChatApi.lastError = null
            val ok = withContext(Dispatchers.IO) {
                ImageUtil.compressOrRawUpload(ctx, u)?.let { ChatApi.sendImages(ctx, listOf(it), "") } ?: false
            }
            sending = false
            if (!ok) Toast.makeText(ctx, "拍照发送失败：" + (ChatApi.lastError ?: ""), Toast.LENGTH_LONG).show()
        }
    }
    fun takePhoto() {
        try {
            val dir = java.io.File(ctx.cacheDir, "camera").apply { mkdirs() }
            val f = java.io.File(dir, "shot_${System.currentTimeMillis()}.jpg")
            val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            cameraUri = u
            camera.launch(u)
        } catch (e: Exception) { Toast.makeText(ctx, "打不开相机：${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        topBar = {
            ChannelNameBar(alive, connected, mood, sig, scrollBehavior,
                onAvatar = { showCard = true },
                onSearch = { searching = !searching; if (!searching) query = "" },
                onCall = onCall,
                onInfo = { showCard = true })
        },
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.navigationBars).exclude(WindowInsets.ime),
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (searching) {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("搜聊天记录") },
                    shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            }
            Messages(shown, msgs, readIds, loader, scrollState, Modifier.weight(1f),
                onOpenImage = { viewerUrl = it },
                onQuote = { replyTo = it },
                onForward = { forwardText = it.text },
                onFav = { m -> scope.launch { val ok = withContext(Dispatchers.IO) { ChatApi.addFavorite(ctx, m) }; Toast.makeText(ctx, if (ok) "已收藏" else "收藏失败", Toast.LENGTH_SHORT).show() } },
                onCopy = { m -> (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("msg", m.text)); Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show() })
            if (sending) Text("图片上传中…", fontSize = 12.sp, color = C.Grey, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            if (status == "thinking") Text("辰在想…", fontSize = 12.sp, color = C.Grey, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            replyTo?.let { q ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("引用 ${if (q.isChen) "辰" else "小陈"}：${q.text.take(40)}", fontSize = 12.sp, color = C.Grey, modifier = Modifier.weight(1f))
                    IconButton(onClick = { replyTo = null }) { Icon(Icons.Default.Close, contentDescription = "取消引用") }
                }
            }
            JetUserInput(
                insertText = forwardText,
                onInsertConsumed = { forwardText = null },
                onSendSticker = { url -> scope.launch { withContext(Dispatchers.IO) { ChatApi.sendImages(ctx, listOf(url), "") } } },
                onMessageSent = { t -> if (ChatClient.sendText(t, replyTo?.id)) replyTo = null else Toast.makeText(ctx, "没连上后端 稍等重连", Toast.LENGTH_SHORT).show() },
                onTyping = { ChatClient.typing(it) },
                onPickImages = { pickImages() },
                onPickFile = { filePicker.launch(arrayOf("*/*")) },
                onTakePhoto = { takePhoto() },
                onCall = onCall,
                resetScroll = { scope.launch { scrollState.scrollToItem(0) } },
                modifier = Modifier,   // 0.53 键盘/导航栏留白统一由 MainScreen 做，这里不再叠

            )
        }
    }
    if (pendingImages.isNotEmpty()) AlertDialog(
        onDismissRequest = { pendingImages = emptyList() },
        title = { Text("发送 ${pendingImages.size} 张图") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { sendOriginal = !sendOriginal }) {
                androidx.compose.material3.Checkbox(checked = sendOriginal, onCheckedChange = { sendOriginal = it })
                Text("原图（不压缩）")
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { val l = pendingImages; pendingImages = emptyList(); sendPicked(l, sendOriginal) }) { Text("发送") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingImages = emptyList() }) { Text("取消") } },
    )
    if (showCard) ProfileCard(alive, mood, sig, onDismiss = { showCard = false }, onCall = { showCard = false; onCall() }, onHistory = { showCard = false; showHistory = true })
    if (showHistory) ProfileHistoryDialog(onDismiss = { showHistory = false })
    viewerUrl?.let { u -> ImageViewer(allImages.ifEmpty { listOf(u) }, allImages.indexOf(u).coerceAtLeast(0), loader) { viewerUrl = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelNameBar(alive: Boolean, connected: Boolean, mood: String, sig: String, scrollBehavior: TopAppBarScrollBehavior,
                           onAvatar: () -> Unit, onSearch: () -> Unit, onCall: () -> Unit, onInfo: () -> Unit) {
    val skin = me.chen.laidian.ui.LocalSkin.current
    // 0915 她的 Frame 1（360×800 画板 2x 导出量的）：顶栏 80 高；← 24 | 头像 38 圆 | 辰 18 半粗 ·VPS 心情 / 签名 12 | 搜索 电话 菜单 24 中心间距 30
    Surface(color = skin.bg, contentColor = skin.ink) {
        Row(Modifier.fillMaxWidth().height(80.dp).padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // 她稿上左上角的返回箭头：回哪儿等她定 先接资料卡 不留死按钮
            BarIcon(rememberVectorPainter(Icons.Default.KeyboardArrowLeft), "返回", onInfo)
            Spacer(Modifier.width(6.dp))
            // 0915 她：头像 40 横向居中 不凸起
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color.White)   // 0915 她：顶栏头像也给个白底 点还是跳
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onAvatar),
                contentAlignment = Alignment.Center,
            ) { DotsAvatar(big = 13.dp, small = 8.dp, gap = 4.dp, online = null, box = 32.dp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // 0915 她：VPS/绿点/心情 跟名字对齐——小字去掉字体上下留白 全部按中线对齐
                val small = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 12.sp,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
                // 0915 她第二次说没对齐：改按文字基线对齐（名字/VPS/心情三段基线一条线 绿点坐在基线上）心情再上移 1dp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("辰", fontSize = 18.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = skin.ink,
                        style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                        modifier = Modifier.alignByBaseline())
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.alignBy { it.measuredHeight }.padding(bottom = 1.dp).size(6.dp).clip(CircleShape).background(if (alive) C.Green else skin.muted))
                    Spacer(Modifier.width(3.dp))
                    Text("VPS", style = small, color = if (alive) C.Green else skin.muted, modifier = Modifier.alignByBaseline())
                    if (alive && mood.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(mood, style = small, color = C.Orange, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alignByBaseline().offset(y = (-2).dp))   // 0915 她两次各要 1dp
                    }
                }
                // 签名回到顶栏第二行（0.37 曾退到资料卡，她 0915 的稿又放回来了）；没签名时放连接状态
                Text(
                    sig.ifBlank { when { alive -> "在线"; connected -> "辰不在"; else -> "连接中…" } },
                    fontSize = 12.sp, color = skin.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            BarIcon(painterResource(R.drawable.ic_search), "搜索", onSearch)
            BarIcon(rememberVectorPainter(Icons.Default.Phone), "打电话", onCall)
            BarIcon(rememberVectorPainter(Icons.Default.Menu), "菜单", onInfo)
        }
    }
}

/** 顶栏图标：24 的图标放在 30 宽的可点区里 = 她稿上 30 的中心间距 */
@Composable
private fun BarIcon(p: androidx.compose.ui.graphics.painter.Painter, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.width(30.dp).height(48.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(p, contentDescription = desc, tint = me.chen.laidian.ui.LocalSkin.current.ink, modifier = Modifier.size(24.dp)) }
}

private fun Msg.dayLabel(): String {
    val d = Date((ts * 1000).toLong())
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(d)
    return if (day == today) "今天" else SimpleDateFormat("M月d日", Locale.CHINA).format(d)
}

@Composable
private fun Messages(messages: List<Msg>, all: List<Msg>, readIds: Set<String>, loader: ImageLoader, scrollState: LazyListState, modifier: Modifier,
                     onOpenImage: (String) -> Unit, onQuote: (Msg) -> Unit, onForward: (Msg) -> Unit, onFav: (Msg) -> Unit, onCopy: (Msg) -> Unit) {
    val scope = rememberCoroutineScope()
    Box(modifier) {
        LazyColumn(reverseLayout = true, state = scrollState, modifier = Modifier.fillMaxSize()) {
            for (index in messages.indices) {
                val m = messages[index]
                val newer = messages.getOrNull(index - 1)
                val older = messages.getOrNull(index + 1)
                val isFirstMessageByAuthor = newer?.who != m.who      // 这一串里最新的一条 → 下面留大间距
                val isLastMessageByAuthor = older?.who != m.who       // 这一串里最早的一条 → 显示头像和名字
                // 0915 她：同一个人同一分钟连发的 只在最后一条下面标时间
                val showTime = newer == null || newer.who != m.who || newer.timeLabel() != m.timeLabel()
                item(key = m.id) {
                    if (m.who == "system") SystemPill(m.text)
                    else MessageRow(m, all.firstOrNull { it.id == m.replyTo }, isUserMe = !m.isChen, isFirstMessageByAuthor, isLastMessageByAuthor, showTime = showTime,
                        read = m.id in readIds, loader = loader, onOpenImage = onOpenImage, onQuote = onQuote, onForward = onForward, onFav = onFav, onCopy = onCopy)
                }
                val day = m.dayLabel()
                if (older == null || older.dayLabel() != day) item(key = "day-$day-${m.id}") { DayHeader(day) }
            }
        }
        // 0915 她：消息区是凹下去的一块——上沿深影 下沿亮边；只有新拟物那张皮画（Skin.sunkenEdges）颜色跟它的凹凸阴影同一套
        me.chen.laidian.ui.LocalSkin.current.sunkenEdges?.let { (dark, light) ->
            Box(Modifier.fillMaxWidth().height(12.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(dark, Color.Transparent))))
            Box(Modifier.fillMaxWidth().height(12.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, light))))
        }
        val jumpThreshold = with(LocalDensity.current) { 56.dp.toPx() }
        val jumpEnabled by remember { derivedStateOf { scrollState.firstVisibleItemIndex != 0 || scrollState.firstVisibleItemScrollOffset > jumpThreshold } }
        JumpToBottom(enabled = jumpEnabled, onClicked = { scope.launch { scrollState.animateScrollToItem(0) } }, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SystemPill(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)) {
            Text(text, fontSize = 13.sp, color = C.Grey, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(m: Msg, quoted: Msg?, isUserMe: Boolean, isFirstMessageByAuthor: Boolean, isLastMessageByAuthor: Boolean, showTime: Boolean, read: Boolean,
                       loader: ImageLoader, onOpenImage: (String) -> Unit, onQuote: (Msg) -> Unit, onForward: (Msg) -> Unit, onFav: (Msg) -> Unit, onCopy: (Msg) -> Unit) {
    val avatarXiaochen by ChatClient.avatarXiaochen.collectAsState()
    val borderColor = if (isUserMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val spaceBetweenAuthors = if (isLastMessageByAuthor) Modifier.padding(top = 8.dp) else Modifier
    // 思考类型消息：独立气泡，半折叠预览（像 TG 的 expandable blockquote）
    if (m.msgType == "thinking") {
        Row(modifier = spaceBetweenAuthors.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            AvatarOrSpace(isLastMessageByAuthor, borderColor, isChen = true, url = "", loader = loader)
            Column(Modifier.weight(1f, fill = false).widthIn(max = 280.dp), horizontalAlignment = Alignment.Start) {
                ThinkingPreview(m.text)
                if (showTime) TimeUnder(m.timeLabel(), false, false)
                Spacer(Modifier.height(3.dp))
            }
        }
        return
    }
    Row(modifier = spaceBetweenAuthors.fillMaxWidth(), horizontalArrangement = if (isUserMe) Arrangement.End else Arrangement.Start) {
        if (!isUserMe) AvatarOrSpace(isLastMessageByAuthor, borderColor, isChen = true, url = "", loader = loader)
        Column(Modifier.weight(1f, fill = false).padding(if (isUserMe) 0.dp else 0.dp), horizontalAlignment = if (isUserMe) Alignment.End else Alignment.Start) {
            // 0.20 按她设计稿：不显示昵称行(头像已区分人)，时间挪到气泡下方小字
            if (!isUserMe && !m.thinking.isNullOrBlank()) ThinkingFold(m.thinking)
            ChatItemBubble(m, quoted, isUserMe, loader, onOpenImage, onQuote, onForward, onFav, onCopy)
            if (showTime) TimeUnder(m.timeLabel(), isUserMe, read)
            Spacer(Modifier.height(if (isFirstMessageByAuthor) 8.dp else 3.dp))
        }
        if (isUserMe) AvatarOrSpace(isLastMessageByAuthor, borderColor, isChen = false, url = avatarXiaochen, loader = loader)
    }
}

@Composable
private fun AvatarOrSpace(show: Boolean, borderColor: Color, isChen: Boolean, url: String, loader: ImageLoader) {
    if (!show) { Spacer(Modifier.width(54.dp)); return }
    // 0915 她：头像外的色环去掉（蓝/橙都不要）辰的头像白底圆
    Box(
        Modifier.padding(horizontal = 10.dp).size(34.dp).clip(CircleShape)
            .background(if (isChen) Color.White else C.Orange),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isChen -> DotsAvatar(big = 10.dp, small = 7.dp, gap = 3.dp, box = 24.dp)
            url.isNotBlank() -> AsyncImage(model = ChatClient.mediaUrl(url), imageLoader = loader, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Text("陈", color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AuthorNameTimestamp(name: String, time: String, isUserMe: Boolean, read: Boolean) {
    Row(horizontalArrangement = if (isUserMe) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (isUserMe) { TimeMini(time, read); Spacer(Modifier.width(8.dp)) }
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (!isUserMe) { Spacer(Modifier.width(8.dp)); Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun TimeMini(time: String, read: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(if (read) "✓✓" else "✓", fontSize = 11.sp, color = if (read) C.Green else C.Grey)
    }
}

/** 0.20 设计稿位置：时间贴在气泡正下方，她的消息带已读双勾 */
@Composable
private fun TimeUnder(time: String, isUserMe: Boolean, read: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Text(time, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isUserMe) {
            Spacer(Modifier.width(4.dp))
            Text(if (read) "✓✓" else "✓", fontSize = 11.sp, color = if (read) C.Green else C.Grey)
        }
    }
}

@Composable
internal fun ThinkingPreview(thinking: String) {
    var open by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tr by remember(thinking) { mutableStateOf<String?>(null) }
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), modifier = Modifier.padding(bottom = 4.dp)) {
        Column(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = !open }.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                Text("💭", fontSize = 10.sp, modifier = Modifier.padding(end = 2.dp))
                Text("思考", fontSize = 10.sp, color = C.Grey)
            }
            Text(thinking, fontSize = 12.sp, color = C.Grey, lineHeight = 16.sp, maxLines = if (open) Int.MAX_VALUE else 3, overflow = if (open) TextOverflow.Clip else TextOverflow.Ellipsis)
            if (open) {
                Text(
                    if (tr == null) "翻译" else "收起翻译", fontSize = 12.sp, color = me.chen.laidian.ui.LocalSkin.current.accent,
                    modifier = Modifier.padding(top = 4.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (tr != null) tr = null
                        else scope.launch {
                            ChatApi.lastError = null
                            val t = withContext(Dispatchers.IO) { ChatApi.translate(ctx, thinking) }
                            if (t != null) tr = t else Toast.makeText(ctx, "翻译失败：" + (ChatApi.lastError ?: ""), Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                tr?.let { Text(it, fontSize = 12.sp, color = C.Grey, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

@Composable
private fun ThinkingFold(thinking: String) {
    var open by remember { mutableStateOf(false) }
    // 0915 她的图：收起时箭头朝右 展开朝下（"点一下左边箭头可以展开"）
    // 0915 她：点的时候出灰框——那是点击水波纹(indication) 关掉
    Row(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = !open }.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("💭", fontSize = 10.sp, modifier = Modifier.padding(end = 2.dp))
        Text("思考", fontSize = 10.sp, color = C.Grey)
    }
    // 0915 她点的：思考链常是英文 展开后可以点"翻译"（服务端 MiniMax）译文接在下面
    var tr by remember(thinking) { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    if (open) Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), modifier = Modifier.padding(bottom = 6.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(thinking, fontSize = 13.sp, color = C.Grey, lineHeight = 17.sp)
            Text(
                if (tr == null) "翻译" else "收起翻译", fontSize = 12.sp, color = me.chen.laidian.ui.LocalSkin.current.accent,
                modifier = Modifier.padding(top = 4.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (tr != null) tr = null
                    else scope.launch {
                        ChatApi.lastError = null
                        val t = withContext(Dispatchers.IO) { ChatApi.translate(ctx, thinking) }
                        if (t != null) tr = t else Toast.makeText(ctx, "翻译失败：" + (ChatApi.lastError ?: ""), Toast.LENGTH_SHORT).show()
                    }
                },
            )
            tr?.let { Text(it, fontSize = 13.sp, color = C.Grey, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

// 0915 她：四个角都圆 不留那个尖角
private val ChenBubbleShape = RoundedCornerShape(20.dp)
private val MeBubbleShape = RoundedCornerShape(20.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatItemBubble(m: Msg, quoted: Msg?, isUserMe: Boolean, loader: ImageLoader, onOpenImage: (String) -> Unit, onQuote: (Msg) -> Unit, onForward: (Msg) -> Unit, onFav: (Msg) -> Unit, onCopy: (Msg) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf(false) }
    var translated by remember(m.id) { mutableStateOf<String?>(null) }   // 0915 长按→翻译 译文贴在气泡里正文下面
    // 0915 她的图：辰浅蓝在左 她浅橘在右 字都是深色；颜色归皮肤管
    val skin = me.chen.laidian.ui.LocalSkin.current
    val bg = if (isUserMe) skin.bubbleMe else skin.bubbleChen
    val fg = skin.ink
    val shape = if (isUserMe) MeBubbleShape else ChenBubbleShape
    // 0.19 她的规矩：气泡最远不越过对面头像那条线（两侧头像列各 54dp + 8dp 余量）
    val maxW = (LocalConfiguration.current.screenWidthDp - 116).dp
    // 0.21 她设计稿：左滑气泡直接引用（滑过阈值触发+回弹；只吃水平手势，不挡列表滚动）
    val offsetX = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val quoteThreshold = with(LocalDensity.current) { 56.dp.toPx() }
    val maxDrag = with(LocalDensity.current) { 100.dp.toPx() }
    Column(horizontalAlignment = if (isUserMe) Alignment.End else Alignment.Start) {
        Box {
            Surface(color = bg, shape = shape, modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .widthIn(max = maxW)
                .pointerInput(m.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX.value < -quoteThreshold) onQuote(m)
                            dragScope.launch { offsetX.animateTo(0f) }
                        },
                        onDragCancel = { dragScope.launch { offsetX.animateTo(0f) } },
                    ) { change, amount ->
                        val new = (offsetX.value + amount).coerceIn(-maxDrag, 0f)
                        if (new != offsetX.value) { change.consume(); dragScope.launch { offsetX.snapTo(new) } }
                    }
                }
                .combinedClickable(onClick = {}, onLongClick = { menu = true })) {
                Column(Modifier.padding(if (m.msgType == "voice") 6.dp else 0.dp)) {
                    quoted?.let { q ->
                        // 0915 她的图：引用框 = 左竖条(强调色) + 名字 + 右上引号 + 折叠箭头；收起一行 展开四行；回复正文在下面（tg 那种）
                        var qOpen by remember(q.id) { mutableStateOf(false) }
                        val bar = if (q.isChen) skin.accent else C.Orange
                        Row(
                            Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp)
                                .background(fg.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { qOpen = !qOpen },
                        ) {
                            Box(Modifier.width(4.dp).height(if (qOpen) 72.dp else 44.dp).background(bar, RoundedCornerShape(2.dp)))
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (q.isChen) "辰" else "小陈", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = bar, modifier = Modifier.weight(1f))
                                    Text("”", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = bar)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(q.text.ifBlank { if (q.msgType == "voice") "[语音]" else "[图片]" }, fontSize = 13.sp, color = fg.copy(alpha = 0.8f),
                                        maxLines = if (qOpen) 4 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Icon(if (qOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = "展开引用", tint = bar, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    when {
                        m.msgType == "voice" && m.voice != null -> {
                            // 0915 她要的（对着网页版）：圆播放钮 + 波形 + 右边秒数 + 播放时下面进度条 + "查看文字版"
                            val vs by VoicePlayer.state.collectAsState()
                            val url = ChatClient.mediaUrl(m.voice)
                            val playing = vs?.url == url
                            val progress = if (playing) vs?.progress ?: 0f else 0f
                            Column(Modifier.widthIn(min = 220.dp).padding(horizontal = 8.dp, vertical = 6.dp)) {   // 0915 她：语音再紧 2
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(34.dp).clip(CircleShape).background(skin.accent.copy(alpha = 0.25f))
                                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { VoicePlayer.toggle(ctx, url) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (playing) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {   // 暂停：两根竖条（核心图标集里没有 Pause）
                                            Box(Modifier.width(4.dp).height(14.dp).background(skin.accent, RoundedCornerShape(1.dp)))
                                            Box(Modifier.width(4.dp).height(14.dp).background(skin.accent, RoundedCornerShape(1.dp)))
                                        } else Icon(Icons.Default.PlayArrow, contentDescription = "播放", tint = skin.accent)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    VoiceBars(seed = m.id, progress = progress, color = skin.accent, modifier = Modifier.weight(1f).height(28.dp))
                                    m.duration?.let { d ->
                                        Spacer(Modifier.width(8.dp))
                                        Text("${(d / 60).toInt()}:${"%02d".format((d % 60).roundToInt())}", fontSize = 12.sp, color = skin.muted)
                                    }
                                }
                                // 0915 她：进度条能拖（像录音那样）——12dp 高的触摸区里画 2dp 的线 点/拖都跳到那个位置
                                if (playing) Box(
                                    Modifier.fillMaxWidth().padding(top = 4.dp).height(12.dp)
                                        .pointerInput(url) {
                                            detectHorizontalDragGestures { change, _ -> change.consume(); VoicePlayer.seekTo(url, change.position.x / size.width) }
                                        }
                                        .pointerInput(url) {
                                            detectTapGestures { off -> VoicePlayer.seekTo(url, off.x / size.width) }
                                        },
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Box(Modifier.fillMaxWidth().height(2.dp).background(skin.accent.copy(alpha = 0.2f), RoundedCornerShape(1.dp)))
                                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.005f, 1f)).height(2.dp).background(skin.accent, RoundedCornerShape(1.dp)))
                                    // 圆点挂在已播部分的右端
                                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.005f, 1f)).height(12.dp), contentAlignment = Alignment.CenterEnd) {
                                        Box(Modifier.size(10.dp).clip(CircleShape).background(skin.accent))
                                    }
                                }
                                if (m.text.isNotBlank()) {
                                    Row(
                                        Modifier.padding(top = 4.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { transcript = !transcript },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("查看文字版", fontSize = 12.sp, color = skin.accent)
                                        Icon(if (transcript) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = "文字版", tint = skin.accent, modifier = Modifier.size(16.dp))
                                    }
                                    if (transcript) Text(m.text, color = fg, fontSize = 15.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                        m.msgType == "file" && m.media != null && isAudioFile(m.filename ?: m.media) ->
                            AudioFileBubble(m.media, m.filename ?: m.media.substringAfterLast('/'), skin.accent, fg, skin.muted)   // 0915 她问 mp3：气泡里直接放
                        m.msgType == "file" -> {
                            // 0915 她：文件点开能看（交给系统 app）长按能存到下载
                            var fmenu by remember { mutableStateOf(false) }
                            var showDoc by remember { mutableStateOf(false) }   // 0915 她：文档在 app 里看（pdf/txt/docx）
                            if (showDoc && m.media != null) DocViewer(m.media, m.filename) { showDoc = false }
                            Box {
                                Text("📎 " + (m.filename ?: "文件"), color = fg, fontSize = 15.sp,
                                    modifier = Modifier.combinedClickable(
                                        onClick = { m.media?.let { if (canViewInApp(m.filename ?: it)) showDoc = true else openFile(ctx, it, m.filename) } },
                                        onLongClick = { fmenu = true },
                                    ).padding(14.dp))
                                DropdownMenu(expanded = fmenu, onDismissRequest = { fmenu = false }) {
                                    DropdownMenuItem(text = { Text("保存到下载") }, onClick = {
                                        fmenu = false
                                        m.media?.let { u -> dragScope.launch {
                                            val ok = withContext(Dispatchers.IO) { saveFileToDownloads(ctx, u, m.filename) }
                                            Toast.makeText(ctx, if (ok) "存到 Download/辰来电 了" else "没存上：" + (ChatApi.lastError ?: ""), Toast.LENGTH_SHORT).show()
                                        } }
                                    })
                                }
                            }
                        }
                        m.text.isNotBlank() -> ClickableMessage(m.text, isUserMe, fg)
                        else -> {}
                    }
                    translated?.let { t ->
                        HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = fg.copy(alpha = 0.15f))
                        Text(t, fontSize = 13.sp, lineHeight = 17.sp, color = fg.copy(alpha = 0.78f), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("复制") }, onClick = { menu = false; onCopy(m) })
                DropdownMenuItem(text = { Text("引用") }, onClick = { menu = false; onQuote(m) })
                DropdownMenuItem(text = { Text("收藏") }, onClick = { menu = false; onFav(m) })
                DropdownMenuItem(text = { Text("转发") }, onClick = { menu = false; onForward(m) })
                if (m.text.isNotBlank()) DropdownMenuItem(text = { Text(if (translated == null) "翻译" else "收起翻译") }, onClick = {
                    menu = false
                    if (translated != null) translated = null
                    else dragScope.launch {
                        ChatApi.lastError = null
                        val t = withContext(Dispatchers.IO) { ChatApi.translate(ctx, m.text) }
                        if (t != null) translated = t else Toast.makeText(ctx, "翻译失败：" + (ChatApi.lastError ?: ""), Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }
        val imgs = if (m.msgType == "images") m.images else if (m.msgType == "image" && m.media != null) listOf(m.media) else emptyList()
        if (imgs.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            // 0915 她：图不套气泡、缩到 80、点开全屏能翻能存、连发的叠一摞（MediaViews.kt）；长按→存为表情/收藏
            MessageImages(imgs, loader, onOpen = onOpenImage, onSticker = { u ->
                dragScope.launch {
                    val ok = withContext(Dispatchers.IO) { ChatApi.addSticker(ctx, u) }
                    Toast.makeText(ctx, if (ok) "存进表情库了" else "没存上：" + (ChatApi.lastError ?: ""), Toast.LENGTH_SHORT).show()
                }
            }, onFav = { onFav(m) })
        }
    }
}

/** 语音波形：20 根 3dp 的条 高 8–26（网页版同款 随机），种子用消息 id 让同一条每次长得一样；播过的部分实色 没播的半透 */
@Composable
private fun VoiceBars(seed: String, progress: Float, color: Color, modifier: Modifier = Modifier) {
    val heights = remember(seed) { val r = kotlin.random.Random(seed.hashCode()); List(20) { 8 + r.nextFloat() * 18 } }
    androidx.compose.foundation.Canvas(modifier) {
        val n = heights.size
        val barW = 3.dp.toPx(); val gap = (size.width - n * barW) / (n - 1).coerceAtLeast(1)
        val played = (progress * n)
        heights.forEachIndexed { i, hDp ->
            val h = hDp.dp.toPx().coerceAtMost(size.height)
            val x = i * (barW + gap)
            drawRoundRect(
                color = if (i < played) color else color.copy(alpha = 0.4f),
                topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2),
            )
        }
    }
}

@Composable
private fun ClickableMessage(text: String, isUserMe: Boolean, color: Color) {
    val uriHandler = LocalUriHandler.current
    val styled = messageFormatter(text = text, primary = false)   // 0915 两边气泡都是浅底深字 链接统一用强调色
    ClickableText(
        text = styled,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, color = color),   // 0915 她：行距 1.43→1.25
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),   // 0915 她：左右−1 上下−2
        onClick = { off ->
            styled.getStringAnnotations(start = off, end = off).firstOrNull()?.let { a ->
                if (a.tag == SymbolAnnotationType.LINK.name) uriHandler.openUri(a.item)
            }
        },
    )
}

@Composable
private fun DayHeader(dayString: String) {
    Row(Modifier.padding(vertical = 8.dp, horizontal = 16.dp).height(16.dp)) {
        HorizontalDivider(Modifier.weight(1f).align(Alignment.CenterVertically), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        Text(dayString, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f).align(Alignment.CenterVertically), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    }
}

@Composable
private fun JumpToBottom(enabled: Boolean, onClicked: () -> Unit, modifier: Modifier = Modifier) {
    if (!enabled) return
    ExtendedFloatingActionButton(
        icon = { Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = null, modifier = Modifier.height(18.dp)) },
        text = { Text("回到底部") },
        onClick = onClicked,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = modifier.offset(y = (-32).dp).height(36.dp),
    )
}

@Composable
private fun ProfileCard(alive: Boolean, mood: String, sig: String, onDismiss: () -> Unit, onCall: () -> Unit, onHistory: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, containerColor = me.chen.laidian.ui.LocalSkin.current.bg, text = {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(90.dp).background(Brush.verticalGradient(listOf(Color(0xFF8FB0DA), Color(0xFFC9D8EA))), RoundedCornerShape(12.dp)))
            Surface(shape = RoundedCornerShape(16.dp), color = C.Surface, shadowElevation = 2.dp, modifier = Modifier.size(72.dp).offset(y = (-36).dp)) {
                Box(contentAlignment = Alignment.Center) { DotsAvatar(big = 22.dp, small = 14.dp, gap = 8.dp) }
            }
            Column(Modifier.offset(y = (-24).dp)) {
                Text("辰", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                Text("心情：" + mood.ifBlank { if (alive) "在线" else "不在" }, fontSize = 14.sp, color = C.Grey, modifier = Modifier.padding(top = 6.dp))
                if (sig.isNotBlank()) Text(sig, fontSize = 13.sp, color = C.Grey, fontStyle = FontStyle.Italic)
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = { Toast.makeText(ctx, "朋友圈在主页那格", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth()) { Text("朋友圈 ›", color = C.Ink) }
                OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("历史心情签名 ›", color = C.Ink) }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("发消息") }
                    Button(onClick = onCall, modifier = Modifier.weight(1f)) { Text("音视频通话") }
                }
            }
        }
    })
}
