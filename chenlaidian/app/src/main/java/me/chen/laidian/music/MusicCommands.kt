package me.chen.laidian.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.chen.laidian.AppState
import me.chen.laidian.R
import me.chen.laidian.net.AppEvents
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0929 一起听：辰的指令。服务端经聊天 ws 下发 music_cmd，ChatClient 丢进 AppEvents.musicCmd，这里订阅执行：
 *  next / prev / pause / play / toggle / seek(position_ms) → 当前会话的 transportControls
 *  open（点歌）→ 拉起播放器放那首（QQ 音乐 / 网易云的 scheme），后台起 Activity 安卓 10+ 会被拦，所以总是同时发一条通知「辰点了《歌名》- 歌手」，她点通知就去放；app 在前台时直接起。
 * 执行结果经 MusicReporter.reportCmd 挂在 /music 的 last_cmd 里回给辰，也写进 lastCmd 给页面看。
 *
 * scheme 依据（0929 查的）：
 *  - QQ 音乐官方分享页 y.qq.com/n3/other/pages/playsong 的 JS：qqmusic://qq.com/media/playSonglist?p=<URL 编码的 JSON>
 *    JSON = {"song":[{"songmid":"<mid>","type":"0"}],"action":"play","hideLoading":"1"}（没有 mid 时键名用 songid）；NFC 教程（博客园 SeanRIchard / xiao1993）同一格式
 *  - 网易云：orpheus://song/<id>（调研文档 + 即刻）；博客园 xiao1993 还多一个尾巴 orpheus://song/<id>/?autoplay=1
 *  - QQ 音乐的「搜索页」scheme 没查到公开资料，只有歌名没有 mid 时退到网页 https://y.qq.com/n/ryqq/search?w=<关键词>&t=song
 *
 * start/stop 按 owner 计数：MusicListenerService 是一个 owner；以后 ChenService 想在没给通知使用权时也能收「点歌」，再加一个 owner 就行，收集器只有一份。
 */
object MusicCommands {
    const val CH_MUSIC = "chen_music"
    private const val NOTIF_BASE = 4000
    private const val SEARCH_URL = "https://y.qq.com/n/ryqq/search?t=song&w="

    /** 最近一条指令和结果（页面显示） */
    val lastCmd = MutableStateFlow("")

    private val owners = HashSet<String>()
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null

    @Synchronized
    fun start(ctx: Context, owner: String) {
        app = ctx.applicationContext
        owners += owner
        createChannel(ctx.applicationContext)
        if (job?.isActive == true) return
        job = scope.launch {
            AppEvents.musicCmd.collect { o ->
                val action = o.optString("action")
                try { handle(o) } catch (e: Exception) {
                    fail(action, "${e.javaClass.simpleName}${e.message?.let { ": " + it.take(60) } ?: ""}")
                }
            }
        }
    }

    @Synchronized
    fun stop(owner: String) {
        owners -= owner
        if (owners.isEmpty()) { job?.cancel(); job = null }
    }

    private fun handle(o: JSONObject) {
        val action = o.optString("action")
        when (action) {
            "next", "prev", "pause", "play", "toggle", "seek" -> transport(action, o)
            "open" -> open(o)
            else -> fail(action, "不认识的指令")
        }
    }

    private fun transport(action: String, o: JSONObject) {
        val ctx = app ?: return fail(action, "还没初始化")
        // 她把「让辰听见我在听什么」关了 = 辰听不见也别动她的播放器；点歌（open）不受这个管，只是一条通知
        if (!MusicReporter.enabled.value) return fail(action, "开关关着（让辰听见我在听什么）")
        val tc = NowPlaying.transport()
            ?: return fail(action, if (!MusicListenerService.hasPermission(ctx)) "没有通知使用权" else "现在没有正在播放的会话")
        val label = when (action) {
            "next" -> { tc.skipToNext(); "下一首" }
            "prev" -> { tc.skipToPrevious(); "上一首" }
            "pause" -> { tc.pause(); "暂停" }
            "play" -> { tc.play(); "播放" }
            "toggle" -> if (NowPlaying.current().state == "playing") { tc.pause(); "暂停" } else { tc.play(); "播放" }
            else -> {   // seek
                val p = o.optLong("position_ms", -1L)
                if (p < 0L) return fail(action, "缺 position_ms")
                tc.seekTo(p)
                "跳到 ${mmss(p)}"
            }
        }
        ok(action, label)
    }

    /** 一个可以试的打开方式：uri + 想指定的播放器包名（null = 交给系统挑） */
    private class Way(val uri: Uri, val pkg: String?)

