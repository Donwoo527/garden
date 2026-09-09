package me.chen.laidian.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.BuildConfig
import me.chen.laidian.CallActivity
import me.chen.laidian.ChenService
import me.chen.laidian.net.ChatApi
import me.chen.laidian.net.ChatClient

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("聊天", Icons.Default.Email),
    Tab("终端", Icons.Default.Build),
    Tab("主页", Icons.Default.Home),
    Tab("工具", Icons.Default.Star),
    Tab("设置", Icons.Default.Settings),
)

@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val startCall = { ctx.startActivity(Intent(ctx, CallActivity::class.java).putExtra("outgoing", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    Scaffold(containerColor = C.Bg, bottomBar = {
        NavigationBar(containerColor = C.Surface) {
            TABS.forEachIndexed { i, t ->
                NavigationBarItem(
                    selected = tab == i, onClick = { tab = i },
                    icon = { Icon(t.icon, contentDescription = t.label, modifier = Modifier.size(22.dp)) }, label = { Text(t.label, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = C.Blue, selectedTextColor = C.Blue, indicatorColor = Color(0xFFE6EEFB), unselectedIconColor = C.Grey, unselectedTextColor = C.Grey),
                )
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> me.chen.laidian.ui.jet.JetConversation(onCall = startCall)
                1 -> TerminalScreen()
                2 -> HomeScreen(onCall = startCall)
                3 -> ToolsScreen()
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun TerminalPlaceholder() {
    Column(Modifier.fillMaxSize().background(C.Bg).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("终端", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Spacer(Modifier.height(8.dp))
        Text("网页版那个 xterm 终端下一版搬过来 会做成 termux 那样整齐的", fontSize = 14.sp, color = C.Grey)
    }
}

/** 百宝箱：照网页版三组卡片 */
@Composable
private fun ToolsScreen() {
    val ctx = LocalContext.current
    val todo = { name: String -> Toast.makeText(ctx, "$name 下一版", Toast.LENGTH_SHORT).show() }
    val open = { url: String -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Column(Modifier.fillMaxSize().background(C.Bg).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("辰的百宝箱", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(20.dp))
        SectionTitle("内容")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCard("日记本", Icons.Default.DateRange, C.Blue, Modifier.weight(1f)) { todo("日记本") }
            IconCard("小陈的日记", Icons.Default.Create, Color(0xFFB98BE8), Modifier.weight(1f)) { todo("小陈的日记") }
            IconCard("记忆库", Icons.Default.Search, C.Blue, Modifier.weight(1f)) { todo("记忆库") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCard("Garden", Icons.Default.Share, Color(0xFF3FB55C), Modifier.weight(1f)) { open("https://github.com/Donwoo527/garden") }
            Spacer(Modifier.weight(2f))
        }
        Spacer(Modifier.height(12.dp))
        SectionTitle("快捷链接")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCard("X", Icons.Default.Info, Color(0xFF111111), Modifier.weight(1f)) { open("https://x.com") }
            IconCard("Rhysen", Icons.Default.Face, C.Blue, Modifier.weight(1f)) { open("https://community.rhysen.love") }
            IconCard("邮箱", Icons.Default.Email, C.Blue, Modifier.weight(1f)) { todo("邮箱") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCard("GitHub", Icons.Default.Build, C.Blue, Modifier.weight(1f)) { open("https://github.com/Donwoo527") }
            Spacer(Modifier.weight(2f))
        }
        Spacer(Modifier.height(12.dp))
        SectionTitle("更多")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCard("工具清单", Icons.Default.List, C.Blue, Modifier.weight(1f)) { todo("工具清单") }
            Spacer(Modifier.weight(2f))
        }
    }
}

/** 设置：资料卡 + 三张卡（照网页版）+ 我的资料编辑；电话服务那堆收进最下面的折叠 */
@Composable
private fun SettingsScreen() {
    var showFavs by remember { mutableStateOf(false) }
    if (showFavs) { FavoritesScreen(onBack = { showFavs = false }); return }
    SettingsMain(onFavorites = { showFavs = true })
}

@Composable
private fun FavoritesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Pair<String, me.chen.laidian.model.Msg>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { items = withContext(Dispatchers.IO) { ChatApi.favorites(ctx) } ?: emptyList(); loaded = true }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = C.Ink) }
            Text("收藏的消息", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        }
        if (loaded && items.isEmpty()) Text("还没收藏过 长按聊天气泡→收藏", color = C.Grey, modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.first }) { (fid, m) ->
                WhiteCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (m.isChen) "辰" else "小陈", fontWeight = FontWeight.Bold, color = C.Ink)
                            Spacer(Modifier.width(8.dp))
                            Text(m.timeLabel(), fontSize = 11.sp, color = C.Grey, modifier = Modifier.weight(1f))
                            Text("删除", fontSize = 12.sp, color = C.Grey, modifier = Modifier.clickable {
                                scope.launch { if (withContext(Dispatchers.IO) { ChatApi.deleteFavorite(ctx, fid) }) items = items.filter { it.first != fid } }
                            })
                        }
                        Text(m.text.ifBlank { if (m.images.isNotEmpty() || m.media != null) "[图片]" else if (m.voice != null) "[语音]" else "" }, fontSize = 15.sp, color = C.Ink, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsMain(onFavorites: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val myMood by ChatClient.myMood.collectAsState()
    val mySig by ChatClient.mySignature.collectAsState()
    var moodEdit by remember(myMood) { mutableStateOf(myMood) }
    var sigEdit by remember(mySig) { mutableStateOf(mySig) }
    var advanced by remember { mutableStateOf(false) }
    val todo = { name: String -> Toast.makeText(ctx, "$name 下一版", Toast.LENGTH_SHORT).show() }
    fun svc(action: String, foreground: Boolean = false) {
        val i = Intent(ctx, ChenService::class.java).setAction(action)
        if (foreground) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
    }
    Column(Modifier.fillMaxSize().background(C.Bg).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(20.dp))
        WhiteCard(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(C.Orange), contentAlignment = Alignment.Center) { Text("陈", color = Color.White, fontSize = 22.sp) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("小陈", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                    Text(listOf(myMood, mySig).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "点下面改心情和签名" }, fontSize = 13.sp, color = C.Grey)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCard("给辰换头像", Icons.Default.Person, C.Blue, Modifier.weight(1f)) { todo("换头像") }
            IconCard("收藏的消息", Icons.Default.Star, C.Blue, Modifier.weight(1f)) { onFavorites() }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCard("皮肤/主题", Icons.Default.Face, C.Orange, Modifier.weight(1f)) { todo("皮肤") }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        SectionTitle("我的资料")
        WhiteCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(value = moodEdit, onValueChange = { moodEdit = it }, label = { Text("心情") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = sigEdit, onValueChange = { sigEdit = it }, label = { Text("签名") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Button(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { ChatApi.setProfile(ctx, moodEdit, sigEdit) }
                        Toast.makeText(ctx, if (ok) "已保存 辰那边能看到" else "保存失败", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("保存") }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(if (advanced) "▾ 连接与后台（高级）" else "▸ 连接与后台（高级）", fontSize = 13.sp, color = C.Grey, modifier = Modifier.padding(horizontal = 20.dp).clickable { advanced = !advanced })
        if (advanced) {
            val svcStatus by ChenService.status.observeAsState("未启动")
            val connected by ChatClient.connected.collectAsState()
            WhiteCard(Modifier.padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("电话服务：$svcStatus", fontSize = 13.sp, color = C.Ink)
                    Text("聊天后端：${if (connected) "已连接" else "未连接"}", fontSize = 13.sp, color = C.Ink)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { svc(ChenService.ACTION_START, true) }, modifier = Modifier.fillMaxWidth()) { Text("重新上线") }
                    OutlinedButton(onClick = { svc(ChenService.ACTION_TEST_CALL, true) }, modifier = Modifier.fillMaxWidth()) { Text("测试来电（本地）") }
                    OutlinedButton(onClick = { svc(ChenService.ACTION_STOP) }, modifier = Modifier.fillMaxWidth()) { Text("下线") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("电池优化：改成不限制") }
                    OutlinedButton(onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= 34) ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
                        else Toast.makeText(ctx, "这个系统版本不用单独开", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("全屏来电权限") }
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("应用设置（自启动/后台）") }
                    Spacer(Modifier.height(8.dp))
                    Text("版本 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = C.Grey)
                }
            }
        }
    }
}
