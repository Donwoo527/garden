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

        fun from(o: JSONObject): Msg {
            var type = if (o.has("msg_type")) o.optString("msg_type", "text") else o.optString("type", "text")
            if (type == "msg" || type.isBlank()) type = "text"
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
