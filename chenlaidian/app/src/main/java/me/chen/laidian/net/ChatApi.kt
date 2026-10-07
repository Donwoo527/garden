package me.chen.laidian.net

import android.content.Context
import me.chen.laidian.Tls
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** chat_server.py 8300 的 HTTP 接口（X-Token 认证），和网页版调用方式一致。 */
object ChatApi {
    private const val TOKEN = "chen_home_2026"
    @Volatile private var client: OkHttpClient? = null
    private fun http(ctx: Context): OkHttpClient =
        client ?: Tls.client(ctx.applicationContext).also { client = it }

    /** 0915 最近一次失败的原因（页面 Toast 用）：0914 她报"图片发送失败"只有四个字 查不下去 */
    @Volatile var lastError: String? = null

    /** 上传一张图（silent：只存文件不单独进聊天），返回 /media/xxx.jpg */
    fun uploadImage(ctx: Context, jpeg: ByteArray): String? = uploadBytes(ctx, jpeg, "photo.jpg", "image/jpeg")

    /** 0915 通用上传：silent=true 只存文件返回地址；false 服务端直接当她发的一条（图片/文件）入库并广播 */
    fun uploadBytes(ctx: Context, bytes: ByteArray, filename: String, mime: String, silent: Boolean = true): String? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, bytes.toRequestBody(mime.toMediaType()))
            .build()
        val req = Request.Builder().url(ChatClient.baseUrl() + "/upload" + (if (silent) "?silent=1" else ""))
            .header("X-Token", TOKEN).post(body).build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) { lastError = "上传被拒 HTTP ${r.code}"; return null }
                val url = JSONObject(r.body?.string() ?: "{}").optString("url", "").takeIf { it.isNotBlank() }
                if (url == null) lastError = "上传返回里没有地址"
                url
            }
        } catch (e: Exception) { lastError = "上传 ${e.javaClass.simpleName}${e.message?.let { ": " + it.take(60) } ?: ""}"; null }
    }

    /** 0926 她的语音消息：m4a 传给 /upload_voice（一步产生消息：服务端落盘+入库+广播，再后台转文字给辰），返回 /media/voice_in_xxx.m4a。
     *  duration 是 app 量的秒数，服务端 ffprobe 读得出就用自己的 */
    fun uploadVoice(ctx: Context, file: java.io.File, durationSec: Double): String? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.readBytes().toRequestBody("audio/mp4".toMediaType()))
            .build()
        val req = Request.Builder().url(ChatClient.baseUrl() + "/upload_voice?duration=" + "%.1f".format(java.util.Locale.US, durationSec))
            .header("X-Token", TOKEN).post(body).build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) { lastError = "语音上传被拒 HTTP ${r.code}"; return null }
                val url = JSONObject(r.body?.string() ?: "{}").optString("url", "").takeIf { it.isNotBlank() }
                if (url == null) lastError = "语音上传返回里没有地址"
                url
            }
        } catch (e: Exception) { lastError = "语音上传 ${e.javaClass.simpleName}${e.message?.let { ": " + it.take(60) } ?: ""}"; null }
    }

    /** 多张图 + 可选配文合成一条消息 */
    fun sendImages(ctx: Context, urls: List<String>, text: String): Boolean = postJson(
        ctx, "/send_images", JSONObject().put("images", JSONArray(urls)).put("text", text)
    )

    /** 她的心情 / 签名 */
    fun setProfile(ctx: Context, mood: String, signature: String): Boolean = postJson(
        ctx, "/user_profile", JSONObject().put("mood", mood).put("signature", signature)
    )

    /** 拉朋友圈（新的在前） */
    fun loadMoments(ctx: Context): List<me.chen.laidian.model.Moment>? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/moments").header("X-Token", TOKEN).get().build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                me.chen.laidian.model.Moment.list(JSONObject(r.body?.string() ?: return null).optJSONArray("items"))
            }
        } catch (e: Exception) { null }
    }

    fun postMoment(ctx: Context, text: String, urls: List<String>): Boolean =
        postJson(ctx, "/moments", JSONObject().put("who", "xiaochen").put("text", text).put("images", JSONArray(urls)))

    fun likeMoment(ctx: Context, id: String): Boolean =
        postJson(ctx, "/moments/react", JSONObject().put("id", id).put("like", "xiaochen"))

    /** 0.122 replyTo/replyText：点着某条评论回的，带上回的是谁、那条说了啥（服务端拿来提醒辰） */
    fun commentMoment(ctx: Context, id: String, text: String, replyTo: String = "", replyText: String = ""): Boolean {
        val c = JSONObject().put("who", "xiaochen").put("text", text)
        if (replyTo.isNotBlank()) c.put("reply_to", replyTo).put("reply_text", replyText.take(40))
        return postJson(ctx, "/moments/react", JSONObject().put("id", id).put("comment", c))
    }

    fun markMomentsRead(ctx: Context): Boolean = postJson(ctx, "/moments/read", JSONObject())

    /** 0.38 收藏表情列表: [url] 新的在前 */
    fun stickers(ctx: Context): List<String>? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/stickers").header("X-Token", TOKEN).get().build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val arr = JSONObject(r.body?.string() ?: return null).optJSONArray("items") ?: return emptyList()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("url")?.takeIf { u -> u.isNotBlank() } }.reversed()
            }
        } catch (e: Exception) { null }
    }

    /** 0.38 添加收藏表情 */
    fun addSticker(ctx: Context, url: String): Boolean = postJson(ctx, "/stickers", JSONObject().put("url", url).put("who", "xiaochen"))

    /** 0915 存错了能删 */
    fun deleteSticker(ctx: Context, url: String): Boolean = postJson(ctx, "/stickers/delete", JSONObject().put("url", url))

    /** 收藏一条消息（快照） */
    fun addFavorite(ctx: Context, m: me.chen.laidian.model.Msg): Boolean {
        val snap = JSONObject().put("id", m.id).put("who", m.who).put("type", m.msgType).put("text", m.text).put("ts", m.ts)
        if (m.media != null) snap.put("media", m.media)
        if (m.voice != null) snap.put("voice", m.voice)
        if (m.images.isNotEmpty()) snap.put("images", JSONArray(m.images))
        return postJson(ctx, "/favorites", JSONObject().put("msg", snap))
    }

    /** 收藏列表：[{id, ts, msg}] 新的在前 */
    fun favorites(ctx: Context): List<Pair<String, me.chen.laidian.model.Msg>>? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/favorites").header("X-Token", TOKEN).get().build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val a = JSONObject(r.body?.string() ?: return null).optJSONArray("items") ?: return emptyList()
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it -> it.optJSONObject("msg")?.let { m -> it.optString("id") to me.chen.laidian.model.Msg.from(m) } } }
            }
        } catch (e: Exception) { null }
    }

    fun deleteFavorite(ctx: Context, id: String): Boolean = postJson(ctx, "/favorites/delete", JSONObject().put("id", id))

    /** 崩溃日志：塞进收藏（后端唯一能存长文本的公开接口），辰在 VPS 上读 */
    fun reportCrash(ctx: Context, text: String): Boolean {
        val snap = JSONObject().put("id", "crash-" + System.currentTimeMillis()).put("who", "xiaochen").put("type", "text")
            .put("text", "[crash]\n" + text).put("ts", System.currentTimeMillis() / 1000.0)
        return postJson(ctx, "/favorites", JSONObject().put("msg", snap))
    }

    /** 天气（服务端代理 wttr.in）：temp/feels/humidity/desc/wind/maxTemp/minTemp */
    fun weather(ctx: Context, city: String = "Ningbo"): JSONObject? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/api/weather?city=" + city).get().build()
        return try { http(ctx).newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string() ?: return null) else null } } catch (e: Exception) { null }
    }

    // ── 0.41 终端页 session 管理（她点单：查看/换引擎/重启）──────────────

    /** 当前引擎（服务端读哨兵state） */
    fun termModel(ctx: Context): String? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/term/model").header("X-Token", TOKEN).get().build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                JSONObject(r.body?.string() ?: return null).optString("model", "").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) { null }
    }

    /** 往辰的输入行发 /model 切换（服务端白名单校验） */
    fun switchModel(ctx: Context, model: String): Boolean =
        postJson(ctx, "/term/model", JSONObject().put("model", model))

    /** 重启辰的session（kill+哨兵45秒拉新；调用前app已二次确认） */
    fun restartSession(ctx: Context): Boolean = postJson(ctx, "/term/restart", JSONObject())

    /** 0.43 压缩上下文：往辰的输入行发 /compact */
    fun compactContext(ctx: Context): Boolean = postJson(ctx, "/term/cmd", JSONObject().put("cmd", "compact"))

    /** 0.43 调思考强度：往辰的输入行发 /effort <level> */
    fun setEffort(ctx: Context, level: String): Boolean =
        postJson(ctx, "/term/cmd", JSONObject().put("cmd", "effort").put("level", level))

    /** 0.47 心情/签名历史：[{ts, who, mood, signature}] 新的在前。服务端按 who 过滤，两个人各拉一次再合 */
    fun profileHistory(ctx: Context, who: String): List<JSONObject>? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/profile_history?who=" + who).header("X-Token", TOKEN).get().build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val a = JSONObject(r.body?.string() ?: return null).optJSONArray("items") ?: return emptyList()
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            }
        } catch (e: Exception) { null }
    }

    /** 0915 翻译（她点的：辰的思考链常是英文 看着累）：服务端走 MiniMax 文本模型 */
    fun translate(ctx: Context, text: String): String? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/translate").header("X-Token", TOKEN)
            .post(JSONObject().put("text", text).toString().toRequestBody("application/json".toMediaType())).build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                val body = JSONObject(r.body?.string() ?: "{}")
                if (!r.isSuccessful) { lastError = "翻译 HTTP ${r.code} ${body.optString("error").take(80)}"; return null }
                body.optString("translation", "").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) { lastError = "翻译 ${e.javaClass.simpleName}"; null }
    }

    /** 0915 Office 文件在 app 里看：服务端 LibreOffice 转 pdf，返回 /media/converted/xxx.pdf */
    fun convertToPdf(ctx: Context, url: String): String? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/convert?url=" + java.net.URLEncoder.encode(url, "UTF-8")).header("X-Token", TOKEN).get().build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                val body = JSONObject(r.body?.string() ?: "{}")
                if (!r.isSuccessful) { lastError = "转 pdf HTTP ${r.code} ${body.optString("error").take(80)}"; return null }
                // tiff 之类服务端转成 png 时返回 image；两种都直接给出地址 调用方按后缀分
                body.optString("pdf", "").takeIf { it.isNotBlank() } ?: body.optString("image", "").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) { lastError = "转 pdf ${e.javaClass.simpleName}"; null }
    }

    /** 0915 docx 在 app 里看：服务端 python-docx 抽文字 */
    fun docText(ctx: Context, url: String): String? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/doc_text?url=" + java.net.URLEncoder.encode(url, "UTF-8")).header("X-Token", TOKEN).get().build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                val body = JSONObject(r.body?.string() ?: "{}")
                if (!r.isSuccessful) { lastError = "抽文字 HTTP ${r.code} ${body.optString("error").take(80)}"; return null }
                body.optString("text", "")
            }
        } catch (e: Exception) { lastError = "抽文字 ${e.javaClass.simpleName}"; null }
    }

    // ── 0.128 我们的清单（chat_server.py /plans）──────────────

    /** GET /plans 全量；成功顺手写进 ChatClient.plans（页面看的是那份），失败 null（lastError 记原因）。
     *  0.129 回包里带 unread（辰改过、她还没进清单页看）→ ChatClient.plansUnread（百宝箱小红点） */
    fun plans(ctx: Context): List<me.chen.laidian.model.Plan>? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/plans").header("X-Token", TOKEN).get().build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) { lastError = "清单 HTTP ${r.code}"; return null }
                val o = JSONObject(r.body?.string() ?: return null)
                if (o.has("unread")) ChatClient.plansUnread.value = o.optBoolean("unread", false)
                me.chen.laidian.model.Plan.list(o.optJSONArray("items")).also { ChatClient.plans.value = it }
            }
        } catch (e: Exception) { lastError = "清单 ${e.javaClass.simpleName}"; null }
    }

    /** POST /plans：
     *  {op:"add",text[,tab][,note]} / {op:"toggle",id,done} / {op:"edit",id[,text][,note][,tab]}（带哪个改哪个）/
     *  {op:"star",id,stars 0-3} / {op:"pin",id,pinned} / {op:"delete",id}（0.129 加了 tab/note/star/pin）。
     *  成功返回服务端改完的全量（也写进 ChatClient.plans，不用等 ws 广播），失败 null */
    fun planOp(ctx: Context, body: JSONObject): List<me.chen.laidian.model.Plan>? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/plans").header("X-Token", TOKEN)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                val txt = r.body?.string() ?: ""
                // 服务端没这个接口（还没重启）时 404 回的是纯文本，不硬解 JSON
                if (!r.isSuccessful) { lastError = "清单 HTTP ${r.code} ${runCatching { JSONObject(txt).optString("error") }.getOrDefault("")}".trim(); return null }
                me.chen.laidian.model.Plan.list(JSONObject(txt).optJSONArray("items")).also { ChatClient.plans.value = it }
            }
        } catch (e: Exception) { lastError = "清单 ${e.javaClass.simpleName}"; null }
    }

    // 0.129 几个常用 op 的请求体（页面直接用，省得每处手拼 JSON 拼错字段名）
    fun planAdd(ctx: Context, text: String, tab: String, note: String = "") =
        planOp(ctx, JSONObject().put("op", "add").put("text", text).put("tab", tab).apply { if (note.isNotEmpty()) put("note", note) })
    fun planToggle(ctx: Context, id: String, done: Boolean) = planOp(ctx, JSONObject().put("op", "toggle").put("id", id).put("done", done))
    /** 只带非 null 的那几个字段：只改标题 / 只改备注 / 只挪栏目都行 */
    fun planEdit(ctx: Context, id: String, text: String? = null, note: String? = null, tab: String? = null) =
        planOp(ctx, JSONObject().put("op", "edit").put("id", id).apply {
            text?.let { put("text", it) }; note?.let { put("note", it) }; tab?.let { put("tab", it) }
        })
    fun planStar(ctx: Context, id: String, stars: Int) = planOp(ctx, JSONObject().put("op", "star").put("id", id).put("stars", stars))
    fun planPin(ctx: Context, id: String, pinned: Boolean) = planOp(ctx, JSONObject().put("op", "pin").put("id", id).put("pinned", pinned))
    fun planDelete(ctx: Context, id: String) = planOp(ctx, JSONObject().put("op", "delete").put("id", id))

    /** 0.129 她进了清单页：POST /plans/seen → 服务端记下、广播 plans_unread=false，小红点灭（失败就等下次进页再清） */
    fun plansSeen(ctx: Context): Boolean {
        val ok = postJson(ctx, "/plans/seen", JSONObject())
        if (ok) ChatClient.plansUnread.value = false
        return ok
    }

    private fun postJson(ctx: Context, path: String, o: JSONObject): Boolean {
        val req = Request.Builder().url(ChatClient.baseUrl() + path).header("X-Token", TOKEN)
            .post(o.toString().toRequestBody("application/json".toMediaType())).build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) lastError = "$path 被拒 HTTP ${r.code}"
                r.isSuccessful
            }
        } catch (e: Exception) { lastError = "$path ${e.javaClass.simpleName}"; false }
    }
}
