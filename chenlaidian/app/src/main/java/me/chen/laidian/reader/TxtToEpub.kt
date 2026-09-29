package me.chen.laidian.reader

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 0929 TXT → 最简 EPUB（foliate-js 不认纯文本，导入时转一次，之后就是普通 epub）。
 *  - 编码：BOM 优先；没有 BOM 先按严格 UTF-8 解，解不动就当 GB18030（国内 txt 常见的 GBK 是它的子集）
 *  - 分章：行首「第X章/节/回/卷/部/集」（X 是中文数字或阿拉伯数字）且这一行不长；另认几个常见的「楔子/序章/番外…」
 *  - 一章都没有：每 ~3000 字切一段
 *  - 书名取文件名（去掉扩展名）
 */
object TxtToEpub {
    private const val CHUNK = 3000
    private val CHAPTER = Regex("""^\s*第[一二三四五六七八九十百千零〇两0-9０-９]+[章节回卷部集]""")
    private val SPECIAL = Regex("""^\s*(楔子|序章|序言|序幕|前言|引子|尾声|后记|番外|终章|结局|大结局)""")

    /** 返回 (文本, 用的编码名) */
    fun decode(bytes: ByteArray): Pair<String, String> {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to "UTF-16LE"
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to "UTF-16BE"
        try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return dec.decode(ByteBuffer.wrap(bytes)).toString() to "UTF-8"
        } catch (_: CharacterCodingException) {}
        return String(bytes, Charset.forName("GB18030")) to "GB18030"
    }

    private fun isHeading(line: String): Boolean {
        if (line.isEmpty() || line.length > 50) return false
        if (CHAPTER.containsMatchIn(line)) return true
        return line.length <= 20 && SPECIAL.containsMatchIn(line)
    }

    /** 章节列表：(标题, 段落们)。没识别出章就按字数切 */
    fun split(text: String): List<Pair<String, List<String>>> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val chapters = mutableListOf<Pair<String, MutableList<String>>>()
        var title = ""
        var cur = mutableListOf<String>()
        var found = false
        for (raw in lines) {
            val line = raw.trim()
            if (isHeading(line)) {
                found = true
                if (cur.isNotEmpty() || title.isNotEmpty()) chapters += (title.ifEmpty { "（开头）" }) to cur
                title = line
                cur = mutableListOf()
            } else if (line.isNotEmpty()) cur.add(line)
        }
        if (cur.isNotEmpty() || title.isNotEmpty()) chapters += (title.ifEmpty { "（开头）" }) to cur
        if (found && chapters.isNotEmpty()) return chapters

        // 没有章：按 ~3000 字一段，段落不拆开
        val paras = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<Pair<String, List<String>>>()
        var buf = mutableListOf<String>()
        var n = 0
        for (p in paras) {
            buf.add(p); n += p.length
            if (n >= CHUNK) { out += "第 ${out.size + 1} 段" to buf; buf = mutableListOf(); n = 0 }
        }
        if (buf.isNotEmpty()) out += "第 ${out.size + 1} 段" to buf
        if (out.isEmpty()) out += "（空）" to listOf("（这个文件里没有文字）")
        return out
    }

    /** 转换：src 是原 txt，out 是要写的 epub。返回章节数 */
    fun convert(src: File, title: String, out: File): Int {
        val (text, _) = decode(src.readBytes())
        val chapters = split(text)
        val uid = "urn:uuid:" + UUID.randomUUID()
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
            // EPUB 规矩：mimetype 必须是第一个条目且不压缩
            val mt = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val e = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mt.size.toLong()
                compressedSize = mt.size.toLong()
                crc = CRC32().apply { update(mt) }.value
            }
            zip.putNextEntry(e); zip.write(mt); zip.closeEntry()
            put(zip, "META-INF/container.xml", CONTAINER)
            put(zip, "OEBPS/content.opf", opf(title, uid, modified, chapters.size))
            put(zip, "OEBPS/nav.xhtml", nav(title, chapters))
            put(zip, "OEBPS/toc.ncx", ncx(title, uid, chapters))
            put(zip, "OEBPS/style.css", CSS)
            chapters.forEachIndexed { i, (t, paras) -> put(zip, "OEBPS/text/${name(i)}.xhtml", chapter(t, paras)) }
        }
        return chapters.size
    }

    private fun name(i: Int) = "ch%04d".format(Locale.US, i + 1)

    private fun put(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** XML 转义 + 去掉 XML 不允许的字符（控制符、孤立代理对、FFFE/FFFF），不然 WebView 解析 XHTML 会整章黄屏 */
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c.isHighSurrogate() -> {
                    if (i + 1 < s.length && s[i + 1].isLowSurrogate()) { sb.append(c).append(s[i + 1]); i++ }
                }
                c.isLowSurrogate() -> {}
                c.code == 0xFFFE || c.code == 0xFFFF -> {}
                c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private const val CONTAINER = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    private const val CSS = """body { margin: 0; padding: 0; }
h2 { font-size: 1.3em; font-weight: bold; text-align: center; margin: 1.2em 0 1em; }
p { text-indent: 2em; margin: 0 0 0.7em; }
"""

    private fun opf(title: String, uid: String, modified: String, n: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid" xml:lang="zh-CN">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">$uid</dc:identifier>
    <dc:title>${esc(title)}</dc:title>
    <dc:language>zh-CN</dc:language>
    <meta property="dcterms:modified">$modified</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="css" href="style.css" media-type="text/css"/>
""")
        for (i in 0 until n) sb.append("""    <item id="${name(i)}" href="text/${name(i)}.xhtml" media-type="application/xhtml+xml"/>
""")
        sb.append("""  </manifest>
  <spine toc="ncx">
""")
        for (i in 0 until n) sb.append("""    <itemref idref="${name(i)}"/>
""")
        sb.append("""  </spine>
</package>
""")
        return sb.toString()
    }

    private fun nav(title: String, chapters: List<Pair<String, List<String>>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN" lang="zh-CN">
<head><title>${esc(title)}</title></head>
<body>
<nav epub:type="toc" id="toc"><h1>目录</h1><ol>
""")
        chapters.forEachIndexed { i, (t, _) -> sb.append("""<li><a href="text/${name(i)}.xhtml">${esc(t)}</a></li>
""") }
        sb.append("""</ol></nav>
</body>
</html>
""")
        return sb.toString()
    }

    private fun ncx(title: String, uid: String, chapters: List<Pair<String, List<String>>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1" xml:lang="zh-CN">
<head><meta name="dtb:uid" content="$uid"/><meta name="dtb:depth" content="1"/></head>
<docTitle><text>${esc(title)}</text></docTitle>
<navMap>
""")
        chapters.forEachIndexed { i, (t, _) ->
            sb.append("""<navPoint id="np${i + 1}" playOrder="${i + 1}"><navLabel><text>${esc(t)}</text></navLabel><content src="text/${name(i)}.xhtml"/></navPoint>
""")
        }
        sb.append("""</navMap>
</ncx>
""")
        return sb.toString()
    }

    private fun chapter(title: String, paras: List<String>): String {
        val sb = StringBuilder(paras.sumOf { it.length } + 512)
        sb.append("""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN" lang="zh-CN">
<head><title>${esc(title)}</title><link rel="stylesheet" type="text/css" href="../style.css"/></head>
<body>
<h2>${esc(title)}</h2>
""")
        // 每段独占一行：Range.toString() 拿本页原文时段与段之间才有换行
        for (p in paras) sb.append("<p>").append(esc(p)).append("</p>\n")
        sb.append("""</body>
</html>
""")
        return sb.toString()
    }
}
