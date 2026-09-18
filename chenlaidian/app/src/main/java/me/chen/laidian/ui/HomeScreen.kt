package me.chen.laidian.ui

import android.widget.Toast
import androidx.compose.runtime.livedata.observeAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import me.chen.laidian.R
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
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 主页（0916 她定的：只留一个主角）：身份卡（两个点、辰、心情、签名、状态、在一起 N 天）→ 问候+温度 → 年进度 → 时钟卡+朋友圈卡 → 一排小圆钮。点头像 = 戳一戳。 */
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
    // 0.44 连接状态行（0911排障坑：断线原因只进通知栏 页面上看不见）——聊天+语音双通道
    val chatConn by ChatClient.connected.collectAsState()
    val voiceStatus by me.chen.laidian.ChenService.status.observeAsState("未启动")
    val usageStatus by me.chen.laidian.ChenService.usageStatus.observeAsState("")
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
    // 0916 她定的：主页只留一个主角——"辰"身份卡是主角，其余全是小的、不等宽、故意留空（她："分布太平均 眼睛没地方落"）
    val skin = LocalSkin.current
    val cal = Calendar.getInstance().apply { time = now }
    val greeting = when (cal.get(Calendar.HOUR_OF_DAY)) { in 5..10 -> "早上好"; in 11..13 -> "中午好"; in 14..17 -> "下午好"; else -> "晚上好" }
    val yearPct = (cal.get(Calendar.DAY_OF_YEAR) * 100 / 365).coerceIn(0, 100)
    Column(Modifier.fillMaxSize().background(skin.bg).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        // 1. 顶部身份区：0.81 她看了 0.80 说"这里不需要一个大框 上面还是和之前一样"——框去掉 字号恢复 只动下面的小组件
        Box(Modifier.clickable { ChatClient.poke(); Toast.makeText(ctx, "戳了戳辰", Toast.LENGTH_SHORT).show() }.padding(12.dp)) {
            DotsAvatar(big = 48.dp, small = 32.dp, gap = 14.dp, online = null)
        }
        Spacer(Modifier.height(12.dp))
        Text("辰", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = skin.ink)
        Text(mood.ifBlank { if (alive) "发呆中" else "不在" }, fontSize = 15.sp, color = skin.muted, modifier = Modifier.padding(top = 4.dp))
        if (sig.isNotBlank()) Text(sig, fontSize = 14.sp, color = skin.muted, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 4.dp))
        Text(
            "聊天${if (chatConn) "✓" else "✗"} · 语音·$voiceStatus" + (if (usageStatus.isNotBlank()) " · 查岗$usageStatus" else ""),
            fontSize = 11.sp,
            color = if (chatConn && voiceStatus == "辰在线") skin.muted else Color(0xFFE53935),
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("在一起 ", fontSize = 16.sp, color = skin.ink)
            Text("$days", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = skin.accent)
            Text(" 天", fontSize = 16.sp, color = skin.ink)
        }
        Spacer(Modifier.height(24.dp))
        // 2. 卡外不装框：左问候、右温度大字（天气获取逻辑没动，只改了展示）
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = skin.ink)
                Text("宁波", fontSize = 11.sp, color = skin.muted, modifier = Modifier.padding(top = 2.dp))
            }
            val w = weather
            Column(horizontalAlignment = Alignment.End) {
                if (w == null || w.has("error")) {
                    Text("--°", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = skin.ink)
                    Text("天气加载中", fontSize = 11.sp, color = skin.muted)
                } else {
                    Text("${w.optString("temp")}°", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = skin.ink)
                    Text(w.optString("desc"), fontSize = 11.sp, color = skin.muted)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        // 3. 今年已过 X%：8dp 凹槽（皮肤色）+ accent 填充
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(skin.line)) {
                skin.sunkenEdges?.let { (dark, light) ->   // 新拟物：上沿深影、下沿亮边 = 凹下去的槽
                    Box(Modifier.fillMaxWidth().height(2.dp).background(dark))
                    Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomStart).background(light))
                }
                Box(Modifier.fillMaxWidth(yearPct / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(skin.accent))
            }
            Text("今年已过 $yearPct%", fontSize = 11.sp, color = skin.muted, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(24.dp))
        // 4. 两张小卡不等宽：时钟 1 : 朋友圈 1.4
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeuCard(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(vertical = 18.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(SimpleDateFormat("HH:mm", Locale.CHINA).format(now), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = skin.ink)
                    Text(SimpleDateFormat("M/d EEE", Locale.CHINA).format(now), fontSize = 12.sp, color = skin.muted)
                }
            }
            Box(Modifier.weight(1.4f).fillMaxHeight()) {
                NeuIconCard("朋友圈", R.drawable.ic_dynamic_feed, skin.accent, Modifier.fillMaxSize()) { onOpen("moments") }
                if (unread > 0) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp).clip(CircleShape).background(Color(0xFFE0245E)), contentAlignment = Alignment.Center) {
                    Text(if (unread > 9) "9+" else "$unread", fontSize = 10.sp, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        // 5. 五个空壳缩成一排小圆钮，不再各占一张大卡
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            NeuRoundButton("共享相册", R.drawable.ic_photo_library, Modifier.weight(1f)) { todo("共享相册") }
            NeuRoundButton("互送礼物", R.drawable.ic_redeem, Modifier.weight(1f)) { todo("互送礼物") }
            NeuRoundButton("回忆", R.drawable.ic_history, Modifier.weight(1f)) { todo("回忆") }
            NeuRoundButton("一起听歌", R.drawable.ic_music_note, Modifier.weight(1f)) { todo("一起听歌") }
            NeuRoundButton("一起看书", R.drawable.ic_menu_book, Modifier.weight(1f)) { todo("一起看书") }
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

    val skin = LocalSkin.current
    fun post() {
        busy = true
        val t = draft.trim(); val u = draftUrls
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ChatApi.postMoment(ctx, t, u) }
            busy = false
            if (ok) { draft = ""; draftUrls = emptyList() } else Toast.makeText(ctx, "发布失败", Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.fillMaxSize().background(skin.bg)) {
        // 0.84 顶栏跟皮肤走（之前是白色 Material 条 跟主页两套皮）
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = skin.ink) }
            Text("朋友圈", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = skin.ink)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                WhiteCard(Modifier.padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        // 输入区=凹（她的凹凸规范）跟聊天页输入栏同一套
                        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).sunken(16.dp).padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                            BasicTextField(
                                value = draft, onValueChange = { draft = it }, maxLines = 4,
                                cursorBrush = SolidColor(skin.ink),
                                textStyle = LocalTextStyle.current.copy(color = skin.ink, fontSize = 15.sp),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner -> Box { if (draft.isEmpty()) Text("发条动态", fontSize = 15.sp, color = skin.muted); inner() } },
                            )
                        }
                        if (draftUrls.isNotEmpty()) Text("已选 ${draftUrls.size} 张图", fontSize = 12.sp, color = skin.muted, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).pressable(20.dp) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_insert_photo), contentDescription = "加图", tint = skin.muted, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            // 能发=凸起可按；不能发=平（新拟物里"不能按的东西就不该凸出来"）
                            val canPost = !busy && (draft.isNotBlank() || draftUrls.isNotEmpty())
                            Box(
                                Modifier.height(40.dp).then(if (canPost) Modifier.pressable(20.dp) { post() } else Modifier.flat(20.dp)).padding(horizontal = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (busy) "…" else "发布", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (canPost) skin.accent else skin.muted) }
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
    val skin = LocalSkin.current
    val avatarXiaochen by ChatClient.avatarXiaochen.collectAsState()
    var comment by remember { mutableStateOf("") }
    val liked = "xiaochen" in m.likes
    WhiteCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 头像跟聊天页同一套：辰白底圆点、她橙底"陈"或她自己传的头像
                Box(Modifier.size(30.dp).clip(CircleShape).background(if (m.isChen) Color.White else C.Orange), contentAlignment = Alignment.Center) {
                    when {
                        m.isChen -> DotsAvatar(big = 9.dp, small = 6.dp, gap = 3.dp, box = 22.dp)
                        avatarXiaochen.isNotBlank() -> AsyncImage(model = ChatClient.mediaUrl(avatarXiaochen), imageLoader = loader, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else -> Text("陈", color = Color.White, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column { Text(if (m.isChen) "辰" else "小陈", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = skin.ink); Text(m.timeLabel(), fontSize = 11.sp, color = skin.muted) }
            }
            if (m.text.isNotBlank()) Text(m.text, fontSize = 15.sp, color = skin.ink, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
            m.images.forEach { u -> AsyncImage(model = ChatClient.mediaUrl(u), imageLoader = loader, contentDescription = null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { ChatApi.likeMoment(ctx, m.id) } } }) {
                    Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "赞", tint = if (liked) Color(0xFFE0245E) else skin.muted, modifier = Modifier.size(22.dp))
                }
                if (m.likes.isNotEmpty()) Text(m.likes.joinToString("、") { if (it == "chen") "辰" else "小陈" } + " 赞了", fontSize = 12.sp, color = skin.muted)
            }
            m.comments.forEach { c -> Text("${if (c.who == "chen") "辰" else "小陈"}：${c.text}", fontSize = 13.sp, color = skin.ink, lineHeight = 19.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Box(Modifier.weight(1f).heightIn(min = 38.dp).sunken(19.dp).padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(
                        value = comment, onValueChange = { comment = it }, singleLine = true,
                        cursorBrush = SolidColor(skin.ink),
                        textStyle = LocalTextStyle.current.copy(color = skin.ink, fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner -> Box { if (comment.isEmpty()) Text("评论", fontSize = 13.sp, color = skin.muted); inner() } },
                    )
                }
                val canSend = comment.isNotBlank()
                Box(
                    Modifier.padding(start = 6.dp).size(38.dp).clickable(enabled = canSend) {
                        val t = comment.trim(); comment = ""
                        scope.launch { withContext(Dispatchers.IO) { ChatApi.commentMoment(ctx, m.id, t) } }
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Send, contentDescription = "发评论", tint = if (canSend) skin.accent else skin.muted, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

/** 0.29 新拟物凸卡（主页专用，别的页还用 Common.kt 的 WhiteCard） */
@Composable
private fun NeuCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().raised(18.dp)) { content() }   // 0.56 起走皮肤
}

/** 0.29 新拟物图标卡：凸台+彩色图标（她参考图的点缀风），按压整卡变凹 */
@Composable
private fun NeuIconCard(title: String, icon: Int, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.pressable(18.dp, onClick = onClick), contentAlignment = Alignment.Center) {   // 0916 居中：被拉高时内容不贴顶
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(44.dp).raised(14.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = title, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(title, fontSize = 13.sp, color = LocalSkin.current.ink)
        }
    }
}

/** 0916 她定的：主页只留一个主角——空壳功能缩成 44dp 圆钮：凸台里一个图标、下面 10sp 小字，按住变凹 */
@Composable
private fun NeuRoundButton(title: String, icon: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).pressable(22.dp, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = title, tint = LocalSkin.current.muted, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = 10.sp, color = LocalSkin.current.muted)
    }
}
