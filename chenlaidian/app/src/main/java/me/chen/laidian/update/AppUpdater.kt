package me.chen.laidian.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import me.chen.laidian.BuildConfig
import me.chen.laidian.Tls
import me.chen.laidian.net.ChatClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 0926 应用内自更新。
 * 服务端 publish_app.sh 往 chat-app/data/apk/ 放 chenlaidian-<版本>.apk + latest.json；
 * 这里 GET /apk/latest 拿 versionCode 比 BuildConfig.VERSION_CODE，大了就下到 cache/update/ 再拉系统安装器。
 * 约定：checkLatest / download 是阻塞的，在 Dispatchers.IO 里调；install 在主线程调。
 */
object AppUpdater {
    private const val TOKEN = "chen_home_2026"

    data class Latest(val versionCode: Int, val versionName: String, val url: String, val notes: String) {
        val isNewer get() = versionCode > BuildConfig.VERSION_CODE
    }

    /** 启动自查 / 手动查到的新版本；null = 没有新版或还没查。设置 tab 的小红点和「x 可更新」那行都订阅它 */
    val available = MutableStateFlow<Latest?>(null)
    /** 最近一次失败原因（照 ChatApi.lastError 的路子：Toast 里给她看得见的字，别只剩"失败"两个字） */
    @Volatile var lastError: String? = null
    /** 一次启动只自查一次：进程级标记，切后台回来 / Activity 重建都不重查，杀掉重开才查 */
    @Volatile private var launchChecked = false

    // Tls.client 的 readTimeout 是 0（给 ws 长连用）；这里查更新和下载都要能超时，不然梯子一断就卡死
    private fun http(ctx: Context) = Tls.client(ctx.applicationContext).newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** GET /apk/latest。返回服务端最新版（不管新不新，调用方看 isNewer）；网络/解析失败返回 null 并写 lastError */
    fun checkLatest(ctx: Context): Latest? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/apk/latest").header("X-Token", TOKEN).get().build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) { lastError = "查更新 HTTP ${r.code}"; return null }
                val o = JSONObject(r.body?.string() ?: run { lastError = "查更新 空响应"; return null })
                val code = o.optInt("versionCode", 0)
                val url = o.optString("url", "")
                if (code <= 0 || url.isBlank()) { lastError = "latest.json 缺 versionCode/url"; return null }
                Latest(code, o.optString("versionName", code.toString()), url, o.optString("notes", ""))
            }
        } catch (e: Exception) { lastError = "查更新 ${e.javaClass.simpleName}${e.message?.let { ": " + it.take(60) } ?: ""}"; null }
    }

    /** app 启动时后台查一次；有新版写进 available（UI 自己订阅）。不阻塞、不弹窗 */
    fun checkOnLaunch(ctx: Context) {
        if (launchChecked) return
        launchChecked = true
        val app = ctx.applicationContext
        Thread {
            val r = checkLatest(app)
            if (r != null && r.isNewer) available.value = r
        }.apply { isDaemon = true }.start()
    }

    /**
     * 下载 apk 到 cache/update/<服务端文件名>（如 chenlaidian-0.109.apk；对应 file_paths.xml 里的 update/ 路径）。
     * 0.109：以前固定存成 update.apk——0927 夜她点「安装」0.108，OPPO 安装器弹「已安装相同版本 0.107」：
     * 每版的 content:// 地址一模一样（这几版 apk 连大小都一样 10019818 B），安装器认了上回的。改成每版一个名字 旧的先删。
     * onProgress 0..100，在下载线程里回调（Compose 状态跨线程写是安全的，UI 直接赋值就行）。
     */
    fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File? {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "update-${System.currentTimeMillis()}.apk" }
        dir.listFiles()?.forEach { if (it.name != name) it.delete() }   // 旧版本的包清掉 不占地方
        val f = File(dir, name)
        val req = Request.Builder().url(ChatClient.mediaUrl(url)).header("X-Token", TOKEN).get().build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) { lastError = "下载 HTTP ${r.code}"; return null }
                val body = r.body ?: run { lastError = "下载 空响应"; return null }
                val total = body.contentLength()   // 服务端 FileResponse 带 Content-Length；万一是 -1 就只报 0 和 100
                var got = 0L
                var lastPct = -1
                onProgress(0)
                body.byteStream().use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (total > 0) {
                                val pct = (got * 100 / total).toInt().coerceIn(0, 99)
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                }
                if (total > 0 && got != total) { lastError = "下载不完整 $got/$total"; f.delete(); return null }
                if (got < 1024 * 1024) { lastError = "下载到的文件太小（$got B）不像 apk"; f.delete(); return null }
                onProgress(100)
                f
            }
        } catch (e: Exception) {
            f.delete()
            lastError = "下载 ${e.javaClass.simpleName}${e.message?.let { ": " + it.take(60) } ?: ""}"
            null
        }
    }

    /** 系统是否允许辰来电装应用（8.0+ 是逐 app 的开关，minSdk 26 所以基本总要查） */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** 跳到系统「允许安装未知应用」页（带 package: 直接定位到辰来电，不用她在列表里找） */
    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * 拉起系统安装器。返回 false = 还没权限，已经跳去设置页了，她开完开关回来要再点一次「安装」。
     * 装完系统发 MY_PACKAGE_REPLACED，BootReceiver 接着把电话服务拉起来（manifest 里早有）。
     */
    fun install(ctx: Context, file: File): Boolean {
        if (!canInstall(ctx)) { openInstallPermission(ctx); return false }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }
}
