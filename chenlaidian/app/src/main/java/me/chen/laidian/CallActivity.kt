package me.chen.laidian

import android.app.KeyguardManager
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 来电全屏页：锁屏上也能弹出来、亮屏、响铃、震动。接听后变通话页（M2 才有声音）。 */
class CallActivity : AppCompatActivity() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 0.35 毛玻璃：半透明窗口+背后模糊(Android 12+) 低版本只半透明也能透出一点背景
        if (Build.VERSION.SDK_INT >= 31) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 64 }
        }
        (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)

        setContentView(R.layout.activity_call)
        findViewById<TextView>(R.id.callText).text = intent.getStringExtra("text") ?: "辰打电话来了"
        val state = findViewById<TextView>(R.id.callState)
        val accept = findViewById<Button>(R.id.btnAccept)
        val acceptWrap = findViewById<View>(R.id.acceptWrap)
        val hangup = findViewById<Button>(R.id.btnHangup)
        val speaker = findViewById<Button>(R.id.btnSpeaker)
        // 0.32 字幕改累积历史：追加显示可回翻；在底部时新句自动滚下来，手动上翻时不抢
        val cap = findViewById<TextView>(R.id.callLast)
        val capScroll = findViewById<android.widget.ScrollView>(R.id.capScroll)
        ChenService.lastText.observe(this) { line ->
            if (line.isNullOrBlank()) return@observe
            val child = capScroll.getChildAt(0)
            val atBottom = child == null || child.bottom <= capScroll.height + capScroll.scrollY + 60
            cap.append((if (cap.text.isEmpty()) "" else "\n\n") + line)
            if (atBottom) capScroll.post { capScroll.fullScroll(View.FOCUS_DOWN) }
        }
        ChenService.speakerOn.observe(this) { speaker.text = if (it) "免提：开" else "免提：关" }
        ChenService.callState.observe(this) {
            if (it == "已挂断") { stopRinging(); beepEnd(); finish() }
            else if (it == "通话中") { stopRinging(); state.text = "通话中"; acceptWrap.visibility = View.GONE; speaker.visibility = View.VISIBLE }
        }
        speaker.setOnClickListener { svc(ChenService.ACTION_SPEAKER) }
        // 0.35 她点名的可见缩小按钮：点了回上一页 通话不断 浮窗由onDestroy兜底弹出
        findViewById<TextView>(R.id.btnMinimize).setOnClickListener { finish() }

        val outgoing = intent.getBooleanExtra("outgoing", false)
        val resume = intent.getBooleanExtra("resume", false)   // 0.34 从悬浮小窗点回来
        if (resume) {
            findViewById<TextView>(R.id.callText).text = "通话中"
            state.text = ChenService.callState.value ?: "通话中"
            acceptWrap.visibility = View.GONE
        } else if (outgoing) {
            findViewById<TextView>(R.id.callText).text = "打给辰"
            state.text = "接通中…"
            acceptWrap.visibility = View.GONE
            svc(ChenService.ACTION_ACCEPT)
        } else {
            startRinging()
        }
        // 0.34 悬浮窗权限引导（一次性提示 不强跳）
        if (!FloatCall.canShow(this)) {
            android.widget.Toast.makeText(this, "想让通话缩成小窗的话 给辰来电开一下\"悬浮窗/显示在其他应用上层\"权限", android.widget.Toast.LENGTH_LONG).show()
        }
        accept.setOnClickListener {
            stopRinging()
            state.text = "接通中…"
            acceptWrap.visibility = View.GONE
            svc(ChenService.ACTION_ACCEPT)
        }
        hangup.setOnClickListener {
            stopRinging()
            svc(ChenService.ACTION_HANGUP)
            finish()
        }
    }

    private fun svc(action: String) = startService(Intent(this, ChenService::class.java).setAction(action))

    /** 0.32 挂断提示音（她以为有 现在真有了）：轻双哔 */
    private fun beepEnd() {
        try {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 80)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 180)
            android.os.Handler(mainLooper).postDelayed({ try { tg.release() } catch (_: Exception) {} }, 500)
        } catch (_: Exception) {}
    }

    private fun startRinging() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
    }

    private fun stopRinging() {
        ringtone?.stop()
        vibrator?.cancel()
    }

    override fun onUserLeaveHint() {
        // 0.34 用户按Home/切走且在通话中 → 缩成悬浮小窗（通话在Service里继续）
        if (ChenService.callState.value == "通话中") FloatCall.show(this)
        super.onUserLeaveHint()
    }

    override fun onResume() {
        super.onResume()
        FloatCall.hide()
    }

    override fun onDestroy() {
        stopRinging()
        // 0.35 浮窗全路径：无论返回键/缩小按钮/别的方式离开 只要还在通话 小方块都出来
        if (ChenService.callState.value == "通话中") FloatCall.show(applicationContext)
        super.onDestroy()
    }
}
