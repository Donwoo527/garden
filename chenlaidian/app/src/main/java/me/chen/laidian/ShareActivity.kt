package me.chen.laidian

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.net.ChatApi
import me.chen.laidian.net.ImageUtil
import java.io.File

/**
 * 0.116 相册（或任何 app）选图 → 分享 →「发给辰」。自己不露面：
 * 分享来的地址读权限只给这个 Activity，它一 finish 系统就可能收回——所以趁活着把图原样拷进 cache/share/，
 * 放进 ShareInbox，再像点桌面图标一样把 app 拉到前台，确认框在 MainScreen 里弹。
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val all = sharedUris(intent)
        if (all.isEmpty()) { toast("读不到这张图"); finish(); return }
        val uris = all.take(ShareInbox.MAX)
        if (all.size > uris.size) toast("一次最多 ${ShareInbox.MAX} 张，只发前 ${ShareInbox.MAX} 张")
        lifecycleScope.launch {
            val (files, why) = withContext(Dispatchers.IO) { copyIn(uris) }
            val reason = why?.let { "（$it）" } ?: ""
            if (files.isEmpty()) {
                toast((if (uris.size == 1) "读不到这张图" else "这 ${uris.size} 张都读不到") + reason)
            } else {
                if (files.size < uris.size) toast("有 ${uris.size - files.size} 张读不到，跳过了$reason")
                ShareInbox.offer(files)
                // 跟桌面图标发的 intent 一模一样（不用 getLaunchIntentForPackage：它多带一个 package，老系统上跟桌面那份比不相等会再压一个 MainActivity）
                // app 开着 = 原任务拉到前台，不多开 MainActivity、不关通话页；没开 = 正常冷启动
                startActivity(
                    Intent.makeMainActivity(ComponentName(this@ShareActivity, MainActivity::class.java))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                )
            }
            finish()
        }
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show()

    private fun sharedUris(i: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()   // 系统会把 EXTRA_STREAM 抄一份进 clipData，两边都读、去重
        runCatching {
            if (i.action == Intent.ACTION_SEND_MULTIPLE)
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.filterIsInstance<Uri>()?.let { out += it }
            else IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
        }
        runCatching { i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { out += it } } }
        // 只收别的 app 给的 content://：file:// 和自家 FileProvider 的地址能被拿来把 app 自己的私有文件骗出去
        return out.filter { it.scheme == ContentResolver.SCHEME_CONTENT && it.authority?.startsWith(packageName) != true }
    }

    /** 原样拷，不在这儿压（压缩/原样传留到发送时走聊天发图那条路）。返回 拷到的文件 + 第一个失败原因 */
    private fun copyIn(uris: List<Uri>): Pair<List<File>, String?> {
        val dir = File(cacheDir, ShareInbox.DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > 24 * 3600 * 1000L) it.delete() }
        val files = mutableListOf<File>()
        var why: String? = null
        for ((n, u) in uris.withIndex()) {
            val mime = try { contentResolver.getType(u) } catch (_: Exception) { null }
            // filter 写的 image/*，但发送方用 */* 时视频/文件也会混进来
            if (mime != null && !mime.startsWith("image/")) { why = why ?: "不是图片"; continue }
            val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"
            val f = File(dir, "s${now}_$n.$ext")
            try {
                val size = contentResolver.openInputStream(u)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: 0L
                if (size > 0) { files += f; continue }
                why = why ?: "打不开分享来的地址"
            } catch (e: Exception) {   // SecurityException：没给读权限；FileNotFoundException：图已经不在了
                why = why ?: e.javaClass.simpleName
            }
            f.delete()
        }
        return files to why
    }
}

/**
 * 0.116 分享进来、等她在确认框点「发送」的图（进程级，新分享顶掉旧的）。
 * 发送也在这儿跑：弹窗一关、MainActivity 被回收都不打断上传。
 */
object ShareInbox {
    const val MAX = 9
    const val DIR = "share"
    val pending = MutableStateFlow<List<File>>(emptyList())
    val sending = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 主线程调。旧的待发连缓存一起丢 */
    fun offer(files: List<File>) {
        val old = pending.value
        pending.value = files
        old.forEach { it.delete() }
    }

    fun cancel() = offer(emptyList())

    /** 走聊天发相册图同一条路（JetConversation.sendPicked 不勾原图）：compressOrRawUpload → sendImages。返回有没有真的发起 */
    fun send(ctx: Context, caption: String): Boolean {
        val files = pending.value
        if (files.isEmpty() || sending.value) return false
        pending.value = emptyList()
        sending.value = true
        val app = ctx.applicationContext
        scope.launch {
            ChatApi.lastError = null
            val urls = withContext(Dispatchers.IO) {
                files.mapNotNull { f ->
                    // 照拍照那条用自家 FileProvider 地址：getType 按扩展名给 mime，压不动原样传时 png/gif 才不会被当成 jpg
                    runCatching { FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f) }.getOrNull()
                        ?.let { ImageUtil.compressOrRawUpload(app, it) }
                }
            }
            val ok = urls.isNotEmpty() && withContext(Dispatchers.IO) { ChatApi.sendImages(app, urls, caption) }
            sending.value = false
            files.forEach { it.delete() }
            val err = ChatApi.lastError
            val msg = when {
                !ok -> "图片发送失败：" + (err ?: if (urls.isEmpty()) "读图/压缩失败" else "发送被拒")
                // 0915 的教训：少传几张、走了原样兜底，都得说出来
                urls.size < files.size -> "发了 ${urls.size}/${files.size} 张，有几张没传上" + (err?.let { "（$it）" } ?: "")
                err != null -> "发了 但压缩没走通（$err）发的是原图"
                else -> null
            }
            msg?.let { Toast.makeText(app, it, Toast.LENGTH_LONG).show() }
        }
        return true
    }
}
