package me.chen.laidian.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.Tls
import me.chen.laidian.model.Moment
import me.chen.laidian.net.ChatApi
import me.chen.laidian.net.ChatClient
import me.chen.laidian.net.ImageUtil

private val Grey = Color(0xFF8A8A94)

/** 主页：辰的状态 + 戳一戳/打电话 + 朋友圈（发/赞/评） */
@Composable
fun HomeScreen(onCall: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mood by ChatClient.mood.collectAsState()
    val sig by ChatClient.signature.collectAsState()
    val alive by ChatClient.sessionAlive.collectAsState()
    val moments by ChatClient.moments.collectAsState()
    val loader = remember { ImageLoader.Builder(ctx).okHttpClient { Tls.client(ctx) }.build() }
    var draft by remember { mutableStateOf("") }
    var draftUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { ChatApi.loadMoments(ctx) }
        if (list != null) ChatClient.moments.value = list
        withContext(Dispatchers.IO) { ChatApi.markMomentsRead(ctx) }
        ChatClient.momentsUnread.value = 0
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val urls = withContext(Dispatchers.IO) { uris.mapNotNull { u -> ImageUtil.compress(ctx, u)?.let { ChatApi.uploadImage(ctx, it) } } }
            draftUrls = draftUrls + urls
            busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text("辰", color = MaterialTheme.colorScheme.onPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(if (alive) "在线" else "不在", fontSize = 13.sp, color = Grey)
                if (mood.isNotBlank()) Text(mood, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
                if (sig.isNotBlank()) Text(sig, fontSize = 13.sp, color = Grey)
                Spacer(Modifier.height(16.dp))
                Row {
                    Button(onClick = { ChatClient.poke(); Toast.makeText(ctx, "戳了一下", Toast.LENGTH_SHORT).show() }) { Text("戳一戳") }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = onCall) { Text("打电话给辰") }
                }
            }
        }
        item {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("朋友圈", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it }, maxLines = 4,
                        placeholder = { Text("发条动态") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    if (draftUrls.isNotEmpty()) Text("已选 ${draftUrls.size} 张图", fontSize = 12.sp, color = Grey, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.Add, contentDescription = "加图") }
                        Button(enabled = !busy && (draft.isNotBlank() || draftUrls.isNotEmpty()), onClick = {
                            busy = true
                            val t = draft.trim(); val u = draftUrls
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { ChatApi.postMoment(ctx, t, u) }
                                busy = false
                                if (ok) { draft = ""; draftUrls = emptyList() } else Toast.makeText(ctx, "发布失败", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(if (busy) "…" else "发布") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        items(moments, key = { it.id }) { m -> MomentCard(m, loader) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun MomentCard(m: Moment, loader: ImageLoader) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var comment by remember { mutableStateOf("") }
    val liked = "xiaochen" in m.likes
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(if (m.isChen) Color(0xFF1E1E24) else Color(0xFFE07A2F)), contentAlignment = Alignment.Center) {
                    Text(if (m.isChen) "辰" else "陈", color = Color.White, fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                Column { Text(if (m.isChen) "辰" else "小陈", fontWeight = FontWeight.Bold); Text(m.timeLabel(), fontSize = 11.sp, color = Grey) }
            }
            if (m.text.isNotBlank()) Text(m.text, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            m.images.forEach { u ->
                AsyncImage(model = ChatClient.mediaUrl(u), imageLoader = loader, contentDescription = null, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { ChatApi.likeMoment(ctx, m.id) } } }) {
                    Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "赞", tint = if (liked) Color(0xFFE0245E) else Grey)
                }
                if (m.likes.isNotEmpty()) Text(m.likes.joinToString("、") { if (it == "chen") "辰" else "小陈" } + " 赞了", fontSize = 12.sp, color = Grey)
            }
            m.comments.forEach { c ->
                Text("${if (c.who == "chen") "辰" else "小陈"}：${c.text}", fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                OutlinedTextField(value = comment, onValueChange = { comment = it }, singleLine = true, placeholder = { Text("评论") }, modifier = Modifier.weight(1f))
                IconButton(enabled = comment.isNotBlank(), onClick = {
                    val t = comment.trim(); comment = ""
                    scope.launch { withContext(Dispatchers.IO) { ChatApi.commentMoment(ctx, m.id, t) } }
                }) { Icon(Icons.Default.Send, contentDescription = "发评论") }
            }
        }
    }
}
