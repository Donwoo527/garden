package me.chen.laidian.ui.jet
import me.chen.laidian.ui.ProfileHistoryDialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import me.chen.laidian.ui.LocalSkin
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
    var collapseTick by remember { mutableIntStateOf(0) }   // 0920 她：点消息区任何地方收起表情/加号面板 每加一收一次
    var panelOpen by remember { mutableStateOf(false) }   // 0920 面板开着时点气泡只收面板 不弹表态条
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    // 0.116 分享进来的图在 ShareInbox 里后台传：切回聊天也要看得见在传
    val shareSending by me.chen.laidian.ShareInbox.sending.collectAsState()
    // 0915 她：点开图能翻整个聊天里的图——看图器放在这一层 拿全部图的顺序列表
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    val allImages = remember(msgs) { msgs.flatMap { m -> if (m.msgType == "images") m.images else if (m.msgType == "image" && m.media != null) listOf(m.media) else emptyList() } }
    val scrollState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val scope = rememberCoroutineScope()
    val loader = remember { ImageLoader.Builder(ctx).okHttpClient { Tls.client(ctx) }.build() }
    // 0926 她："搜索栏输入文字不搜"——原来只过滤已加载的一页（50 条真消息≈一小时）搜早些的自然空。改成：本地过滤 + 服务端全量搜（防抖 300ms）合并去重按时间排；
    // 不搜思考/工具行（长段英文 命中全是它们）；清空输入就恢复整个列表
    val hits by ChatClient.searchResults.collectAsState()
    LaunchedEffect(query) { if (query.isBlank()) ChatClient.search("") else { kotlinx.coroutines.delay(300); ChatClient.search(query) } }
    val shown = remember(msgs, query, hits) {
        if (query.isBlank()) msgs.asReversed()
        else {
            val local = msgs.filter { !it.isAux && it.text.contains(query, true) }
            val have = local.map { it.id }.toHashSet()
            (local + hits.filter { it.id !in have }).sortedBy { it.ts }.asReversed()
        }
    }
    // 0920 她："每次发新消息不能自动定位到底部"——原来只看一眼 firstVisibleItemIndex<=1 就 scrollToItem 一次：新条目量完高位置会漂、她自己发的也可能被判成不在底部
    // 现在：atBottom 持续算（index 0 且离底不到 120dp）；forceBottom 由她的发送动作点亮；她发的(who=xiaochen)一律到底；滚两次（等一帧再补一次）防漂；她在翻旧消息时不拽 只记 unseen 给"回到底部"按钮显示条数
    val bottomSlack = with(LocalDensity.current) { 120.dp.toPx() }
    val atBottom by remember(bottomSlack) { derivedStateOf { scrollState.firstVisibleItemIndex == 0 && scrollState.firstVisibleItemScrollOffset < bottomSlack } }
    var forceBottom by remember { mutableStateOf(false) }
    var unseen by remember { mutableIntStateOf(0) }
    val lastId = msgs.lastOrNull()?.id
    LaunchedEffect(lastId) {
        val newest = msgs.lastOrNull()
        // effect 跑在布局前：这里读到的 atBottom 是新条目插进来之前的位置；万一落在布局后（新条目已占 index 0）原来贴底那条会变成 index 1——旧代码的 <=1 就是防这个 保留这层容错
        val nearBottom = atBottom || (scrollState.firstVisibleItemIndex <= 1 && scrollState.firstVisibleItemScrollOffset < bottomSlack)
        if (forceBottom || nearBottom || newest?.who == "xiaochen") {
            scrollState.scrollToItem(0)
            withFrameNanos {}
            scrollState.scrollToItem(0)
            unseen = 0
        } else if (newest != null && !newest.isAux) unseen++   // 思考行/工具行不算新消息
        forceBottom = false
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = 0 }
    val nearTop by remember { derivedStateOf { val info = scrollState.layoutInfo; info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 3 } }
    // 0916 她："翻到顶就不动了"：一页折叠的思考行太矮 加载完视口仍在顶 nearTop 不变就不再触发——加上 msgs.size 每来一页重判 直到填满或没有更多
    LaunchedEffect(nearTop, msgs.size) { if (nearTop && query.isBlank()) ChatClient.loadMore() }
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
        forceBottom = true   // 0920 她发的到了就滚到底：先点亮 失败再灭（ws 回显可能比 HTTP 应答先到）
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
            if (!ok) { forceBottom = false; Toast.makeText(ctx, "图片发送失败：" + (ChatApi.lastError ?: if (urls.isEmpty()) "读图/压缩失败" else "发送被拒"), Toast.LENGTH_LONG).show() }
            // 0915 教训：兜底路径不能静默——走了"原样上传"就得说出来，不然根因被盖住（这次盖了一整天）
            else if (!original && ChatApi.lastError != null) Toast.makeText(ctx, "发了 但压缩没走通（${ChatApi.lastError}）发的是原图", Toast.LENGTH_LONG).show()
        }
    }
    // 0915 ③ 文件（不压 服务端直接入库当她发的一条）和拍照（系统相机预览图 → JPEG 88）
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        sending = true
        forceBottom = true   // 0920 同上
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
            if (!ok) { forceBottom = false; Toast.makeText(ctx, "文件发送失败：" + (ChatApi.lastError ?: "读不到文件"), Toast.LENGTH_LONG).show() }
        }
    }
    // 0915 拍照全尺寸：相机把原图写进 cache/camera/ 再走和相册一样的压缩上传（0.62 用的预览图只有 144×192）
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok0 ->
        val u = cameraUri
        if (!ok0 || u == null) return@rememberLauncherForActivityResult
        sending = true
        forceBottom = true   // 0920 同上
        scope.launch {
            ChatApi.lastError = null
            val ok = withContext(Dispatchers.IO) {
                ImageUtil.compressOrRawUpload(ctx, u)?.let { ChatApi.sendImages(ctx, listOf(it), "") } ?: false
            }
            sending = false
            if (!ok) { forceBottom = false; Toast.makeText(ctx, "拍照发送失败：" + (ChatApi.lastError ?: ""), Toast.LENGTH_LONG).show() }
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
                // 0926 给个数：没命中也看得出"搜过了"而不是"没搜"
                if (query.isNotBlank()) Text("找到 ${shown.size} 条", fontSize = 12.sp, color = LocalSkin.current.muted, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalPanelOpen provides panelOpen) {
            Messages(shown, msgs, readIds, loader, scrollState, Modifier.weight(1f), unseen = unseen,
                onAnyTap = { collapseTick++ },
                onOpenImage = { viewerUrl = it },
                onQuote = { replyTo = it },
                onForward = { forwardText = it.text },
                // 0920 她：图长按→转发 = 这张图原样再发一条（和发表情包同一条路）
                onForwardImage = { u -> forceBottom = true; scope.launch { ChatApi.lastError = null; val ok = withContext(Dispatchers.IO) { ChatApi.sendImages(ctx, listOf(u), "") }; if (!ok) forceBottom = false; Toast.makeText(ctx, if (ok) "已转发" else "转发失败：" + (ChatApi.lastError ?: ""), Toast.LENGTH_SHORT).show() } },
                onFav = { m -> scope.launch { val ok = withContext(Dispatchers.IO) { ChatApi.addFavorite(ctx, m) }; Toast.makeText(ctx, if (ok) "已收藏" else "收藏失败", Toast.LENGTH_SHORT).show() } },
                onCopy = { m -> (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("msg", m.text)); Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show() })
            }
            if (sending || shareSending) Text("图片上传中…", fontSize = 12.sp, color = LocalSkin.current.muted, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            if (status == "thinking") Text("辰在想…", fontSize = 12.sp, color = LocalSkin.current.muted, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            replyTo?.let { q ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("引用 ${if (q.isChen) "辰" else "小陈"}：${q.text.take(40)}", fontSize = 12.sp, color = LocalSkin.current.muted, modifier = Modifier.weight(1f))
                    IconButton(onClick = { replyTo = null }) { Icon(Icons.Default.Close, contentDescription = "取消引用") }
                }
            }
            JetUserInput(
                insertText = forwardText,
                onInsertConsumed = { forwardText = null },
                collapseTick = collapseTick,
                onPanelOpen = { panelOpen = it },
                onSendSticker = { url -> forceBottom = true; scope.launch { val ok = withContext(Dispatchers.IO) { ChatApi.sendImages(ctx, listOf(url), "") }; if (!ok) forceBottom = false } },
                // 0926 返回发没发出去：输入框只在发出去时才清草稿
                onMessageSent = { t -> if (ChatClient.sendText(t, replyTo?.id)) { replyTo = null; forceBottom = true; true } else { Toast.makeText(ctx, "没连上后端 稍等重连", Toast.LENGTH_SHORT).show(); false } },
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

/** 0926 她点的：列表里连着的思考行并成一条「💭 思考了 N 秒」、连着的工具行并成一条「🔧 跑了 N 件事」。
 *  Messages 先把 messages 切成这些行再喂 LazyColumn；一组的 key 用组里最新那条的 id */
private sealed class ChatRow {
    abstract val newest: Msg   // 时间上最新的一条：key / 时间标签 / 跟更新的邻居比 用它
    abstract val oldest: Msg   // 时间上最早的一条：跟更早的邻居比 用它
    data class One(val m: Msg) : ChatRow() { override val newest get() = m; override val oldest get() = m }
    /** items 按时间从早到晚 */
    data class Group(val type: String, val items: List<Msg>) : ChatRow() { override val newest get() = items.last(); override val oldest get() = items.first() }
}

/** messages 倒序（index 0 最新）→ 同样倒序的行列表。思考/工具行：同类型、中间没别的消息、同一天的连成一组（思考组和工具组交替出现时各归各）；其它消息各自一行。
 *  分页翻进更老的一批时 messages 整个重算 所以页边上被切开的组会自动接上 */
private fun groupRows(messages: List<Msg>): List<ChatRow> {
    val out = ArrayList<ChatRow>(messages.size)
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        if (!m.isAux) { out += ChatRow.One(m); i++; continue }
        val day = m.dayLabel()
        var j = i + 1
        while (j < messages.size && messages[j].msgType == m.msgType && messages[j].dayLabel() == day) j++
        out += ChatRow.Group(m.msgType, messages.subList(i, j).asReversed().toList())
        i = j
    }
    return out
}

@Composable
private fun Messages(messages: List<Msg>, all: List<Msg>, readIds: Set<String>, loader: ImageLoader, scrollState: LazyListState, modifier: Modifier, unseen: Int,
                     onAnyTap: () -> Unit, onOpenImage: (String) -> Unit, onQuote: (Msg) -> Unit, onForward: (Msg) -> Unit, onForwardImage: (String) -> Unit, onFav: (Msg) -> Unit, onCopy: (Msg) -> Unit) {
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onAnyTap)
    // 0920 她：点消息区任何地方（气泡/空白都算）收起表情/加号面板。走 Initial 通道只看不消费：按下→抬起、位移没过 touchSlop、不是长按、单指 才算一次点；
    // 列表滚动（有位移）、气泡长按菜单、左滑引用、图片点开 全都照旧不受影响
    Box(modifier.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        val longPress = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var moved = false
            var multi = false
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (ev.changes.size > 1) multi = true
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if ((ch.position - down.position).getDistance() > slop) moved = true
                if (!ch.pressed) {
                    if (!moved && !multi && ch.uptimeMillis - down.uptimeMillis < longPress) tap()
                    break
                }
            }
        }
    }) {
        // 0926 她点的：连着的思考行 / 工具行各并成一组一行小字（groupRows）；rows 跟 messages 一样倒序 index 0 最新
        val rows = remember(messages) { groupRows(messages) }
        LazyColumn(reverseLayout = true, state = scrollState, modifier = Modifier.fillMaxSize()) {
            for (index in rows.indices) {
                val row = rows[index]
                val m = row.newest                                   // 这一行的代表：key / 时间标签 用组里最新那条（单条就是它自己）
                val newer = rows.getOrNull(index - 1)?.oldest        // 时间上紧挨着的更新一条（那边是组就取组里最早的）
                val older = rows.getOrNull(index + 1)?.newest        // 时间上紧挨着的更早一条（那边是组就取组里最新的）
                val isFirstMessageByAuthor = newer?.who != m.who      // 这一串里最新的一条 → 下面留大间距
                val isLastMessageByAuthor = older?.who != m.who       // 这一串里最早的一条 → 显示头像和名字
                // 0915 她：同一个人同一分钟连发的 只在最后一条下面标时间
                val showTime = newer == null || newer.who != m.who || newer.timeLabel() != m.timeLabel()
                item(key = m.id) {
                    when (row) {
                        is ChatRow.Group -> AuxRow(row, isLastMessageByAuthor, showTime, loader)
                        is ChatRow.One -> {
                            // 0920 服务端给带 reply_to 的消息附了引用快照 quote{id,who,text}：被引用那条不在本地列表里（老消息/没翻到）就用快照拼一条只够引用框渲染的 Msg 兜底（引用框只读 id/isChen/text/msgType）
                            val quoted = all.firstOrNull { it.id == m.replyTo } ?: m.quoteText?.let { Msg(id = m.replyTo ?: "", who = m.quoteWho ?: "chen", msgType = "text", text = it, media = null, voice = null, replyTo = null, thinking = null, ts = 0.0) }
                            if (m.who == "system") SystemPill(m.text)
                            else MessageRow(m, quoted, isUserMe = !m.isChen, isFirstMessageByAuthor, isLastMessageByAuthor, showTime = showTime,
                                read = m.id in readIds, loader = loader, onOpenImage = onOpenImage, onQuote = onQuote, onForward = onForward, onForwardImage = onForwardImage, onFav = onFav, onCopy = onCopy)
                        }
                    }
                }
                val day = m.dayLabel()   // 组不跨天（groupRows 按天切）所以看最新那条就够
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
        JumpToBottom(enabled = jumpEnabled, unseen = unseen, onClicked = { scope.launch { scrollState.animateScrollToItem(0) } }, modifier = Modifier.align(Alignment.BottomEnd))   // 0926 右下角圆钮
    }
}

@Composable
private fun SystemPill(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)) {
            Text(text, fontSize = 13.sp, color = LocalSkin.current.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(m: Msg, quoted: Msg?, isUserMe: Boolean, isFirstMessageByAuthor: Boolean, isLastMessageByAuthor: Boolean, showTime: Boolean, read: Boolean,
                       loader: ImageLoader, onOpenImage: (String) -> Unit, onQuote: (Msg) -> Unit, onForward: (Msg) -> Unit, onForwardImage: (String) -> Unit, onFav: (Msg) -> Unit, onCopy: (Msg) -> Unit) {
    val avatarXiaochen by ChatClient.avatarXiaochen.collectAsState()
    val borderColor = if (isUserMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    // 0.113 她 0928 截图："对话空白间隔有点大，可以小点"——换人处上 8→4、下 8→5（合计 16→9），同一人连发的 3 不动
    val spaceBetweenAuthors = if (isLastMessageByAuthor) Modifier.padding(top = 4.dp) else Modifier
    // 0926 思考行/工具行不走这里：Messages 里并成组交给 AuxRow
    Row(modifier = spaceBetweenAuthors.fillMaxWidth(), horizontalArrangement = if (isUserMe) Arrangement.End else Arrangement.Start) {
        if (!isUserMe) AvatarOrSpace(isLastMessageByAuthor, borderColor, isChen = true, url = "", loader = loader)
        Column(Modifier.weight(1f, fill = false).padding(if (isUserMe) 0.dp else 0.dp), horizontalAlignment = if (isUserMe) Alignment.End else Alignment.Start) {
            // 0.20 按她设计稿：不显示昵称行(头像已区分人)，时间挪到气泡下方小字
            if (!isUserMe && !m.thinking.isNullOrBlank()) ThinkingFold(m.thinking)
            ChatItemBubble(m, quoted, isUserMe, loader, onOpenImage, onQuote, onForward, onForwardImage, onFav, onCopy)
            if (showTime) TimeUnder(m.timeLabel(), isUserMe, read)
            Spacer(Modifier.height(if (isFirstMessageByAuthor) 5.dp else 3.dp))
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
        Text(if (read) "✓✓" else "✓", fontSize = 11.sp, color = if (read) C.Green else LocalSkin.current.muted)
    }
}

/** 0.20 设计稿位置：时间贴在气泡正下方，她的消息带已读双勾 */
@Composable
private fun TimeUnder(time: String, isUserMe: Boolean, read: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Text(time, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isUserMe) {
            Spacer(Modifier.width(4.dp))
            Text(if (read) "✓✓" else "✓", fontSize = 11.sp, color = if (read) C.Green else LocalSkin.current.muted)
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
                Text("思考", fontSize = 10.sp, color = LocalSkin.current.muted)
            }
            Text(thinking, fontSize = 12.sp, color = LocalSkin.current.muted, lineHeight = 16.sp, maxLines = if (open) Int.MAX_VALUE else 3, overflow = if (open) TextOverflow.Clip else TextOverflow.Ellipsis)
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
                tr?.let { Text(it, fontSize = 12.sp, color = LocalSkin.current.muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

@Composable
private fun ThinkingFold(thinking: String, label: String = "思考") {
    // 0926 22:46 她定的：思考链折叠时不要框，跟以前一样小灰字；只有工具行才画框
    var open by remember { mutableStateOf(false) }
    var tr by remember(thinking) { mutableStateOf<String?>(null) }
    var showTr by remember(thinking) { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val muted = LocalSkin.current.muted
    Row(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = !open }.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("💭", fontSize = 10.sp, modifier = Modifier.padding(end = 2.dp))
        Text(label, fontSize = 10.sp, color = muted)
    }
    if (open) Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), modifier = Modifier.padding(bottom = 6.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(if (showTr) tr ?: thinking else thinking, fontSize = 13.sp, color = muted, lineHeight = 17.sp)
            Text(
                if (showTr) "原文" else "翻译", fontSize = 12.sp, color = LocalSkin.current.accent,
                modifier = Modifier.padding(top = 4.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (showTr) showTr = false
                    else if (tr != null) showTr = true
                    else scope.launch {
                        ChatApi.lastError = null
                        val t = withContext(Dispatchers.IO) { ChatApi.translate(ctx, thinking) }
                        if (t != null) { tr = t; showTr = true } else Toast.makeText(ctx, "翻译失败：" + (ChatApi.lastError ?: ""), Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
}

/** 0926 思考组 / 工具组：占原来思考行的位置（头像列 + 280 宽）；卡片样式见 FoldCard */
@Composable
private fun AuxRow(row: ChatRow.Group, isLastMessageByAuthor: Boolean, showTime: Boolean, loader: ImageLoader) {
    val spaceBetweenAuthors = if (isLastMessageByAuthor) Modifier.padding(top = 4.dp) else Modifier   // 0.113 跟 MessageRow 一起收紧
    Row(modifier = spaceBetweenAuthors.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        AvatarOrSpace(isLastMessageByAuthor, MaterialTheme.colorScheme.tertiary, isChen = true, url = "", loader = loader)
        Column(Modifier.weight(1f, fill = false).widthIn(max = 280.dp), horizontalAlignment = Alignment.Start) {
            if (row.type == "thinking") {
                // 秒数 = 各段相加，1位小数；一段都没有就只写「思考」。点开把各段按时间顺序接起来 段间空一行（翻译按钮在 ThinkingFold 里 照旧）
                val secs = row.items.mapNotNull { it.secs }.takeIf { it.isNotEmpty() }?.sum()
                val label = if (secs != null) "思考了 " + String.format(Locale.US, "%.1f", secs) + " 秒" else "思考"
                ThinkingFold(row.items.joinToString("\n\n") { it.text }, label = label)
                if (showTime) TimeUnder(row.newest.timeLabel(), false, false)   // 工具卡片头一行自己带时间了(FoldCard 的 time) 这里只补思考卡片缺的
            } else ToolFold(row.items)
            Spacer(Modifier.height(3.dp))
        }
    }
}

/** 0926 工具行：一条「做了件事 · 读 memory.md」；连着的并成「做了 N 件事」。点开展开成清单 一行一件：图标 + 描述 + 这条自己的时间 */
@Composable
private fun ToolFold(items: List<Msg>) {
    var open by remember { mutableStateOf(false) }
    val muted = LocalSkin.current.muted
    val head = if (items.size == 1) "做了件事 · " + items[0].text else "做了 ${items.size} 件事"
    // 组里工具都一样就用那个图标，混着就用通用图标；items 按时间从早到晚(ChatRow.Group 的约定)，最后一条=最新
    val headIcon = items.map { it.tool }.distinct().singleOrNull()?.let { toolIcon(it) } ?: toolIcon(null)
    FoldCard(
        open = open, onToggle = { open = !open },
        leading = { Icon(headIcon, contentDescription = null, tint = muted, modifier = Modifier.size(20.dp)) },
        headText = head, headMaxWidth = 155.dp, time = items.last().timeLabel(),
    ) {
        items.forEach { t ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(toolIcon(t.tool), contentDescription = null, tint = muted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text(t.text, fontSize = 13.sp, color = muted, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Text(t.timeLabel(), fontSize = 11.sp, color = muted.copy(alpha = 0.7f))
            }
        }
    }
}

/** 0926 工具图标映射：material-icons-extended 没进依赖（app/build.gradle.kts 只有核心 material3/ui）。
 *  实测 ~/.gradle 缓存里的 material-icons-core-1.6.8.jar，核心 Outlined 集合里没有 Description/Terminal/Psychology
 *  （原说明里给的备选 Description 其实也不在，是查实后才发现的）。Read 本来想用 List 更贴切，但 Icons.Outlined.List
 *  这个名字会跟 kotlin.collections.List（这个文件到处用它当类型）撞名，没法编译验证 import 别名到底稳不稳，干脆换 Info 躲开这整类风险。
 *  只用查到确认存在的：Build/Edit/Search/Send/Person/Info；Bash 想要的 Terminal 不在核心里，退成 Build；未识别的工具也是 Build */
private fun toolIcon(tool: String?) = when {
    tool == "Edit" || tool == "Write" || tool == "MultiEdit" -> Icons.Outlined.Edit
    tool == "Read" -> Icons.Outlined.Info
    tool == "Grep" || tool == "Glob" -> Icons.Outlined.Search
    tool == "Agent" -> Icons.Outlined.Person
    tool != null && (tool.startsWith("mcp__plugin_telegram") || tool.startsWith("mcp__plugin_discord")) -> Icons.Outlined.Send
    else -> Icons.Outlined.Build   // Bash / null / 其它没列的工具
}

/** 0926 思考行/工具行共用外壳：卡片颜色圆角走皮肤（raised()——换皮跟着变，不搞毛玻璃，跟辰气泡一样是"这一套皮肤的卡片质感"）。
 *  头一行：左图标 + 文字(单行超长省略号) + 右侧(可选时间) + 展开箭头(展开转180°变朝上)；点开在同一张卡片内向下展开，不是另起一张卡片 */
@Composable
private fun FoldCard(
    open: Boolean, onToggle: () -> Unit,
    leading: @Composable () -> Unit, headText: String, headMaxWidth: Dp, time: String? = null,
    expanded: @Composable () -> Unit,
) {
    val muted = LocalSkin.current.muted
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "foldArrow")
    val cardWidth = Modifier.widthIn(min = 200.dp, max = 280.dp)   // 宽度跟辰的气泡一致：最大 280 内容短就包内容 最小 200
    // 0926 22:48 她定的：工具框不要凸起效果 就平面——去掉 raised()，用浅色底+圆角+细边
    Column(Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(22.dp))) {
        Row(
            cardWidth.height(44.dp).padding(horizontal = 14.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leading()
                Spacer(Modifier.width(12.dp))
                Text(headText, fontSize = 14.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = headMaxWidth))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                time?.let { Text(it, fontSize = 11.sp, color = muted, modifier = Modifier.padding(end = 4.dp)) }
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = if (open) "收起" else "展开", tint = muted,
                    modifier = Modifier.size(18.dp).rotate(rotation))
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column(cardWidth.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 10.dp)) { expanded() }
        }
    }
}

// 0915 她：四个角都圆 不留那个尖角
private val ChenBubbleShape = RoundedCornerShape(20.dp)
private val MeBubbleShape = RoundedCornerShape(20.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatItemBubble(m: Msg, quoted: Msg?, isUserMe: Boolean, loader: ImageLoader, onOpenImage: (String) -> Unit, onQuote: (Msg) -> Unit, onForward: (Msg) -> Unit, onForwardImage: (String) -> Unit, onFav: (Msg) -> Unit, onCopy: (Msg) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var reactBar by remember { mutableStateOf(false) }
    val panelOpen = LocalPanelOpen.current   // 0920 她：轻点气泡弹一排表态（TG 那种）
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
                .combinedClickable(onClick = { if (!panelOpen) reactBar = true }, onLongClick = { menu = true })) {   // 0920 轻点=表态条（语音/文件气泡和文字留白区走这里）
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
                        m.text.isNotBlank() -> ClickableMessage(m.text, isUserMe, fg, onLongPress = { menu = true }, onTap = { if (!panelOpen) reactBar = true })   // 0920 她：文字长按开菜单 轻点（没点到链接）弹表态条
                        else -> {}
                    }
                    translated?.let { t ->
                        HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = fg.copy(alpha = 0.15f))
                        Text(t, fontSize = 13.sp, lineHeight = 17.sp, color = fg.copy(alpha = 0.78f), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
            // 0920 她：轻点弹出的表态条；长按菜单顶上也放同一排（TG 同款）
            DropdownMenu(expanded = reactBar, onDismissRequest = { reactBar = false }) {
                ReactionRow { e -> reactBar = false; ChatClient.react(m.id, e) }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ReactionRow { e -> menu = false; ChatClient.react(m.id, e) }
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
            // 0915 她：图不套气泡、缩到 80、点开全屏能翻能存、连发的叠一摞（MediaViews.kt）；长按→存为表情/收藏/转发(0920)
            MessageImages(imgs, loader, onOpen = onOpenImage, onSticker = { u ->
                dragScope.launch {
                    val ok = withContext(Dispatchers.IO) { ChatApi.addSticker(ctx, u) }
                    Toast.makeText(ctx, if (ok) "存进表情库了" else "没存上：" + (ChatApi.lastError ?: ""), Toast.LENGTH_SHORT).show()
                }
            }, onFav = { onFav(m) }, onForward = onForwardImage, onReact = { e -> ChatClient.react(m.id, e) })
        }
        ReactionChips(m.reactions) { e -> ChatClient.react(m.id, e) }   // 0920 气泡下的表态胶囊；Column 已按 isUserMe 左右对齐 跟着气泡那一侧
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
private fun ClickableMessage(text: String, isUserMe: Boolean, color: Color, onLongPress: () -> Unit, onTap: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val styled = messageFormatter(text = text, primary = false)   // 0915 两边气泡都是浅底深字 链接统一用强调色
    // 0920 她："文字长按没反应"——ClickableText 内部 detectTapGestures 会把按下吃掉 外层 Surface 的 combinedClickable 永远等不到没被消费的 down
    // 换普通 Text 自己接手势：长按直接开菜单 点链接照旧；padding 留在 pointerInput 外面 点在留白上的落到外层 combinedClickable 同样开菜单
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val longPress by rememberUpdatedState(onLongPress)
    val tap by rememberUpdatedState(onTap)
    Text(
        text = styled,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, color = color),   // 0915 她：行距 1.43→1.25
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp)   // 0915 她：左右−1 上下−2
            .pointerInput(styled) {
                detectTapGestures(
                    onLongPress = { longPress() },
                    onTap = { pos ->
                        // 0920 点到链接就开链接 没点到链接 = 轻点气泡 → 表态条
                        val link = layout?.getOffsetForPosition(pos)?.let { off -> styled.getStringAnnotations(start = off, end = off).firstOrNull { it.tag == SymbolAnnotationType.LINK.name } }
                        if (link != null) uriHandler.openUri(link.item) else tap()
                    },
                )
            },
        onTextLayout = { layout = it },
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

/** 0926 她点的：「回到底部」从中下方带字的宽胶囊改成右下角 40dp 白色圆钮（贴右 16dp、输入栏上方 12dp）只放向下箭头；
 *  翻旧消息时新到的条数（0920）改挂在圆钮右上角的红色角标。出现/消失条件和点击行为照旧 */
@Composable
private fun JumpToBottom(enabled: Boolean, unseen: Int = 0, onClicked: () -> Unit, modifier: Modifier = Modifier) {
    if (!enabled) return
    Box(modifier.padding(end = 16.dp, bottom = 12.dp)) {
        Box(
            Modifier.size(40.dp)
                .shadow(4.dp, CircleShape, clip = false, ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
                .clip(CircleShape).background(Color.White)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClicked),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "回到底部", tint = LocalSkin.current.ink, modifier = Modifier.size(26.dp)) }
        if (unseen > 0) Box(
            Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).sizeIn(minWidth = 18.dp, minHeight = 18.dp)
                .background(Color(0xFFE53935), CircleShape).padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) { Text(if (unseen > 99) "99+" else "$unseen", color = Color.White, fontSize = 10.sp, lineHeight = 10.sp) }
    }
}

@Composable
private fun ProfileCard(alive: Boolean, mood: String, sig: String, onDismiss: () -> Unit, onCall: () -> Unit, onHistory: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, containerColor = me.chen.laidian.ui.LocalSkin.current.bg, text = {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(90.dp).background(Brush.verticalGradient(listOf(Color(0xFF8FB0DA), Color(0xFFC9D8EA))), RoundedCornerShape(12.dp)))
            Surface(shape = RoundedCornerShape(16.dp), color = LocalSkin.current.surface, shadowElevation = 2.dp, modifier = Modifier.size(72.dp).offset(y = (-36).dp)) {
                Box(contentAlignment = Alignment.Center) { DotsAvatar(big = 22.dp, small = 14.dp, gap = 8.dp) }
            }
            Column(Modifier.offset(y = (-24).dp)) {
                Text("辰", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LocalSkin.current.ink)
                Text("心情：" + mood.ifBlank { if (alive) "在线" else "不在" }, fontSize = 14.sp, color = LocalSkin.current.muted, modifier = Modifier.padding(top = 6.dp))
                if (sig.isNotBlank()) Text(sig, fontSize = 13.sp, color = LocalSkin.current.muted, fontStyle = FontStyle.Italic)
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = { Toast.makeText(ctx, "朋友圈在主页那格", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth()) { Text("朋友圈 ›", color = LocalSkin.current.ink) }
                OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("历史心情签名 ›", color = LocalSkin.current.ink) }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("发消息") }
                    Button(onClick = onCall, modifier = Modifier.weight(1f)) { Text("音视频通话") }
                }
            }
        }
    })
}
