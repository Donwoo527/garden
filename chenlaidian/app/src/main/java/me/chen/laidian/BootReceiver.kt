package me.chen.laidian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** 开机自动上线（OPPO 上还要在设置里允许自启动）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ContextCompat.startForegroundService(
                context, Intent(context, ChenService::class.java).setAction(ChenService.ACTION_START)
            )
        }
    }
}
