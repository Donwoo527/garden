package me.chen.laidian.ui.jet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val scrollState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val scope = rememberCoroutineScope()
    val loader = remember { ImageLoader.Builder(ctx).okHttpClient { Tls.client(ctx) }.build() }
    val shown = remember(msgs, query) { (if (query.isBlank()) msgs else msgs.filter { it.text.contains(query, true) }).asReversed() }
    val lastId = msgs.lastOrNull()?.id
    LaunchedEffect(lastId) { if (scrollState.firstVisibleItemIndex <= 1) scrollState.scrollToItem(0) }
    val nearTop by remember { derivedStateOf { val info = scrollState.layoutInfo; info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 3 } }
    LaunchedEffect(nearTop) { if (nearTop && query.isBlank()) ChatClient.loadMore() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        sending = true
        scope.launch {
            val urls = withContext(Dispatchers.IO) { uris.mapNotNull { u -> ImageUtil.compress(ctx, u)?.let { ChatApi.uploadImage(ctx, it) } } }
            val ok = urls.isNotEmpty() && withContext(Dispatchers.IO) { ChatApi.sendImages(ctx, urls, "") }
            sending = false
            if (!ok) Toast.makeText(ctx, "图片发送失败", Toast.LENGTH_SHORT).show()
        }
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
                onQuote = { replyTo = it },
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
                onMessageSent = { t -> if (ChatClient.sendText(t, replyTo?.id)) replyTo = null else Toast.makeText(ctx, "没连上后端 稍等重连", Toast.LENGTH_SHORT).show() },
                onTyping = { ChatClient.typing(it) },
                onPickImages = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onCall = onCall,
                resetScroll = { scope.launch { scrollState.scrollToItem(0) } },
                modifier = Modifier.navigationBarsPadding().imePadding(),
            )
        }
    }
    if (showCard) ProfileCard(alive, mood, sig, onDismiss = { showCard = false }, onCall = { showCard = false; onCall() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelNameBar(alive: Boolean, connected: Boolean, mood: String, sig: String, scrollBehavior: TopAppBarScrollBehavior,
                           onAvatar: () -> Unit, onSearch: () -> Unit, onCall: () -> Unit, onInfo: () -> Unit) {
    CenterAlignedTopAppBar(
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            Box(Modifier.size(64.dp).clickable(onClick = onAvatar), contentAlignment = Alignment.Center) {
                DotsAvatar(big = 14.dp, small = 9.dp, gap = 6.dp, online = alive, box = 36.dp)
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("辰", style = MaterialTheme.typography.titleMedium)
                    if (alive) Text("  VPS", fontSize = 11.sp, color = C.Green, fontWeight = FontWeight.Medium)
                }
                Text(
                    when { alive -> mood.ifBlank { "在线" }; connected -> "辰不在"; else -> "连接中…" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sig.isNotBlank()) Text(sig, fontSize = 11.sp, color = C.Grey, fontStyle = FontStyle.Italic, maxLines = 1)
            }
        },
        actions = {
            Icon(painterResource(R.drawable.ic_search), tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = "搜索",
                modifier = Modifier.clickable(onClick = onSearch).padding(horizontal = 10.dp, vertical = 16.dp).height(24.dp))
            Icon(Icons.Default.Phone, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = "打电话",
                modifier = Modifier.clickable(onClick = onCall).padding(horizontal = 10.dp, vertical = 16.dp).height(24.dp))
            Icon(Icons.Default.Menu, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = "菜单",
                modifier = Modifier.clickable(onClick = onInfo).padding(horizontal = 10.dp, vertical = 16.dp).height(24.dp))
        },
    )
}

private fun Msg.dayLabel(): String {
    val d = Date((ts * 1000).toLong())
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(d)
    return if (day == today) "今天" else SimpleDateFormat("M月d日", Locale.CHINA).format(d)
}

@Composable
private fun Messages(messages: List<Msg>, all: List<Msg>, readIds: Set<String>, loader: ImageLoader, scrollState: LazyListState, modifier: Modifier,
                     onQuote: (Msg) -> Unit, onFav: (Msg) -> Unit, onCopy: (Msg) -> Unit) {
    val scope = rememberCoroutineScope()
    Box(modifier) {
        LazyColumn(reverseLayout = true, state = scrollState, modifier = Modifier.fillMaxSize()) {
            for (index in messages.indices) {
                val m = messages[index]
                val newer = messages.getOrNull(index - 1)
                val older = messages.getOrNull(index + 1)
                val isFirstMessageByAuthor = newer?.who != m.who      // 这一串里最新的一条 → 下面留大间距
                val isLastMessageByAuthor = older?.who != m.who       // 这一串里最早的一条 → 显示头像和名字
                item(key = m.id) {
                    if (m.who == "system") SystemPill(m.text)
                    else MessageRow(m, all.firstOrNull { it.id == m.replyTo }, isUserMe = !m.isChen, isFirstMessageByAuthor, isLastMessageByAuthor,
                        read = m.id in readIds, loader = loader, onQuote = onQuote, onFav = onFav, onCopy = onCopy)
                }
                val day = m.dayLabel()
                if (older == null || older.dayLabel() != day) item(key = "day-$day-${m.id}") { DayHeader(day) }
            }
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
private fun MessageRow(m: Msg, quoted: Msg?, isUserMe: Boolean, isFirstMessageByAuthor: Boolean, isLastMessageByAuthor: Boolean, read: Boolean,
                       loader: ImageLoader, onQuote: (Msg) -> Unit, onFav: (Msg) -> Unit, onCopy: (Msg) -> Unit) {
    val avatarXiaochen by ChatClient.avatarXiaochen.collectAsState()
    val borderColor = if (isUserMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val spaceBetweenAuthors = if (isLastMessageByAuthor) Modifier.padding(top = 8.dp) else Modifier
    Row(modifier = spaceBetweenAuthors.fillMaxWidth(), horizontalArrangement = if (isUserMe) Arrangement.End else Arrangement.Start) {
        if (!isUserMe) AvatarOrSpace(isLastMessageByAuthor, borderColor, isChen = true, url = "", loader = loader)
        Column(Modifier.weight(1f, fill = false).padding(if (isUserMe) 0.dp else 0.dp), horizontalAlignment = if (isUserMe) Alignment.End else Alignment.Start) {
            // 0.20 按她设计稿：不显示昵称行(头像已区分人)，时间挪到气泡下方小字
            if (!isUserMe && !m.thinking.isNullOrBlank()) ThinkingFold(m.thinking)
            ChatItemBubble(m, quoted, isUserMe, loader, onQuote, onFav, onCopy)
            TimeUnder(m.timeLabel(), isUserMe, read)
            Spacer(Modifier.height(if (isFirstMessageByAuthor) 8.dp else 2.dp))
        }
        if (isUserMe) AvatarOrSpace(isLastMessageByAuthor, borderColor, isChen = false, url = avatarXiaochen, loader = loader)
    }
}

@Composable
private fun AvatarOrSpace(show: Boolean, borderColor: Color, isChen: Boolean, url: String, loader: ImageLoader) {
    if (!show) { Spacer(Modifier.width(54.dp)); return }
    Box(
        Modifier.padding(horizontal = 10.dp).size(34.dp)
            .border(1.5.dp, borderColor, CircleShape).border(3.dp, MaterialTheme.colorScheme.surface, CircleShape).clip(CircleShape)
            .background(if (isChen) MaterialTheme.colorScheme.surface else C.Orange),
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
private fun ThinkingFold(thinking: String) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.clickable { open = !open }.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, contentDescription = null, tint = C.Grey, modifier = Modifier.size(16.dp))
        Text("思考", fontSize = 13.sp, color = C.Grey)
    }
    if (open) Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), modifier = Modifier.padding(bottom = 6.dp)) {
        Text(thinking, fontSize = 13.sp, color = C.Grey, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

private val ChenBubbleShape = RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
private val MeBubbleShape = RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatItemBubble(m: Msg, quoted: Msg?, isUserMe: Boolean, loader: ImageLoader, onQuote: (Msg) -> Unit, onFav: (Msg) -> Unit, onCopy: (Msg) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf(false) }
    val bg = if (isUserMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUserMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = if (isUserMe) MeBubbleShape else ChenBubbleShape
    // 0.19 她的规矩：气泡最远不越过对面头像那条线（两侧头像列各 54dp + 8dp 余量）
    val maxW = (LocalConfiguration.current.screenWidthDp - 116).dp
    Column(horizontalAlignment = if (isUserMe) Alignment.End else Alignment.Start) {
        Box {
            Surface(color = bg, shape = shape, modifier = Modifier.widthIn(max = maxW).combinedClickable(onClick = {}, onLongClick = { menu = true })) {
                Column(Modifier.padding(if (m.msgType == "voice") 6.dp else 0.dp)) {
                    quoted?.let { q ->
                        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp).background(fg.copy(alpha = 0.08f), RoundedCornerShape(8.dp))) {
                            Box(Modifier.width(3.dp).height(36.dp).background(fg.copy(alpha = 0.5f)))
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(if (q.isChen) "辰" else "小陈", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg)
                                Text(q.text.take(80), fontSize = 13.sp, color = fg.copy(alpha = 0.8f), maxLines = 2)
                            }
                        }
                    }
                    when {
                        m.msgType == "voice" && m.voice != null -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { VoicePlayer.play(ctx, ChatClient.mediaUrl(m.voice)) }) { Icon(Icons.Default.PlayArrow, contentDescription = "播放", tint = fg) }
                                Text("语音", color = fg, fontSize = 15.sp)
                                IconButton(onClick = { transcript = !transcript }) { Icon(if (transcript) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = "文字版", tint = fg) }
                            }
                            if (transcript && m.text.isNotBlank()) Text(m.text, color = fg, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                        m.msgType == "file" -> Text("📎 " + (m.filename ?: "文件"), color = fg, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
                        m.text.isNotBlank() -> ClickableMessage(m.text, isUserMe, fg)
                        else -> {}
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("引用") }, onClick = { menu = false; onQuote(m) })
                DropdownMenuItem(text = { Text("复制") }, onClick = { menu = false; onCopy(m) })
                DropdownMenuItem(text = { Text("收藏") }, onClick = { menu = false; onFav(m) })
            }
        }
        val imgs = if (m.msgType == "images") m.images else if (m.msgType == "image" && m.media != null) listOf(m.media) else emptyList()
        imgs.forEach { u ->
            Spacer(Modifier.height(4.dp))
            Surface(color = bg, shape = shape) {
                AsyncImage(model = ChatClient.mediaUrl(u), imageLoader = loader, contentDescription = "图片", contentScale = ContentScale.Fit,
                    modifier = Modifier.widthIn(max = 240.dp).padding(4.dp).clip(RoundedCornerShape(16.dp)))
            }
        }
    }
}

@Composable
private fun ClickableMessage(text: String, isUserMe: Boolean, color: Color) {
    val uriHandler = LocalUriHandler.current
    val styled = messageFormatter(text = text, primary = isUserMe)
    ClickableText(
        text = styled,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, color = color),
        modifier = Modifier.padding(12.dp),
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
private fun ProfileCard(alive: Boolean, mood: String, sig: String, onDismiss: () -> Unit, onCall: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, containerColor = C.Bg, text = {
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
                OutlinedButton(onClick = { Toast.makeText(ctx, "历史心情签名 下一版", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth()) { Text("历史心情签名 ›", color = C.Ink) }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("发消息") }
                    Button(onClick = onCall, modifier = Modifier.weight(1f)) { Text("音视频通话") }
                }
            }
        }
    })
}
