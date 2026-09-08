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

    private fun postJson(ctx: Context, path: String, o: JSONObject): Boolean {
        val req = Request.Builder().url(ChatClient.baseUrl() + path).header("X-Token", TOKEN)
            .post(o.toString().toRequestBody("application/json".toMediaType())).build()
        return try { http(ctx).newCall(req).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }
}
