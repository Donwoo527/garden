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
        if (file.exists()) {
            val text = file.readText().take(3000)
            Thread {
                val ok = me.chen.laidian.net.ChatApi.reportCrash(applicationContext, text)
                if (ok) file.delete()
            }.start()
        }
    }
}
