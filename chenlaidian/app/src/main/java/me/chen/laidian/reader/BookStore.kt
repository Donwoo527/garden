package me.chen.laidian.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** 书架上的一本：书目 json（filesDir/books/library.json）里的一条 */
data class Book(
    val id: String,          // 文件内容 sha1 前 12 位
    var title: String,
    val format: String,      // epub | txt | pdf（其余按扩展名：mobi/azw3/fb2/cbz）
    val file: String,        // books/ 下的文件名，如 a1b2c3d4e5f6.epub（txt 转出来的也是 .epub，format 仍记 txt）
    val added: Long,         // 导入时间 ms
    var cfi: String = "",    // 最后位置
    var fraction: Double = 0.0,
    var chapter: String = "",
    var lastRead: Long = 0L, // 最后阅读时间 ms（0 = 还没翻开过）
    var author: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("format", format).put("file", file).put("added", added)
        .put("cfi", cfi).put("fraction", fraction).put("chapter", chapter).put("last_read", lastRead).put("author", author)

    companion object {
        fun from(o: JSONObject) = Book(
            id = o.optString("id"), title = o.optString("title"), format = o.optString("format", "epub"),
            file = o.optString("file"), added = o.optLong("added", 0L),
            cfi = o.optString("cfi", ""), fraction = o.optDouble("fraction", 0.0).takeIf { it.isFinite() } ?: 0.0,
            chapter = o.optString("chapter", ""), lastRead = o.optLong("last_read", 0L), author = o.optString("author", ""),
        )
    }
}

/**
 * 0929 书架：书用 SAF 选进来，拷到 filesDir/books/<id>.<ext>；书目存 library.json。
 * 书只在她手机里，服务端只收到她读过的页——天然不剧透（调研文档里定的）。
 * 重活（导入/转换）在 IO 线程调；进度更新有 async 版本（阅读页每翻一页调一次）。
 */
