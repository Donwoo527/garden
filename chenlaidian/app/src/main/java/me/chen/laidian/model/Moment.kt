package me.chen.laidian.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MomentComment(val who: String, val text: String, val ts: Double)

/** 朋友圈一条：chat_server.py moments.jsonl 一行 */
data class Moment(
    val id: String,
    val who: String,
    val text: String,
    val images: List<String>,
    val ts: Double,
    val likes: List<String>,
    val comments: List<MomentComment>,
) {
    val isChen get() = who == "chen"
    fun timeLabel(): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date((ts * 1000).toLong()))

    companion object {
        fun from(o: JSONObject): Moment {
            val imgs = o.optJSONArray("images")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() } } } ?: emptyList()
            val likes = o.optJSONArray("likes")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } } ?: emptyList()
            val comments = o.optJSONArray("comments")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { c -> MomentComment(c.optString("who"), c.optString("text"), c.optDouble("ts", 0.0)) } }
            } ?: emptyList()
            return Moment(o.optString("id"), o.optString("who", "chen"), o.optString("text", ""), imgs, o.optDouble("ts", 0.0), likes, comments)
        }
        fun list(a: JSONArray?): List<Moment> = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let(::from) } } ?: emptyList()
    }
}
