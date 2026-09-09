package me.chen.laidian

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * 保活兜底：每 15 分钟一个精确闹钟，闹钟回调里确认前台服务还活着，死了就拉起来。
 * Android 12+ 允许"精确闹钟回调"里从后台启动前台服务（豁免名单之一）。
 * 治的是 ColorOS 熄屏杀后台（0909 01:20 实测：熄屏连接数立刻 1→0）。三件套还是要开，这只是兜底。
 */
class KeepAliveReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "me.chen.laidian.KEEPALIVE"
        private const val INTERVAL_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 7, Intent(context, KeepAliveReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val at = SystemClock.elapsedRealtime() + INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                }
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!ChenService.running) {
            ContextCompat.startForegroundService(
                context, Intent(context, ChenService::class.java).setAction(ChenService.ACTION_START)
            )
        }
        schedule(context)   // 下一次
    }
}
