package me.chen.laidian.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.128 我们的清单一条：chat_server.py data/plans.json 的一项
 * {id, text, note, tab, stars, pinned, who, created, done, done_at, done_by}；who / done_by 是 "chen" 或 "xiaochen"
 * 0.129 她手画的 v2 加了：note（标题下那行小字 = 便签）、tab（我们共同 / 小陈个人 / 辰想做）、stars 0-3（只管排序）、pinned（置顶）
 * 老服务端 / 老数据没这几个字段时按默认读：tab=common note="" stars=0 pinned=false
 */
data class Plan(
    val id: String,
    val text: String,
    val who: String,
    val created: Double,
    val done: Boolean,
    val doneAt: Double,
    val doneBy: String,
    val note: String = "",
    val tab: String = TAB_COMMON,
    val stars: Int = 0,
    val pinned: Boolean = false,
) {
    val isChen get() = who == "chen"

    /** 0.129 她："完成后三天没动静就自动沉到该栏最底"——以打勾时间算 */
    fun sunk(nowSec: Double): Boolean = done && doneAt > 0 && nowSec - doneAt > SINK_SEC

    companion object {
        const val TAB_COMMON = "common"
        const val TAB_XIAOCHEN = "xiaochen"
        const val TAB_CHEN = "chen"
        /** 「已完成」只是 app 里的一个视图，服务端没有这个 tab 值 */
        const val TAB_DONE = "done"
        /** 可以放东西的三栏（顺序 = 标签顺序），她画的：我们共同 / 小陈个人 / 辰想做 */
        val TABS = listOf(TAB_COMMON to "我们共同", TAB_XIAOCHEN to "小陈个人", TAB_CHEN to "辰想做")
        fun tabName(k: String) = TABS.firstOrNull { it.first == k }?.second ?: "我们共同"
        const val SINK_SEC = 3 * 86400.0

        fun from(o: JSONObject): Plan = Plan(
            id = o.optString("id"),
            text = o.optString("text", ""),
            who = o.optString("who", "xiaochen"),
            created = o.optDouble("created", 0.0),
            done = o.optBoolean("done", false),
            // done_at / done_by 没勾时是 null：optDouble 拿到 NaN、optString 拿到 "null"，这里都折成默认值
            doneAt = if (o.isNull("done_at")) 0.0 else o.optDouble("done_at", 0.0),
            doneBy = if (o.isNull("done_by")) "" else o.optString("done_by", ""),
            note = if (o.isNull("note")) "" else o.optString("note", ""),
            tab = o.optString("tab", TAB_COMMON).takeIf { t -> TABS.any { it.first == t } } ?: TAB_COMMON,
            stars = o.optInt("stars", 0).coerceIn(0, 3),
            pinned = o.optBoolean("pinned", false),
        )
        fun list(a: JSONArray?): List<Plan> = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let(::from) } } ?: emptyList()
    }
}
