package me.chen.laidian.music

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * 0929 一起听：系统「正在播放」的一份快照。字段名和 POST /music 的 body 一一对应（toJson）。
 *  - positionMs 是 at 那一刻的进度（at = 采样时刻 epoch ms）；播放中要看"现在到哪了"用 positionAt(now) 外推
 *  - extras 只在第一次遇到一首歌时带（metadata 的全部键值，诊断用：看 QQ 音乐到底给不给 songId），之后为 null
 */
data class Snapshot(
    val pkg: String = "",
    val app: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    /** playing | paused | stopped | none（none = 根本没有会话） */
    val state: String = "none",
    val positionMs: Long = 0L,
    val at: Long = 0L,
    val speed: Float = 1f,
    val mediaId: String = "",
    val extras: Map<String, String>? = null,
) {
    /** 同一首歌的判据（歌变了 = 这个变了）。没会话时为空串 */
    val songKey: String get() = if (state == "none") "" else "$pkg|$mediaId|$title|$artist|$album|$durationMs"

    /** 调研文档的公式：现在进度 = position + (现在 − 记录时刻) × 倍速，只在播放中外推 */
    fun positionAt(now: Long): Long {
        if (state != "playing" || at <= 0L) return positionMs
        val p = positionMs + ((now - at) * speed).toLong()
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return p.coerceIn(0L, max)
    }

    /** 把进度外推到 now 再拍一张（心跳 / 手动发用），extras 不跟着走 */
    fun extrapolated(now: Long) = copy(positionMs = positionAt(now), at = now, extras = null)

    fun toJson(): JSONObject = JSONObject()
        .put("pkg", pkg).put("app", app)
        .put("title", title).put("artist", artist).put("album", album)
        .put("duration_ms", durationMs).put("state", state)
        .put("position_ms", positionMs).put("at", at)
        .put("speed", speed.toDouble()).put("media_id", mediaId)
}

/**
 * 从系统活跃的 MediaSession 里挑一个盯着（她 0929 电话里拍板：QQ 音乐为主、网易云也用，不换播放器）。
 * 挑法：先在"正在播放"的会话里按 QQ 音乐 > 网易云 > 其它 挑；一个都没在放，再在全部会话里按同样顺序挑。
 * （比"QQ 音乐永远优先"多一层：QQ 音乐暂停挂着、网易云在放，要报网易云那首。）
 * 选中的会话注册 MediaController.Callback，每次变化更新 snapshot 并交给 MusicReporter 判断要不要上报。
 * 只在主线程调用（MusicListenerService 的回调都在主线程；页面上的按钮也是）。
 */
object NowPlaying {
    const val QQ = "com.tencent.qqmusic"
    const val NETEASE = "com.netease.cloudmusic"

    /** 给页面显示的当前状态 */
    val snapshot = MutableStateFlow(Snapshot())
    /** 给页面看的一句话：连上没有 / 有几个会话 / 选了谁 / 出了什么错 */
    val status = MutableStateFlow("还没连上系统（通知使用权？）")
    /** 最近一首歌的诊断键值（页面折叠显示；她截图给辰看 QQ 音乐给了哪些键） */
    val lastExtras = MutableStateFlow<Map<String, String>>(emptyMap())

