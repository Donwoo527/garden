package me.chen.laidian.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.Tls
import me.chen.laidian.model.Msg
import me.chen.laidian.net.ChatApi
import me.chen.laidian.net.ChatClient
import me.chen.laidian.net.ImageUtil
import me.chen.laidian.net.VoicePlayer

@Composable
fun ChatScreen(onCall: () -> Unit) {
    val ctx = LocalContext.current
    val msgs by ChatClient.messages.collectAsState()
    val connected by ChatClient.connected.collectAsState()
    val alive by ChatClient.sessionAlive.collectAsState()
    val status by ChatClient.status.collectAsState()
    val mood by ChatClient.mood.collectAsState()
    val sig by ChatClient.signature.collectAsState()
    var input by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Msg?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val loader = remember { ImageLoader.Builder(ctx).okHttpClient { Tls.client(ctx) }.build() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        sending = true
        val caption = input.trim()
        scope.launch {
            val urls = withContext(Dispatchers.IO) { uris.mapNotNull { u -> ImageUtil.compress(ctx, u)?.let { ChatApi.uploadImage(ctx, it) } } }
            val ok = urls.isNotEmpty() && withContext(Dispatchers.IO) { ChatApi.sendImages(ctx, urls, caption) }
            sending = false
            if (ok) { input = ""; replyTo = null } else Toast.makeText(ctx, "图片发送失败", Toast.LENGTH_SHORT).show()
        }
    }
    val shown = if (query.isBlank()) msgs else msgs.filter { it.text.contains(query, ignoreCase = true) }
    // reverseLayout：index 0 = 最新一条，永远贴底；新消息来时如果本来就在底部就跟着走
    val reversed = remember(shown) { shown.asReversed() }
    val lastId = msgs.lastOrNull()?.id
    LaunchedEffect(lastId) { if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0) }
    val nearTop by remember { derivedStateOf { val info = listState.layoutInfo; info.visibleItemsInfo.lastOrNull()?.index ?: -1 >= info.totalItemsCount - 3 && info.totalItemsCount > 0 } }
    LaunchedEffect(nearTop) { if (nearTop && query.isBlank()) ChatClient.loadMore() }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        Header(connected, alive, mood, sig, onCall, onSearch = { searching = !searching; if (!searching) query = "" })
        if (searching) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("搜聊天记录") }, shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        LazyColumn(
            state = listState, reverseLayout = true,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(reversed, key = { it.id }) { m ->
                MessageRow(m, msgs, loader,
                    onQuote = { replyTo = it },
                    onCopy = {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("msg", it.text))
                        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    })
            }
        }
        if (sending) Text("图片上传中…", fontSize = 12.sp, color = C.Grey, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
        if (status == "thinking") Text("辰在想…", fontSize = 12.sp, color = C.Grey, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
        replyTo?.let { q ->
            Row(Modifier.fillMaxWidth().background(C.Surface).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("引用 ${if (q.isChen) "辰" else "小陈"}：${q.text.take(40)}", fontSize = 12.sp, color = C.Grey, modifier = Modifier.weight(1f))
                IconButton(onClick = { replyTo = null }) { Icon(Icons.Default.Close, contentDescription = "取消引用") }
            }
        }
        Composer(
            value = input,
            onChange = { input = it; ChatClient.typing(it.isNotEmpty()) },
            onSend = {
                val t = input.trim()
                if (t.isNotEmpty()) {
                    if (ChatClient.sendText(t, replyTo?.id)) { input = ""; replyTo = null }
                    else Toast.makeText(ctx, "没连上后端 稍等重连", Toast.LENGTH_SHORT).show()
                }
            },
            onPlus = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

@Composable
private fun Header(connected: Boolean, alive: Boolean, mood: String, sig: String, onCall: () -> Unit, onSearch: () -> Unit) {
    Surface(color = C.Surface, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            DotsAvatar(big = 30.dp, small = 19.dp, online = alive)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("辰", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                    Spacer(Modifier.width(6.dp))
                    Text(if (alive) "VPS" else "", fontSize = 12.sp, color = C.Green, modifier = Modifier.padding(bottom = 3.dp))
                }
                Text(when { alive -> "在线"; connected -> "辰不在" ; else -> "连接中…" }, fontSize = 13.sp, color = C.Grey)
                if (sig.isNotBlank()) Text(sig, fontSize = 12.sp, color = C.Grey, fontStyle = FontStyle.Italic)
            }
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "搜索", tint = C.Ink) }
            IconButton(onClick = onCall) { Icon(Icons.Default.Phone, contentDescription = "打电话", tint = C.Ink) }
            IconButton(onClick = {}) { Icon(Icons.Default.Menu, contentDescription = "会话", tint = C.Ink) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(m: Msg, all: List<Msg>, loader: ImageLoader, onQuote: (Msg) -> Unit, onCopy: (Msg) -> Unit) {
    val ctx = LocalContext.current
    if (m.who == "system") {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(14.dp), color = C.ChenBubble) {
                Text(m.text, fontSize = 12.sp, color = C.Grey, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
        return
    }
    val mine = !m.isChen
    val fg = if (mine) Color.White else C.Ink
    var menu by remember { mutableStateOf(false) }
    var showThink by remember { mutableStateOf(false) }
    var showTranscript by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine && !m.thinking.isNullOrBlank()) {
            Row(Modifier.clickable { showThink = !showThink }.padding(start = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (showThink) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, contentDescription = null, tint = C.Grey, modifier = Modifier.size(16.dp))
                Text("思考", fontSize = 12.sp, color = C.Grey)
            }
            if (showThink) Text(m.thinking, fontSize = 12.sp, color = C.Grey, modifier = Modifier.padding(start = 8.dp, end = 24.dp, bottom = 4.dp))
        }
        Box {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (mine) C.Blue else C.ChenBubble,
                modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    m.replyTo?.let { rid ->
                        all.firstOrNull { it.id == rid }?.let { q ->
                            Column(Modifier.fillMaxWidth().background(Color(0x22000000), RoundedCornerShape(8.dp)).padding(8.dp)) {
                                Text(if (q.isChen) "辰" else "小陈", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg)
                                Text(q.text.take(60), fontSize = 12.sp, color = fg.copy(alpha = 0.85f))
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    when {
                        m.msgType == "images" && m.images.isNotEmpty() -> {
                            m.images.forEach { u -> AsyncImage(model = ChatClient.mediaUrl(u), imageLoader = loader, contentDescription = null, modifier = Modifier.widthIn(max = 260.dp).padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp))) }
                            if (m.text.isNotBlank()) Text(m.text, fontSize = 16.sp, color = fg, modifier = Modifier.padding(top = 2.dp))
                        }
                        m.msgType == "image" && m.media != null -> {
                            AsyncImage(model = ChatClient.mediaUrl(m.media), imageLoader = loader, contentDescription = null, modifier = Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(8.dp)))
                            if (m.text.isNotBlank()) Text(m.text, fontSize = 16.sp, color = fg, modifier = Modifier.padding(top = 6.dp))
                        }
                        m.msgType == "file" -> Text("📎 " + (m.filename ?: "文件"), fontSize = 15.sp, color = fg)
                        m.msgType == "voice" && m.voice != null -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { VoicePlayer.play(ctx, ChatClient.mediaUrl(m.voice)) }) { Icon(Icons.Default.PlayArrow, contentDescription = "播放", tint = fg) }
                                Text("语音", fontSize = 15.sp, color = fg)
                                IconButton(onClick = { showTranscript = !showTranscript }) {
                                    Icon(if (showTranscript) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = "文字版", tint = fg)
                                }
                            }
                            if (showTranscript && m.text.isNotBlank()) Text(m.text, fontSize = 15.sp, color = fg)
                        }
                        else -> Text(m.text, fontSize = 16.sp, color = fg, lineHeight = 23.sp)
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("引用") }, onClick = { menu = false; onQuote(m) })
                DropdownMenuItem(text = { Text("复制") }, onClick = { menu = false; onCopy(m) })
            }
        }
        Text(m.timeLabel(), fontSize = 11.sp, color = C.Grey, modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp))
    }
}

@Composable
private fun Composer(value: String, onChange: (String) -> Unit, onSend: () -> Unit, onPlus: () -> Unit) {
    Surface(color = C.Surface, shadowElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlus) { Icon(Icons.Default.Add, contentDescription = "更多", tint = C.Grey) }
            OutlinedTextField(
                value = value, onValueChange = onChange,
                modifier = Modifier.weight(1f), maxLines = 5,
                placeholder = { Text("说点什么…", color = C.Grey) },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Line, unfocusedBorderColor = C.Line),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (value.isNotBlank()) C.Blue else C.Line).clickable(enabled = value.isNotBlank(), onClick = onSend),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Send, contentDescription = "发送", tint = Color.White) }
        }
    }
}
