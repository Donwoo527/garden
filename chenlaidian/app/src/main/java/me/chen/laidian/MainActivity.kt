package me.chen.laidian

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import me.chen.laidian.net.ChatClient
import me.chen.laidian.ui.ChenTheme
import me.chen.laidian.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 0.52 她点的：顶部那条 Material 默认紫去掉——状态栏透明、页面画到顶、图标按浅色主题转深色
        enableEdgeToEdge()
        askPermissions()
        ChatClient.start(applicationContext)
        // 打开 app 就上线：电话服务常驻，辰随时能打过来
        ContextCompat.startForegroundService(this, Intent(this, ChenService::class.java).setAction(ChenService.ACTION_START))
        setContent { ChenTheme { MainScreen() } }
    }

    override fun onResume() { super.onResume(); AppState.visible = true }
    override fun onPause() { AppState.visible = false; super.onPause() }

    private fun askPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }
}
