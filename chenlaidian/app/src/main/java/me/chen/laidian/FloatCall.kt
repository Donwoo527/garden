package me.chen.laidian

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** 0.34 微信同款通话悬浮小窗（她0910指定形态）：白圆角块+📞+时长 可拖 松手贴边 点击回通话页 */
object FloatCall {
    private var wm: WindowManager? = null
    private var view: View? = null
    private var timeTv: TextView? = null
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val ts = ChenService.callStartTs
            if (ts > 0) {
                val s = ((System.currentTimeMillis() - ts) / 1000).toInt()
                timeTv?.text = String.format("%02d:%02d", s / 60, s % 60)
            }
            main.postDelayed(this, 1000)
        }
    }

    fun canShow(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    @SuppressLint("ClickableViewAccessibility")
    fun show(ctx: Context) {
        if (view != null || !canShow(ctx)) return
        val app = ctx.applicationContext
        wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = app.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val box = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() }
            elevation = dp(6).toFloat()
        }
        // 0.41 她点单：图标/时长在小窗里显式居中（光靠容器gravity实机上偏左）
        box.addView(TextView(app).apply { text = "📞"; textSize = 22f; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL })
        timeTv = TextView(app).apply { text = "00:00"; textSize = 13f; setTextColor(Color.parseColor("#34B559")); gravity = Gravity.CENTER }
        box.addView(timeTv,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL })

        val lp = WindowManager.LayoutParams(
            dp(76), dp(76),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(180) }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        box.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (!moved && (abs(dx) > dp(4) || abs(dy) > dp(4))) moved = true
                    lp.x = startX + dx; lp.y = startY + dy
                    try { wm?.updateViewLayout(box, lp) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        app.startActivity(
                            Intent(app, CallActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                .putExtra("resume", true)
                        )
                    } else {
                        val w = app.resources.displayMetrics.widthPixels
                        lp.x = if (lp.x + dp(38) < w / 2) dp(8) else w - dp(84)
                        try { wm?.updateViewLayout(box, lp) } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }
        view = box
        try { wm?.addView(box, lp); main.post(ticker) } catch (_: Exception) { view = null }
    }

    fun hide() {
        main.removeCallbacks(ticker)
        view?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        view = null; timeTv = null
    }
}
