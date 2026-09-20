package me.chen.laidian.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 聊天消息：对应 chat_server.py 的 history.jsonl 一行 / ws "msg" 事件 */
data class Msg(
    val id: String,
    val who: String,          // "chen" | "xiaochen"
    val msgType: String,      // text | image | voice
    val text: String,
    val media: String?,       // /media/xxx.jpg
    val voice: String?,       // /media/xxx.mp3
    val replyTo: String?,
    val thinking: String?,
    val ts: Double,
    val images: List<String> = emptyList(),
    val filename: String? = null,
    val duration: Double? = null,   // 0915 语音时长（秒），服务端 ffprobe 算的；老消息没有
    val quoteWho: String? = null,   // 0920 服务端带的引用快照 quote{id,who,text}：reply_to 那条不在本地列表时靠它画引用框；老消息没有
    val quoteText: String? = null,
    val reactions: Map<String, List<String>> = emptyMap(),   // 0920 她：气泡表态 emoji→点过的人(chen/xiaochen)；老消息没有
) {
    val isChen get() = who == "chen"

    fun timeLabel(): String {
        val d = Date((ts * 1000).toLong())
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { time = d }
        val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
        val fmt = if (sameDay) "HH:mm" else "MM-dd HH:mm"
        return SimpleDateFormat(fmt, Locale.CHINA).format(d)
    }

    companion object {
        private fun JSONObject.str(key: String): String? =
            optString(key, "").takeIf { it.isNotBlank() && it != "null" }

        /** 0920 表态 {"❤": ["chen"], "👍": ["xiaochen","chen"]} → Map；没有/不是对象 → 空；没人点的 emoji 丢掉 */
        fun parseReactions(o: JSONObject?): Map<String, List<String>> {
            if (o == null) return emptyMap()
            return o.keys().asSequence()
                .associateWith { k -> o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() } } } ?: emptyList() }
                .filterValues { it.isNotEmpty() }
        }

        fun from(o: JSONObject): Msg {
            var type = if (o.has("msg_type")) o.optString("msg_type", "text") else o.optString("type", "text")
            if (type == "msg" || type.isBlank()) type = "text"
            val quote = o.optJSONObject("quote")   // 0920 引用快照；老消息没有这个字段
            return Msg(
                id = o.optString("id", ""),
                who = o.optString("who", "chen"),
                msgType = type,
                text = o.optString("text", ""),
                media = o.str("media"),
                voice = o.str("voice"),
                replyTo = o.str("reply_to"),
                thinking = o.str("thinking"),
                ts = o.optDouble("ts", 0.0),
                images = o.optJSONArray("images")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() } } } ?: emptyList(),
                filename = o.str("filename"),
                duration = if (o.has("duration") && !o.isNull("duration")) o.optDouble("duration").takeIf { it > 0 } else null,
                quoteWho = quote?.str("who"),
                quoteText = quote?.optString("text", ""),
                reactions = parseReactions(o.optJSONObject("reactions")),
            )
        }

        fun list(a: JSONArray?): List<Msg> {
            if (a == null) return emptyList()
            val out = ArrayList<Msg>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out += from(o)
            }
            return out
        }
    }
}
