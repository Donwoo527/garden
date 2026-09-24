package me.chen.laidian.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chen.laidian.ToyController
import kotlin.math.roundToInt

/**
 * 玩具页（百宝箱→玩具）。0.89 照司沃康 app 补齐：伸缩 / 拍打 / 振动模式×强度 / 吮吸强度 / 加热。
 * 滑块松手才发指令（不然一拖一串蓝牙包）。她自己点连接、自己开"允许辰远程"、随时一键停；辰发的每条指令都显示在这。
 */
@Composable
fun ToyScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val skin = LocalSkin.current
    val status by ToyController.status.observeAsState("")
    val wandOn by ToyController.wandConnected.observeAsState(false)
    val suckOn by ToyController.suckConnected.observeAsState(false)
    val wandLv by ToyController.wandLevel.observeAsState(0)
    val suckLv by ToyController.suckLevel.observeAsState(0)
    val stretchLv by ToyController.stretchLevel.observeAsState(0)
    val patLv by ToyController.patLevel.observeAsState(0)
    val vibM by ToyController.vibMode.observeAsState(0)
    val vibL by ToyController.vibLevel.observeAsState(5)
    val heatW by ToyController.heatWand.observeAsState(false)
    val heatS by ToyController.heatSuck.observeAsState(false)
    val allowed by ToyController.remoteAllowed.observeAsState(false)
    val log by ToyController.log.observeAsState("")
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) ToyController.scan() else Toast.makeText(ctx, "没给蓝牙权限 连不上", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize().background(skin.bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 百宝箱", color = skin.muted) }
        }
        Text("玩具", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = skin.ink)
        Spacer(Modifier.height(6.dp))
        Text(status, fontSize = 14.sp, color = skin.muted)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (ToyController.hasPermissions(ctx)) ToyController.scan() else perm.launch(ToyController.needed()) }) { Text("扫描连接") }
            OutlinedButton(onClick = { ToyController.disconnectAll() }) { Text("断开") }
        }
        Spacer(Modifier.height(12.dp))
        Text("棒 SL278H：${if (wandOn) "已连接" else "未连接"}    吸 SL278J：${if (suckOn) "已连接" else "未连接"}", fontSize = 14.sp, color = skin.ink)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("允许辰远程控制", fontSize = 15.sp, color = skin.ink)
                Text("关着的时候只有你自己能控 辰发的指令会被忽略", fontSize = 12.sp, color = skin.muted)
            }
            Switch(checked = allowed, onCheckedChange = { ToyController.allowRemote(it) })
        }
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { ToyController.stopAll("一键停") },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD24C3E))
        ) { Text("一键停", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        Spacer(Modifier.height(6.dp))
        Text("最近动作：$log", fontSize = 13.sp, color = skin.ink)
        Spacer(Modifier.height(16.dp))

        // ---------- 棒 ----------
        SectionTitle("棒 SL278H")
        LevelSlider("联动强度（伸缩+转珠+拍打）", wandLv, 0, ToyController.MAX_LEVEL) { ToyController.apply("你", wand = it) }
        LevelSlider("伸缩", stretchLv, 0, 7) { ToyController.apply("你", stretch = it) }
        LevelSlider("拍打", patLv, 0, 7) { ToyController.apply("你", pat = it) }
        LevelSlider("振动模式（0=关）", vibM, 0, 10) { ToyController.apply("你", vibModeV = it) }
        LevelSlider("振动强度", vibL, 1, 10) { ToyController.apply("你", vibLevelV = it) }
        HeatRow("棒 加热", heatW) { ToyController.apply("你", heatW = it) }
        Spacer(Modifier.height(12.dp))

        // ---------- 吸 ----------
        SectionTitle("吸 SL278J")
        LevelSlider("吮吸强度", suckLv, 0, ToyController.MAX_LEVEL) { ToyController.apply("你", suck = it) }
        HeatRow("吸 加热", heatS) { ToyController.apply("你", heatS = it) }
        Spacer(Modifier.height(16.dp))

        Text(
            "规矩：任何时候按停立刻停（马达停、加热关）；辰的指令 120 秒不续自动停；蓝牙一断设备 3-5 秒自停；强度最高 ${ToyController.MAX_LEVEL}。滑块松手才生效。手机上的 SVAKOM 官方 app 要关掉，不然抢蓝牙。吸的只破了强度，没有模式。",
            fontSize = 12.sp, color = skin.muted
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LevelSlider(label: String, value: Int, min: Int, max: Int, onDone: (Int) -> Unit) {
    val skin = LocalSkin.current
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Text("$label：${v.roundToInt()}", fontSize = 13.sp, color = skin.ink)
        Slider(
            value = v, onValueChange = { v = it }, onValueChangeFinished = { onDone(v.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(), steps = if (max - min <= 20) (max - min - 1).coerceAtLeast(0) else 0
        )
    }
}

@Composable
private fun HeatRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val skin = LocalSkin.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, color = skin.ink)
        Switch(checked = on, onCheckedChange = onChange)
    }
}