    /** 按可靠程度排：服务端给的 url 先；能从 url / mid 拼出直接播放的 scheme 就再加一个；最后才是搜索网页 */
    private fun ways(song: JSONObject?): List<Way> {
        val out = ArrayList<Way>()
        if (song == null) return out
        val url = song.optString("url", "").trim()
        val mid = song.optString("mid", "").trim()
        val title = song.optString("title", "").trim()
        val artist = song.optString("artist", "").trim()
        val pkgHint = song.optString("pkg", "").trim().let { when (it) { "qq", "qqmusic", NowPlaying.QQ -> NowPlaying.QQ; "netease", "163", NowPlaying.NETEASE -> NowPlaying.NETEASE; else -> null } }
        if (url.isNotBlank()) {
            val u = Uri.parse(url)
            val host = u.host ?: ""
            val pkg = when {
                url.startsWith("qqmusic://") -> NowPlaying.QQ
                url.startsWith("orpheus://") -> NowPlaying.NETEASE
                host.endsWith("qq.com") -> NowPlaying.QQ
                host.endsWith("163.com") -> NowPlaying.NETEASE
                else -> pkgHint
            }
            // 网页链接里能抠出 id 的，先试直接播放的 scheme
            if (url.startsWith("http")) {
                if (pkg == NowPlaying.QQ) {
                    val m = Regex("songDetail/([0-9A-Za-z]{10,})").find(url)?.groupValues?.get(1) ?: u.getQueryParameter("songmid")
                    if (!m.isNullOrBlank()) out += Way(qqPlay(m), NowPlaying.QQ)
                } else if (pkg == NowPlaying.NETEASE) {
                    val id = u.getQueryParameter("id") ?: Regex("/song/(\\d+)").find(url)?.groupValues?.get(1)
                    if (!id.isNullOrBlank()) out += Way(neteasePlay(id), NowPlaying.NETEASE)
                }
            }
            out += Way(u, pkg)
            if (pkg != null) out += Way(u, null)   // 指定的包不认这个链接（比如网页链接没做 App Link）就交给系统/浏览器
        }
        if (mid.isNotBlank()) {
            if (pkgHint == NowPlaying.NETEASE) out += Way(neteasePlay(mid), NowPlaying.NETEASE)
            else out += Way(qqPlay(mid), NowPlaying.QQ)   // mid 是 QQ 音乐的叫法（songmid）
        }
        if (out.isEmpty() && title.isNotBlank()) {
            val kw = Uri.encode(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "))
            val search = Uri.parse(SEARCH_URL + kw)
            out += Way(search, NowPlaying.QQ)   // QQ 音乐若把 y.qq.com 做了 App Link 就在 app 里开
            out += Way(search, null)            // 不然浏览器开搜索页，页上有「用 QQ 音乐打开」
        }
        return out
    }

    private fun qqPlay(mid: String): Uri {
        val key = if (mid.all { it.isDigit() }) "songid" else "songmid"
        val json = "{\"song\":[{\"$key\":\"$mid\",\"type\":\"0\"}],\"action\":\"play\",\"hideLoading\":\"1\"}"
        return Uri.Builder().scheme("qqmusic").authority("qq.com").path("/media/playSonglist").appendQueryParameter("p", json).build()
    }

    private fun neteasePlay(id: String): Uri = Uri.parse("orpheus://song/$id/?autoplay=1")

    private fun installed(ctx: Context, pkg: String): Boolean = try { ctx.packageManager.getApplicationInfo(pkg, 0); true } catch (_: Exception) { false }

    private fun intentFor(ctx: Context, w: Way): Intent? {
        if (w.pkg != null && !installed(ctx, w.pkg)) return null
        val i = Intent(Intent.ACTION_VIEW, w.uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (w.pkg != null) i.setPackage(w.pkg)
        // 有 QUERY_ALL_PACKAGES 权限，能看见别的包接不接这个链接；接不了的这一条跳过
        return if (ctx.packageManager.resolveActivity(i, 0) != null) i else null
    }

    private fun open(o: JSONObject) {
        val ctx = app ?: return fail("open", "还没初始化")
        val song = o.optJSONObject("song")
        val title = song?.optString("title", "")?.trim().orEmpty()
        val artist = song?.optString("artist", "")?.trim().orEmpty()
        val note = o.optString("note", "").trim()
        val candidates = ways(song).mapNotNull { intentFor(ctx, it) }
        if (candidates.isEmpty()) return fail("open", if (song == null) "没给 song" else "没有能打开的链接（url/mid/歌名都没有，或者播放器没装）")
        val intent = candidates.first()
        val who = when (intent.`package`) { NowPlaying.QQ -> "QQ音乐"; NowPlaying.NETEASE -> "网易云"; null -> "浏览器/系统"; else -> intent.`package` }
        val name = if (title.isBlank()) "一首歌" else "《$title》" + (if (artist.isBlank()) "" else " - $artist")
        // 通知总是发：安卓 10+ 后台起不了 Activity，她点通知就去放；也是一条「辰点了什么」的记录
        notify(ctx, intent, name, note)
        var launched = false
        if (AppState.visible) {
            for (i in candidates) {
                try { ctx.startActivity(i); launched = true; break } catch (_: Exception) {}
            }
        }
        ok("open", "点歌$name → " + (if (launched) "已拉起$who" else "已弹通知（点通知去${who}放）"))
    }

    private fun notify(ctx: Context, intent: Intent, name: String, note: String) {
        val id = NOTIF_BASE + (name.hashCode() and 0xfff)
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (note.isBlank()) "点一下就去放" else note
        val n = NotificationCompat.Builder(ctx, CH_MUSIC)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("辰点了$name")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { nm(ctx).notify(id, n) } catch (_: Exception) {}
    }

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel(ctx: Context) {
        try { nm(ctx).createNotificationChannel(NotificationChannel(CH_MUSIC, "辰点歌", NotificationManager.IMPORTANCE_HIGH)) } catch (_: Exception) {}
    }

    private fun ok(action: String, label: String) {
        lastCmd.value = "${hhmm()} $label ✓"
        MusicReporter.reportCmd(action, true, "")
    }

    private fun fail(action: String, why: String) {
        lastCmd.value = "${hhmm()} $action ✗ $why"
        MusicReporter.reportCmd(action, false, why)
    }

    private fun hhmm(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date())
    private fun mmss(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(Locale.US, s / 60, s % 60) }
}
