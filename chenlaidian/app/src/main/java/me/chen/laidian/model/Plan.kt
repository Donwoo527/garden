package me.chen.laidian.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.128 我们的清单一条：chat_server.py data/plans.json 的一项
 * {id, text, who, created, done, done_at, done_by}；who / done_by 是 "chen" 或 "xiaochen"
 */
data class Plan(
    val id: String,
    val text: String,
    val who: String,
    val created: Double,
    val done: Boolean,
    val doneAt: Double,
    val doneBy: String,
) {
    val isChen get() = who == "chen"

    companion object {
        fun from(o: JSONObject): Plan = Plan(
            id = o.optString("id"),
            text = o.optString("text", ""),
            who = o.optString("who", "xiaochen"),
            created = o.optDouble("created", 0.0),
            done = o.optBoolean("done", false),
            // done_at / done_by 没勾时是 null：optDouble 拿到 NaN、optString 拿到 "null"，这里都折成默认值
            doneAt = if (o.isNull("done_at")) 0.0 else o.optDouble("done_at", 0.0),
            doneBy = if (o.isNull("done_by")) "" else o.optString("done_by", ""),
        )
        fun list(a: JSONArray?): List<Plan> = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let(::from) } } ?: emptyList()
    }
}
