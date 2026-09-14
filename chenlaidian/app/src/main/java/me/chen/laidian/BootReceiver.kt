package me.chen.laidian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 开机自动上线（OPPO 上还要在设置里允许自启动）。
 * 0.55 起覆盖安装也拉起：装新版时系统杀掉旧进程并清掉所有闹钟，她不点开 app 服务就一直死着
 * （0914 0.53 发过去没装/装了没开，查岗从 17:17 静默到晚上）。这两个广播都在"后台可起前台服务"的豁免名单里。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ContextCompat.startForegroundService(
                context, Intent(context, ChenService::class.java).setAction(ChenService.ACTION_START)
            )
        }
    }
}
