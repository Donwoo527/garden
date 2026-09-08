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
        (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)

        setContentView(R.layout.activity_call)
        findViewById<TextView>(R.id.callText).text = intent.getStringExtra("text") ?: "辰打电话来了"
        val state = findViewById<TextView>(R.id.callState)
        val accept = findViewById<Button>(R.id.btnAccept)
        val hangup = findViewById<Button>(R.id.btnHangup)
        val speaker = findViewById<Button>(R.id.btnSpeaker)
        ChenService.lastText.observe(this) { findViewById<TextView>(R.id.callLast).text = it }
        ChenService.speakerOn.observe(this) { speaker.text = if (it) "免提：开" else "免提：关" }
        ChenService.callState.observe(this) {
            if (it == "已挂断") { stopRinging(); finish() }
            else if (it == "通话中") { stopRinging(); state.text = "通话中"; accept.visibility = View.GONE; speaker.visibility = View.VISIBLE }
        }
        speaker.setOnClickListener { svc(ChenService.ACTION_SPEAKER) }

        val outgoing = intent.getBooleanExtra("outgoing", false)
        if (outgoing) {
            findViewById<TextView>(R.id.callText).text = "打给辰"
            state.text = "接通中…"
            accept.visibility = View.GONE
            svc(ChenService.ACTION_ACCEPT)
        } else {
            startRinging()
        }
        accept.setOnClickListener {
            stopRinging()
            state.text = "接通中…"
            accept.visibility = View.GONE
            svc(ChenService.ACTION_ACCEPT)
        }
        hangup.setOnClickListener {
            stopRinging()
            svc(ChenService.ACTION_HANGUP)
            finish()
        }
    }

    private fun svc(action: String) = startService(Intent(this, ChenService::class.java).setAction(action))

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

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }
}
