package me.chen.laidian

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃自动上报：崩了先把堆栈写进文件，下次启动把文件内容当一条"收藏"POST 到聊天后端（X-Token 接口里唯一能塞长文本的），
 * 辰在 VPS 上 curl /favorites 就能看到。手机上没有 logcat，这是最省事的通道。
 */
class ChenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val file = File(filesDir, "crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file.writeText("v${BuildConfig.VERSION_NAME} thread=${t.name}\n$sw")
            } catch (_: Exception) {}
            previous?.uncaughtException(t, e)
        }
        // 0.78 安全模式只兜一次启动：crash.txt 一读到就改名成待上传 下次启动不再进安全模式；
        // 上传失败的话留在 pending 里每次启动重试 不拿聊天页当人质（0916 她：上传一直不成功 页面一直是旧的）
        val pending = File(filesDir, "crash_pending.txt")
        if (file.exists()) {
            AppState.safeMode = true
            if (!file.renameTo(pending)) { try { pending.writeText(file.readText()); file.delete() } catch (_: Exception) {} }
        }
        if (pending.exists()) {
            val text = pending.readText().take(3000)
            Thread {
                val ok = me.chen.laidian.net.ChatApi.reportCrash(applicationContext, text)
                if (ok) pending.delete()
            }.start()
        }
    }
}
