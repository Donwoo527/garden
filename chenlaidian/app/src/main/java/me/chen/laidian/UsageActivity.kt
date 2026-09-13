package me.chen.laidian

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import me.chen.laidian.ui.C
import me.chen.laidian.ui.ChenTheme
import java.util.Calendar

/** 0.46 手机使用榜（她 0911 授权过的查岗单）：系统 UsageStats 本地统计，
 *  跟 MacroDroid→VPS 那条数据流完全独立——MacroDroid 被 OPPO 杀了这里照样有数。 */
class UsageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChenTheme { UsageScreen() } }
    }
}

private data class AppUse(val label: String, val pkg: String, val ms: Long)

private fun hasUsagePermission(ctx: Context): Boolean {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

/** queryEvents 手算今日前台时长：RESUMED/PAUSED 配对，比 bucket 聚合准 */
private fun todayUsage(ctx: Context): List<AppUse> {
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val start = cal.timeInMillis
    val now = System.currentTimeMillis()
    val events = usm.queryEvents(start, now)
    val fg = HashMap<String, Long>()   // pkg -> resumed at
    val total = HashMap<String, Long>()
    val ev = UsageEvents.Event()
    while (events.hasNextEvent()) {
        events.getNextEvent(ev)
        when (ev.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> fg[ev.packageName] = ev.timeStamp
            UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                val t0 = fg.remove(ev.packageName)
                if (t0 != null && ev.timeStamp > t0) {
                    total[ev.packageName] = (total[ev.packageName] ?: 0) + (ev.timeStamp - t0)
                }
            }
        }
    }
    // 还在前台的算到现在
    for ((pkg, t0) in fg) total[pkg] = (total[pkg] ?: 0) + (now - t0)
    val pm = ctx.packageManager
    return total.entries
        .filter { it.value >= 60_000 }   // <1min 不上榜
        .map { (pkg, ms) ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg.substringAfterLast('.') }
            AppUse(label, pkg, ms)
        }
        .sortedByDescending { it.ms }
}

private fun fmt(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h${m % 60}m" else "${m}m"
}

@Composable
private fun UsageScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var granted by remember { mutableStateOf(hasUsagePermission(ctx)) }
    var data by remember { mutableStateOf<List<AppUse>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // 回到页面时重查权限（从设置页返回的场景）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = hasUsagePermission(ctx); refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(granted, refreshKey) { if (granted) data = todayUsage(ctx) }

    Column(Modifier.fillMaxSize().background(C.Bg).verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("今日手机使用", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Spacer(Modifier.height(4.dp))
        Text("手机自己记的账 不走 MacroDroid 那条线", fontSize = 12.sp, color = C.Grey)
        Spacer(Modifier.height(16.dp))

        if (!granted) {
            Surface(shape = RoundedCornerShape(16.dp), color = C.Surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("需要开一次\"使用情况访问\"权限", fontSize = 15.sp, color = C.Ink)
                    Spacer(Modifier.height(6.dp))
                    Text("系统级统计 只开这一次 之后都不用管", fontSize = 12.sp, color = C.Grey)
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp), color = C.Blue,
                        modifier = Modifier.clickable {
                            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        },
                    ) {
                        Text("去开权限", fontSize = 14.sp, color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                    }
                }
            }
        } else {
            val totalMs = data.sumOf { it.ms }
            Text("总计 ${fmt(totalMs)}", fontSize = 14.sp, color = C.Blue, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            val max = data.firstOrNull()?.ms ?: 1
            for (u in data) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(u.label, fontSize = 13.sp, color = C.Ink, modifier = Modifier.width(88.dp), maxLines = 1)
                    Box(Modifier.weight(1f).height(14.dp), contentAlignment = Alignment.CenterStart) {
                        Box(
                            Modifier.fillMaxWidth(u.ms.toFloat() / max).height(14.dp)
                                .clip(RoundedCornerShape(7.dp)).background(C.BlueLight)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(fmt(u.ms), fontSize = 12.sp, color = C.Grey, modifier = Modifier.width(52.dp))
                }
            }
            if (data.isEmpty()) Text("今天还没攒出上榜的(≥1分钟)", fontSize = 13.sp, color = C.Grey)
        }
    }
}
