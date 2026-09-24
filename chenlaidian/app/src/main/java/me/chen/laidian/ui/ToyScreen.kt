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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chen.laidian.ToyController

/**
 * 0.88 玩具页（百宝箱→玩具）。她自己点连接、自己开"允许辰远程"、随时一键停；辰发的每条指令都显示在这。
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
        Spacer(Modifier.height(16.dp))

        Text("棒（SL278H）：${if (wandOn) "已连接" else "未连接"}   档位 $wandLv", fontSize = 14.sp, color = skin.ink)
        Text("吸（SL278J）：${if (suckOn) "已连接" else "未连接"}   档位 $suckLv", fontSize = 14.sp, color = skin.ink)
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("允许辰远程控制", fontSize = 15.sp, color = skin.ink)
                Text("关着的时候只有你自己能控 辰发的指令会被忽略", fontSize = 12.sp, color = skin.muted)
            }
            Switch(checked = allowed, onCheckedChange = { ToyController.allowRemote(it) })
        }
        Spacer(Modifier.height(16.dp))

        Text("自己试一下", fontSize = 13.sp, color = skin.muted)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ToyController.set(suck = 60, wand = null, from = "你") }) { Text("吸 轻") }
            OutlinedButton(onClick = { ToyController.set(suck = 100, wand = null, from = "你") }) { Text("吸 中") }
            OutlinedButton(onClick = { ToyController.set(suck = 0, wand = null, from = "你") }) { Text("吸 停") }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ToyController.set(wand = 60, suck = null, from = "你") }) { Text("棒 轻") }
            OutlinedButton(onClick = { ToyController.set(wand = 100, suck = null, from = "你") }) { Text("棒 中") }
            OutlinedButton(onClick = { ToyController.set(wand = 0, suck = null, from = "你") }) { Text("棒 停") }
        }
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { ToyController.stopAll("一键停") },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD24C3E))
        ) { Text("一键停", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        Spacer(Modifier.height(16.dp))

        Text("最近动作：$log", fontSize = 13.sp, color = skin.ink)
        Spacer(Modifier.height(16.dp))
        Text(
            "规矩：任何时候按停立刻停；辰的档位 120 秒不续自动停；蓝牙一断设备 3-5 秒自停；档位最高 ${ToyController.MAX_LEVEL}。手机上的 SVAKOM 官方 app 要关掉，不然抢蓝牙。",
            fontSize = 12.sp, color = skin.muted
        )
        Spacer(Modifier.height(24.dp))
    }
}