object BookStore {
    private const val DIR = "books"
    private const val LIB = "library.json"
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "bookstore").apply { isDaemon = true } }

    class ImportError(msg: String) : Exception(msg)

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    /** 书目：最近读的在前，没读过的按导入时间倒序 */
    fun list(ctx: Context): List<Book> = synchronized(lock) {
        read(ctx).sortedWith(compareByDescending<Book> { maxOf(it.lastRead, it.added) })
    }

    fun get(ctx: Context, id: String): Book? = synchronized(lock) { read(ctx).firstOrNull { it.id == id } }

    /** 给 WebView 拦截用：id 对应的书文件（不认 library.json 本身） */
    fun fileFor(ctx: Context, id: String): File? {
        if (id.isBlank() || id.contains('/') || id.contains("..")) return null
        return dir(ctx).listFiles()?.firstOrNull { it.isFile && it.name != LIB && it.name.substringBeforeLast('.') == id }
    }

    private fun read(ctx: Context): MutableList<Book> {
        val f = File(dir(ctx), LIB)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("books") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Book.from(it) } }.filter { it.id.isNotBlank() }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun write(ctx: Context, list: List<Book>) {
        val f = File(dir(ctx), LIB)
        val tmp = File(dir(ctx), "$LIB.tmp")
        tmp.writeText(JSONObject().put("books", JSONArray(list.map { it.toJson() })).toString())
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    private fun edit(ctx: Context, id: String, change: (Book) -> Unit) = synchronized(lock) {
        val list = read(ctx)
        val b = list.firstOrNull { it.id == id } ?: return@synchronized
        change(b)
        write(ctx, list)
    }

    fun updateProgress(ctx: Context, id: String, cfi: String, fraction: Double, chapter: String) = edit(ctx, id) {
        if (cfi.isNotBlank()) it.cfi = cfi
        if (fraction.isFinite()) it.fraction = fraction.coerceIn(0.0, 1.0)
        it.chapter = chapter
        it.lastRead = System.currentTimeMillis()
    }

    fun updateProgressAsync(ctx: Context, id: String, cfi: String, fraction: Double, chapter: String) {
        val app = ctx.applicationContext
        io.execute { try { updateProgress(app, id, cfi, fraction, chapter) } catch (_: Exception) {} }
    }

    /** 书打开后 foliate 读到的元数据：标题比文件名靠谱就换掉 */
    fun updateMetaAsync(ctx: Context, id: String, title: String, author: String) {
        val app = ctx.applicationContext
        io.execute {
            try {
                edit(app, id) {
                    if (title.isNotBlank()) it.title = title
                    if (author.isNotBlank()) it.author = author
                }
            } catch (_: Exception) {}
        }
    }

    fun delete(ctx: Context, id: String) = synchronized(lock) {
        val list = read(ctx)
        val b = list.firstOrNull { it.id == id }
        if (b != null) { list.remove(b); write(ctx, list) }
        fileFor(ctx, id)?.delete()
    }

    /**
     * 从 SAF 的 uri 导入：拷进来同时算 sha1 → 认格式 → txt 转 epub → 记书目。抛 ImportError 给页面 Toast。
     * 同一本（内容一样）再导一次直接返回已有的那条。
     */
    fun importUri(ctx: Context, uri: Uri): Book {
        val cr = ctx.contentResolver
        var name = "未命名"
        try { cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name } } catch (_: Exception) {}
        val d = dir(ctx)
        val tmp = File(d, "import_${System.currentTimeMillis()}.tmp")
        val sha = MessageDigest.getInstance("SHA-1")
        var total = 0L
        try {
            (cr.openInputStream(uri) ?: throw ImportError("打不开这个文件")).use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); sha.update(buf, 0, n); total += n
                    }
                }
            }
            if (total == 0L) throw ImportError("文件是空的")
            val id = sha.digest().joinToString("") { "%02x".format(it) }.take(12)
            get(ctx, id)?.let { tmp.delete(); return it }

            val ext = name.substringAfterLast('.', "").lowercase()
            val head = ByteArray(4096).let { b -> tmp.inputStream().use { s -> val n = s.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) } }
            val format = detect(head, ext)
            val title = name.substringBeforeLast('.').ifBlank { name }.ifBlank { "未命名" }
            val fileName: String
            when (format) {
                "txt" -> {
                    fileName = "$id.epub"
                    val out = File(d, fileName)
                    try { TxtToEpub.convert(tmp, title, out) } catch (e: Exception) { out.delete(); throw ImportError("txt 转换失败：${e.message ?: e.javaClass.simpleName}") }
                    tmp.delete()
                }
                else -> {
                    fileName = "$id.$format"
                    val out = File(d, fileName)
                    if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
                }
            }
            val book = Book(id = id, title = title, format = format, file = fileName, added = System.currentTimeMillis())
            synchronized(lock) {
                val list = read(ctx)
                list.removeAll { it.id == id }
                list += book
                write(ctx, list)
            }
            return book
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** 认格式：先看文件头，再看扩展名。认不出就抛 */
    private fun detect(head: ByteArray, ext: String): String {
        val isZip = head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() && head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        val isPdf = head.size >= 5 && String(head, 0, 5, Charsets.US_ASCII) == "%PDF-"
        val isMobi = head.size >= 68 && String(head, 60, 8, Charsets.US_ASCII).let { it == "BOOKMOBI" || it == "TEXtREAd" }
        return when {
            isPdf -> "pdf"
            isZip -> if (ext == "cbz") "cbz" else "epub"
            isMobi -> if (ext == "azw3" || ext == "azw") "azw3" else "mobi"
            ext == "fb2" -> "fb2"
            ext == "txt" || ext == "text" || ext == "md" || looksLikeText(head) -> "txt"
            else -> throw ImportError("不认识这种文件（.$ext）只认 epub / txt / pdf，mobi、azw3、fb2 也能试")
        }
    }

    /** 没扩展名/扩展名怪的：前 4K 里没有 NUL 字节就当文本 */
    private fun looksLikeText(head: ByteArray): Boolean = head.isNotEmpty() && head.none { it == 0.toByte() }
}
