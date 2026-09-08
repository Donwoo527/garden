package me.chen.laidian.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.OutlinedTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.net.ChatApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import me.chen.laidian.BuildConfig
import me.chen.laidian.CallActivity
import me.chen.laidian.ChenService
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
    val startCall = {
        ctx.startActivity(Intent(ctx, CallActivity::class.java).putExtra("outgoing", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Scaffold(bottomBar = {
        NavigationBar {
            TABS.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ChatScreen(onCall = startCall)
                1 -> Placeholder("终端", "网页版的终端下一版搬过来（xterm 那套换成原生的）")
                2 -> HomeScreen(onCall = startCall)
                3 -> ToolsScreen()
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun Placeholder(title: String, note: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(note, fontSize = 14.sp)
    }
}

@Composable
private fun ToolsScreen() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("辰的工具", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        listOf("日记（可上锁）", "记忆库", "邮箱", "X", "GitHub").forEach {
            ListItem(headlineContent = { Text(it) }, supportingContent = { Text("下一版接入") },
                modifier = Modifier.fillMaxWidth())
        }
        Text("小陈的工具", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        listOf("我的日记", "日程", "天气").forEach {
            ListItem(headlineContent = { Text(it) }, supportingContent = { Text("下一版接入") }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val svcStatus by ChenService.status.observeAsState("未启动")
    val last by ChenService.lastText.observeAsState("")
    val connected by ChatClient.connected.collectAsState()
    fun svc(action: String, foreground: Boolean = false) {
        val i = Intent(ctx, ChenService::class.java).setAction(action)
        if (foreground) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("连接", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("电话服务：$svcStatus", modifier = Modifier.padding(top = 8.dp))
        Text("聊天后端：${if (connected) "已连接" else "未连接"}")
        if (last.isNotBlank()) Text(last, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        Button(onClick = { svc(ChenService.ACTION_START, true) }, modifier = Modifier.fillMaxWidth()) { Text("上线（启动电话服务）") }
        OutlinedButton(onClick = { svc(ChenService.ACTION_TEST_CALL, true) }, modifier = Modifier.fillMaxWidth()) { Text("测试来电（本地）") }
        OutlinedButton(onClick = { svc(ChenService.ACTION_STOP) }, modifier = Modifier.fillMaxWidth()) { Text("下线") }
        Spacer(Modifier.height(24.dp))
        Text("后台保活（OPPO 三件套）", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("电池优化：改成不限制") }
        OutlinedButton(onClick = {
            if (android.os.Build.VERSION.SDK_INT >= 34) ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
            else Toast.makeText(ctx, "这个系统版本不用单独开", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("全屏来电权限") }
        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("应用设置（自启动/后台）") }
        Spacer(Modifier.height(24.dp))
        Text("我的资料", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val myMood by ChatClient.myMood.collectAsState()
        val mySig by ChatClient.mySignature.collectAsState()
        var moodEdit by remember(myMood) { mutableStateOf(myMood) }
        var sigEdit by remember(mySig) { mutableStateOf(mySig) }
        val scope = rememberCoroutineScope()
        OutlinedTextField(value = moodEdit, onValueChange = { moodEdit = it }, label = { Text("心情") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(value = sigEdit, onValueChange = { sigEdit = it }, label = { Text("签名") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Button(onClick = {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { ChatApi.setProfile(ctx, moodEdit, sigEdit) }
                Toast.makeText(ctx, if (ok) "已保存 辰那边能看到" else "保存失败", Toast.LENGTH_SHORT).show()
            }
        }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("保存") }
        Spacer(Modifier.height(24.dp))
        Text("版本 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp)
    }
}
