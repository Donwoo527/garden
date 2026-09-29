package me.chen.laidian.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 0929 一起听：读系统「正在播放」(MediaSession) 必须是一个启用了的通知监听服务——这就是它。
 * 通知本身不管（onNotificationPosted 空实现）；系统在她给了「通知使用权」后自己把服务绑起来，之后常驻。
 * manifest 里已按 me.chen.laidian.music.MusicListenerService 注册（BIND_NOTIFICATION_LISTENER_SERVICE + intent-filter）。
 */
class MusicListenerService : NotificationListenerService() {

    companion object {
        /** 服务是否已经连上系统（给了权限但没连上 → 页面上给个「重新连接」） */
        val connected = MutableStateFlow(false)

        fun component(ctx: Context) = ComponentName(ctx, MusicListenerService::class.java)

        /** 通知使用权给了没：Settings.Secure.enabled_notification_listeners 里有没有我们 */
        fun hasPermission(ctx: Context): Boolean = try {
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        } catch (_: Exception) { false }

        /** 跳系统「通知使用权」页：11+ 先试直接定位到辰来电那一项，不行再退到列表页 */
        fun openSettings(ctx: Context) {
            val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 30) {
                val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(ctx).flattenToString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(detail); return } catch (_: Exception) {}
            }
            try { ctx.startActivity(list) } catch (_: Exception) {
                Toast.makeText(ctx, "打不开系统设置页 去 设置→通知→通知使用权 里找「辰来电」", Toast.LENGTH_LONG).show()
            }
        }

        /** 给了权限但服务没连上（ColorOS 偶尔这样）：请系统重新绑一次 */
        fun rebind(ctx: Context) {
            try { NotificationListenerService.requestRebind(component(ctx)) } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        MusicReporter.init(this)
        MusicCommands.start(this, "listener")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected.value = true
        NowPlaying.attach(this, component(this))
    }

    override fun onListenerDisconnected() {
        connected.value = false
        NowPlaying.detach()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        connected.value = false
        NowPlaying.detach()
        MusicCommands.stop("listener")
        super.onDestroy()
    }

    // 通知本身不看：我们只要它带来的 MediaSession 读取资格
    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
