package me.chen.laidian

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvStatus = findViewById<TextView>(R.id.status)
        val tvLast = findViewById<TextView>(R.id.last)
        ChenService.status.observe(this) { tvStatus.text = it }
        ChenService.lastText.observe(this) { tvLast.text = it }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            ContextCompat.startForegroundService(this, svcIntent(ChenService.ACTION_START))
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener { startService(svcIntent(ChenService.ACTION_STOP)) }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            ContextCompat.startForegroundService(this, svcIntent(ChenService.ACTION_TEST_CALL))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btnFullScreen).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 34) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (nm.canUseFullScreenIntent()) {
                    Toast.makeText(this, "已经有全屏来电权限了", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                    )
                }
            } else {
                Toast.makeText(this, "这个系统版本不用单独开", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        askPermissions()
    }

    private fun svcIntent(action: String) = Intent(this, ChenService::class.java).setAction(action)

    private fun askPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }
}
