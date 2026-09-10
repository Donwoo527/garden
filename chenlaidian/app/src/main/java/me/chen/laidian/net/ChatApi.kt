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

    /** 上传一张图（silent：只存文件不单独进聊天），返回 /media/xxx.jpg */
    fun uploadImage(ctx: Context, jpeg: ByteArray): String? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val req = Request.Builder().url(ChatClient.baseUrl() + "/upload?silent=1")
            .header("X-Token", TOKEN).post(body).build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                JSONObject(r.body?.string() ?: return null).optString("url", "").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) { null }
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

    fun commentMoment(ctx: Context, id: String, text: String): Boolean =
        postJson(ctx, "/moments/react", JSONObject().put("id", id).put("comment", JSONObject().put("who", "xiaochen").put("text", text)))

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
    fun addSticker(ctx: Context, url: String): Boolean = postJson(ctx, "/stickers", JSONObject().put("url", url))

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

    private fun postJson(ctx: Context, path: String, o: JSONObject): Boolean {
        val req = Request.Builder().url(ChatClient.baseUrl() + path).header("X-Token", TOKEN)
            .post(o.toString().toRequestBody("application/json".toMediaType())).build()
        return try { http(ctx).newCall(req).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }
}
