package me.chen.laidian.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/** 跟网页版一样：长边限 1600px，JPEG 82%，几 MB 的原图压到几百 K。 */
object ImageUtil {
    private const val MAX = 1600

    /** 压缩；失败返回 null 并把原因写进 ChatApi.lastError（0915 她那条"表情没存上：读图/压缩失败"之前只有四个字） */
    fun compress(ctx: Context, uri: Uri): ByteArray? {
        return try {
            // ⚠️0915 根因：inJustDecodeBounds=true 时 decodeStream 本来就返回 null，之前拿它的返回值判"打不开"= 永远失败。
            // 这就是 0914"图片发送失败"和 0915"表情没存上"的真凶（相册权限只是顺手加的）。判成功看 bounds.outWidth
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val probe = ctx.contentResolver.openInputStream(uri) ?: run { ChatApi.lastError = "打不开相册给的地址"; return null }
            probe.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) { ChatApi.lastError = "解码失败（${bounds.outMimeType ?: "未知格式"}）"; return null }
            var sample = 1
            while (bounds.outWidth / sample > MAX * 2 || bounds.outHeight / sample > MAX * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: run { ChatApi.lastError = "解码失败（${bounds.outMimeType ?: "未知格式"}）"; return null }
            val scale = minOf(1f, MAX.toFloat() / maxOf(bmp.width, bmp.height))
            val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
        } catch (e: Exception) { ChatApi.lastError = "读图 ${e.javaClass.simpleName}${e.message?.let { ": " + it.take(60) } ?: ""}"; null }
    }

    /** 0915 兜底：解码不了就原样读出来（动图/webp/奇怪格式），返回 字节+mime+扩展名 */
    fun readRaw(ctx: Context, uri: Uri): Triple<ByteArray, String, String>? {
        return try {
            val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            val ext = when (mime) { "image/png" -> ".png"; "image/webp" -> ".webp"; "image/gif" -> ".gif"; "image/heic", "image/heif" -> ".heic"; else -> ".jpg" }
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: run { ChatApi.lastError = "打不开相册给的地址"; return null }
            Triple(bytes, mime, ext)
        } catch (e: Exception) { ChatApi.lastError = "读原图 ${e.javaClass.simpleName}${e.message?.let { ": " + it.take(60) } ?: ""}"; null }
    }

    /** 压得动就压（JPEG）；压不动原样传。返回 /media 地址 */
    fun compressOrRawUpload(ctx: Context, uri: Uri): String? {
        compress(ctx, uri)?.let { return ChatApi.uploadImage(ctx, it) }
        val (bytes, mime, ext) = readRaw(ctx, uri) ?: return null
        return ChatApi.uploadBytes(ctx, bytes, "raw$ext", mime)
    }
}