    private var app: Context? = null
    private var mgr: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var listening = false
    @Volatile private var controller: MediaController? = null
    private var meta: MediaMetadata? = null
    private var ps: PlaybackState? = null
    /** 已经带过 extras 的那首歌的 songKey */
    private var extrasSongKey = ""
    private val labels = HashMap<String, String>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list ?: emptyList()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            meta = metadata
            publish()
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            ps = state
            publish()
            // 这个会话停了/暂停了：看看是不是别的 app 接着在放（切 app 听歌不一定触发 onActiveSessionsChanged）
            if (state?.state != PlaybackState.STATE_PLAYING) refresh()
        }
        override fun onSessionDestroyed() {
            drop()
            refresh()
        }
    }

    /** MusicListenerService.onListenerConnected 里调：注册会话变化监听 + 立刻取一次 */
    fun attach(ctx: Context, comp: ComponentName) {
        app = ctx.applicationContext
        component = comp
        val m = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        mgr = m
        if (!listening) {
            try {
                m.addOnActiveSessionsChangedListener(sessionsListener, comp)
                listening = true
            } catch (e: SecurityException) {
                status.value = "系统不让读会话：没有通知使用权"
                return
            } catch (e: Exception) {
                status.value = "注册会话监听失败 ${e.javaClass.simpleName}"
                return
            }
        }
        refresh()
    }

    /** 服务断开 / 销毁时调 */
    fun detach() {
        val m = mgr
        if (listening && m != null) try { m.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        listening = false
        drop()
        publish()
        status.value = "服务已断开"
    }

    /** 重新取一次活跃会话并挑选（页面「刷新会话」也走这里） */
    fun refresh() {
        val m = mgr ?: run { status.value = "还没连上系统（通知使用权？）"; return }
        val comp = component ?: return
        val list = try { m.getActiveSessions(comp) } catch (e: SecurityException) {
            status.value = "系统不让读会话：没有通知使用权"; return
        } catch (e: Exception) {
            status.value = "读会话失败 ${e.javaClass.simpleName}"; return
        }
        pick(list ?: emptyList())
    }

    /** 当前快照（reporter 心跳 / 指令回执用） */
    fun current(): Snapshot = snapshot.value

    /** 当前会话的遥控器；没有会话返回 null */
    fun transport(): MediaController.TransportControls? = controller?.transportControls

    private fun prefer(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.packageName == QQ } ?: list.firstOrNull { it.packageName == NETEASE } ?: list.firstOrNull()

    private fun pick(list: List<MediaController>) {
        val playing = list.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val chosen = prefer(playing) ?: prefer(list)
        val names = list.joinToString("/") { label(it.packageName) }
        if (chosen == null) {
            drop()
            publish()
            status.value = "系统里没有正在播放的会话"
            return
        }
        val cur = controller
        if (cur != null && cur.sessionToken == chosen.sessionToken) {
            // 同一个会话：只刷新一下缓存的状态（有的 app 不主动回调）
            meta = cur.metadata; ps = cur.playbackState
            publish()
        } else {
            drop()
            controller = chosen
            try { chosen.registerCallback(callback) } catch (e: Exception) { status.value = "注册回调失败 ${e.javaClass.simpleName}"; controller = null; return }
            meta = chosen.metadata
            ps = chosen.playbackState
            publish()
        }
        status.value = "会话 ${list.size} 个（$names）· 盯着 ${label(chosen.packageName)}"
    }

    private fun drop() {
        val c = controller ?: return
        try { c.unregisterCallback(callback) } catch (_: Exception) {}
        controller = null
        meta = null
        ps = null
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        when (pkg) {
            QQ -> "QQ音乐"
            NETEASE -> "网易云音乐"
            else -> try {
                val pm = app?.packageManager
                if (pm != null) pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() else pkg.substringAfterLast('.')
            } catch (_: PackageManager.NameNotFoundException) { pkg.substringAfterLast('.') } catch (_: Exception) { pkg.substringAfterLast('.') }
        }
    }

    private fun stateName(s: Int?): String = when (s) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> "playing"   // 缓冲当播放：speed 多半是 0，外推自然不动
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, PlaybackState.STATE_ERROR, null -> "stopped"
        else -> "paused"   // 快进/快退/切歌中/连接中：过渡态，按暂停算，别让外推乱跑
    }

    /** 把 controller/meta/ps 拼成快照 → snapshot 流 → reporter */
    private fun publish() {
        val c = controller
        val s = if (c == null) Snapshot() else {
            val m = meta
            val p = ps
            val upd = p?.lastPositionUpdateTime ?: 0L   // elapsedRealtime 基准，换算成 epoch
            val at = if (upd > 0L) System.currentTimeMillis() - (SystemClock.elapsedRealtime() - upd) else System.currentTimeMillis()
            val base = Snapshot(
                pkg = c.packageName ?: "",
                app = label(c.packageName ?: ""),
                title = m?.text(MediaMetadata.METADATA_KEY_TITLE) ?: m?.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "",
                artist = m?.text(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: m?.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
                album = m?.text(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
                durationMs = (m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L),
                state = stateName(p?.state),
                positionMs = (p?.position ?: 0L).coerceAtLeast(0L),
                at = at,
                speed = p?.playbackSpeed ?: 1f,
                mediaId = m?.text(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: "",
            )
            val key = base.songKey
            if (key.isNotEmpty() && base.title.isNotBlank() && key != extrasSongKey) {
                extrasSongKey = key
                // 诊断键值倒不出来就算了，不能拖着整个 app 一起崩
                val ex = try { dump(m, try { c.extras } catch (_: Exception) { null }) } catch (_: Throwable) { emptyMap() }
                lastExtras.value = ex
                base.copy(extras = ex)
            } else base
        }
        snapshot.value = s
        MusicReporter.onSnapshot(s)
    }

    private fun MediaMetadata.text(key: String): String? = try { getText(key)?.toString()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }

    // ---------- 诊断：把 metadata 所有键值倒出来（字符串截前 80 字） ----------

    private val LONG_KEYS = setOf(
        MediaMetadata.METADATA_KEY_DURATION, MediaMetadata.METADATA_KEY_YEAR, MediaMetadata.METADATA_KEY_TRACK_NUMBER,
        MediaMetadata.METADATA_KEY_NUM_TRACKS, MediaMetadata.METADATA_KEY_DISC_NUMBER, MediaMetadata.METADATA_KEY_BT_FOLDER_TYPE,
        "android.media.metadata.ADVERTISEMENT", "android.media.metadata.DOWNLOAD_STATUS",
    )
    private val BITMAP_KEYS = setOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    private val RATING_KEYS = setOf(MediaMetadata.METADATA_KEY_RATING, MediaMetadata.METADATA_KEY_USER_RATING)

    private fun dump(m: MediaMetadata?, sessionExtras: Bundle?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        // 1005 闪退：QQ 音乐切歌/停的时候 metadata 里会混进一个 null 键，out[k] 的非空检查当场抛 NPE（0.121 反编译钉死在这行）
        if (m != null) for (k: String? in m.keySet()) {
            if (k == null) continue
            val v: String = try {
                when (k) {
                    in BITMAP_KEYS -> "<bitmap>"
                    in LONG_KEYS -> m.getLong(k).toString()
                    in RATING_KEYS -> m.getRating(k)?.toString() ?: "null"
                    else -> m.getText(k)?.toString() ?: m.getLong(k).let { if (it != 0L) it.toString() else "<null>" }
                }
            } catch (e: Exception) { "<${e.javaClass.simpleName}>" }
            out[k] = v.take(80)
        }
        // 会话级 extras（有的 app 把歌曲 id 放这里）
        if (sessionExtras != null) for (k: String? in sessionExtras.keySet()) {
            if (k == null) continue
            @Suppress("DEPRECATION")
            val v = try { sessionExtras.get(k)?.toString() } catch (_: Exception) { null } ?: "null"
            out["session.$k"] = v.take(80)
        }
        return out
    }
}
