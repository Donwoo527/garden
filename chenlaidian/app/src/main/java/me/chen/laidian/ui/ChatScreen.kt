package me.chen.laidian.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import me.chen.laidian.Tls
import me.chen.laidian.model.Msg
import me.chen.laidian.net.ChatClient
import me.chen.laidian.net.VoicePlayer

private val ChenBubble = Color(0xFFD6ECF5)
private val MeBubble = Color(0xFFF2E3D0)
private val Grey = Color(0xFF8A8A94)

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
    val listState = rememberLazyListState()
    val loader = remember { ImageLoader.Builder(ctx).okHttpClient { Tls.client(ctx) }.build() }

    // 新消息来了滚到底（只在最后一条变化时，往上翻历史不触发）
    val lastId = msgs.lastOrNull()?.id
    LaunchedEffect(lastId) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }
    // 翻到顶部加载更早的
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    LaunchedEffect(atTop, msgs.size) { if (atTop && msgs.size >= 50) ChatClient.loadMore() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(connected, alive, mood, sig, onCall)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(msgs, key = { it.id }) { m ->
                MessageRow(m, msgs, loader,
                    onQuote = { replyTo = it },
                    onCopy = {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("msg", it.text))
                        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    })
            }
        }
        if (status == "thinking") {
            Text("辰在想…", fontSize = 12.sp, color = Grey, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
        }
        replyTo?.let { q ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("引用 ${if (q.isChen) "辰" else "小陈"}：${q.text.take(40)}", fontSize = 12.sp, color = Grey, modifier = Modifier.weight(1f))
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
            onPlus = { Toast.makeText(ctx, "图片/文件 下一版", Toast.LENGTH_SHORT).show() },
        )
    }
}

@Composable
private fun Header(connected: Boolean, alive: Boolean, mood: String, sig: String, onCall: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text("辰", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("辰", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (alive) Color(0xFF2ECC71) else Grey))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when { alive -> "在线·VPS"; connected -> "已连接·辰不在" ; else -> "连接中…" },
                        fontSize = 12.sp, color = Grey,
                    )
                }
                if (mood.isNotBlank()) Text(mood, fontSize = 12.sp)
                if (sig.isNotBlank()) Text(sig, fontSize = 12.sp, color = Grey)
            }
            IconButton(onClick = {}) { Icon(Icons.Default.Search, contentDescription = "搜索") }
            IconButton(onClick = onCall) { Icon(Icons.Default.Phone, contentDescription = "打电话") }
            IconButton(onClick = {}) { Icon(Icons.Default.Menu, contentDescription = "会话") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(m: Msg, all: List<Msg>, loader: ImageLoader, onQuote: (Msg) -> Unit, onCopy: (Msg) -> Unit) {
    val ctx = LocalContext.current
    val mine = !m.isChen
    var menu by remember { mutableStateOf(false) }
    var showThink by remember { mutableStateOf(false) }
    var showTranscript by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine && !m.thinking.isNullOrBlank()) {
            Row(Modifier.clickable { showThink = !showThink }.padding(start = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (showThink) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Grey, modifier = Modifier.size(16.dp))
                Text("思考", fontSize = 12.sp, color = Grey)
            }
            if (showThink) Text(m.thinking, fontSize = 12.sp, color = Grey, modifier = Modifier.padding(start = 8.dp, end = 24.dp, bottom = 4.dp))
        }
        Box {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (mine) MeBubble else ChenBubble,
                modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    m.replyTo?.let { rid ->
                        all.firstOrNull { it.id == rid }?.let { q ->
                            Column(
                                Modifier.fillMaxWidth().background(Color(0x22000000), RoundedCornerShape(8.dp)).padding(8.dp)
                            ) {
                                Text(if (q.isChen) "辰" else "小陈", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(q.text.take(60), fontSize = 12.sp, color = Color(0xFF444444))
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    if (m.msgType == "image" && m.media != null) {
                        AsyncImage(model = ChatClient.mediaUrl(m.media), imageLoader = loader, contentDescription = null, modifier = Modifier.widthIn(max = 260.dp))
                        if (m.text.isNotBlank()) Text(m.text, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
                    } else if (m.msgType == "voice" && m.voice != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { VoicePlayer.play(ctx, ChatClient.mediaUrl(m.voice)) }) { Icon(Icons.Default.PlayArrow, contentDescription = "播放") }
                            Text("语音", fontSize = 15.sp)
                            IconButton(onClick = { showTranscript = !showTranscript }) {
                                Icon(if (showTranscript) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = "文字版")
                            }
                        }
                        if (showTranscript && m.text.isNotBlank()) Text(m.text, fontSize = 15.sp)
                    } else {
                        Text(m.text, fontSize = 16.sp, color = Color(0xFF1E1E24))
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("引用") }, onClick = { menu = false; onQuote(m) })
                DropdownMenuItem(text = { Text("复制") }, onClick = { menu = false; onCopy(m) })
            }
        }
        Text(m.timeLabel(), fontSize = 11.sp, color = Grey, modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp))
    }
}

@Composable
private fun Composer(value: String, onChange: (String) -> Unit, onSend: () -> Unit, onPlus: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlus) { Icon(Icons.Default.Add, contentDescription = "更多") }
            OutlinedTextField(
                value = value, onValueChange = onChange,
                modifier = Modifier.weight(1f), maxLines = 5,
                placeholder = { Text("说点什么") },
                shape = RoundedCornerShape(22.dp),
            )
            IconButton(onClick = onSend, enabled = value.isNotBlank()) { Icon(Icons.Default.Send, contentDescription = "发送") }
        }
    }
}
