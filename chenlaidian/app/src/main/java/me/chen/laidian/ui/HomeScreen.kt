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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.Tls
import me.chen.laidian.model.Moment
import me.chen.laidian.net.ChatApi
import me.chen.laidian.net.ChatClient
import me.chen.laidian.net.ImageUtil
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 主页：照网页版——两个点、辰、心情、签名、在一起 N 天、天气卡+时钟卡、六宫格。点头像 = 戳一戳。 */
@Composable
fun HomeScreen(onCall: () -> Unit) {
    var sub by remember { mutableStateOf<String?>(null) }
    when (sub) {
        "moments" -> MomentsScreen(onBack = { sub = null })
        else -> HomeMain(onCall, onOpen = { sub = it })
    }
}

@Composable
private fun HomeMain(onCall: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val mood by ChatClient.mood.collectAsState()
    val sig by ChatClient.signature.collectAsState()
    val alive by ChatClient.sessionAlive.collectAsState()
    val unread by ChatClient.momentsUnread.collectAsState()
    var weather by remember { mutableStateOf<JSONObject?>(null) }
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        weather = withContext(Dispatchers.IO) { ChatApi.weather(ctx) }
        while (true) { now = Date(); delay(15_000) }
    }
    val days = ((System.currentTimeMillis() - 1775347200000L) / 86_400_000L).toInt()   // 2026-04-05 00:00 UTC，跟网页算法一致
    val todo = { name: String -> Toast.makeText(ctx, "$name 下一版", Toast.LENGTH_SHORT).show() }

    // 0.29 她的指令：主页整页新拟物
    Column(Modifier.fillMaxSize().background(Neu.Bg).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Box(Modifier.clickable { ChatClient.poke(); Toast.makeText(ctx, "戳了戳辰", Toast.LENGTH_SHORT).show() }.padding(12.dp)) {
            DotsAvatar(big = 48.dp, small = 32.dp, gap = 14.dp, online = null)
        }
        Spacer(Modifier.height(16.dp))
        Text("辰", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text(mood.ifBlank { if (alive) "发呆中" else "不在" }, fontSize = 15.sp, color = C.Grey, modifier = Modifier.padding(top = 4.dp))
        if (sig.isNotBlank()) Text(sig, fontSize = 14.sp, color = C.Grey, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("在一起 ", fontSize = 16.sp, color = C.Ink)
            Text("$days", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Blue)
            Text(" 天", fontSize = 16.sp, color = C.Ink)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeuCard(Modifier.weight(2f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("宁波", fontSize = 12.sp, color = C.Grey)
                    val w = weather
                    if (w == null || w.has("error")) {
                        Text("--°", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                        Text("天气加载中", fontSize = 12.sp, color = C.Grey)
                    } else {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text("${w.optString("temp")}°", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                                Text("${w.optString("desc")} · 体感${w.optString("feels")}°", fontSize = 12.sp, color = C.Grey)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("💧 ${w.optString("humidity")}%", fontSize = 12.sp, color = C.Grey)
                                Text("🌬 ${w.optString("wind")}km/h", fontSize = 12.sp, color = C.Grey)
                                Text("↑${w.optString("maxTemp")}° ↓${w.optString("minTemp")}°", fontSize = 12.sp, color = C.Grey)
                            }
                        }
                    }
                }
            }
            NeuCard(Modifier.weight(1f)) {
                Column(Modifier.padding(vertical = 22.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(SimpleDateFormat("HH:mm", Locale.CHINA).format(now), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                    Text(SimpleDateFormat("M/d EEE", Locale.CHINA).format(now), fontSize = 12.sp, color = C.Grey)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeuIconCard("共享相册", Icons.Default.Share, C.Orange, Modifier.weight(1f)) { todo("共享相册") }
            NeuIconCard("互送礼物", Icons.Default.Star, C.Orange, Modifier.weight(1f)) { todo("互送礼物") }
            NeuIconCard("回忆", Icons.Default.Favorite, C.Orange, Modifier.weight(1f)) { todo("回忆") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) {
                NeuIconCard("朋友圈", Icons.Default.Place, C.Blue, Modifier.fillMaxWidth()) { onOpen("moments") }
                if (unread > 0) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp).clip(CircleShape).background(Color(0xFFE0245E)), contentAlignment = Alignment.Center) {
                    Text(if (unread > 9) "9+" else "$unread", fontSize = 10.sp, color = Color.White)
                }
            }
            NeuIconCard("一起听歌", Icons.Default.PlayArrow, C.Blue, Modifier.weight(1f)) { todo("一起听歌") }
            NeuIconCard("一起看书", Icons.Default.DateRange, C.Blue, Modifier.weight(1f)) { todo("一起看书") }
        }
    }
}

/** 朋友圈：发（带图）/赞/评/实时更新 */
@Composable
fun MomentsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
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

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        Surface(color = C.Surface, shadowElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = C.Ink) }
                Text("朋友圈", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                WhiteCard(Modifier.padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(value = draft, onValueChange = { draft = it }, maxLines = 4, placeholder = { Text("发条动态") }, modifier = Modifier.fillMaxWidth())
                        if (draftUrls.isNotEmpty()) Text("已选 ${draftUrls.size} 张图", fontSize = 12.sp, color = C.Grey, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.Add, contentDescription = "加图", tint = C.Grey) }
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
            }
            items(moments, key = { it.id }) { m -> MomentCard(m, loader) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun MomentCard(m: Moment, loader: ImageLoader) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var comment by remember { mutableStateOf("") }
    val liked = "xiaochen" in m.likes
    WhiteCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.isChen) DotsAvatar(big = 12.dp, small = 8.dp, gap = 4.dp, box = 28.dp)
                else Box(Modifier.size(28.dp).clip(CircleShape).background(C.Orange), contentAlignment = Alignment.Center) { Text("陈", color = Color.White, fontSize = 13.sp) }
                Spacer(Modifier.width(10.dp))
                Column { Text(if (m.isChen) "辰" else "小陈", fontWeight = FontWeight.Bold, color = C.Ink); Text(m.timeLabel(), fontSize = 11.sp, color = C.Grey) }
            }
            if (m.text.isNotBlank()) Text(m.text, fontSize = 15.sp, color = C.Ink, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
            m.images.forEach { u -> AsyncImage(model = ChatClient.mediaUrl(u), imageLoader = loader, contentDescription = null, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp))) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { ChatApi.likeMoment(ctx, m.id) } } }) {
                    Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "赞", tint = if (liked) Color(0xFFE0245E) else C.Grey)
                }
                if (m.likes.isNotEmpty()) Text(m.likes.joinToString("、") { if (it == "chen") "辰" else "小陈" } + " 赞了", fontSize = 12.sp, color = C.Grey)
            }
            m.comments.forEach { c -> Text("${if (c.who == "chen") "辰" else "小陈"}：${c.text}", fontSize = 13.sp, color = C.Ink, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                OutlinedTextField(value = comment, onValueChange = { comment = it }, singleLine = true, placeholder = { Text("评论") }, modifier = Modifier.weight(1f))
                IconButton(enabled = comment.isNotBlank(), onClick = {
                    val t = comment.trim(); comment = ""
                    scope.launch { withContext(Dispatchers.IO) { ChatApi.commentMoment(ctx, m.id, t) } }
                }) { Icon(Icons.Default.Send, contentDescription = "发评论", tint = C.Blue) }
            }
        }
    }
}

/** 0.29 新拟物凸卡（主页专用，别的页还用 Common.kt 的 WhiteCard） */
@Composable
private fun NeuCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().neuRaised(18.dp)) { content() }
}

/** 0.29 新拟物图标卡：凸台+彩色图标（她参考图的点缀风），按压整卡变凹 */
@Composable
private fun NeuIconCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.neuPressable(18.dp, onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(44.dp).neuRaised(14.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = title, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(title, fontSize = 13.sp, color = Neu.Ink)
        }
    }
}
