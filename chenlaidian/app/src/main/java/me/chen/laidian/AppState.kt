package me.chen.laidian

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** app 是否在前台（前台就不弹消息通知）。 */
object AppState {
    @Volatile var visible = false
    /** 上次启动崩过 → 这次用旧版聊天页兜底（安全模式） */
    @Volatile var safeMode = false

    /** 0926 她报的 bug：输入框打一半切到别的 tab 再回来字没了——聊天页离开组合时 remember 的草稿跟着丢。
     *  草稿的真身提到这里（JetUserInput 读写它），每次变化顺手写 SharedPreferences，冷启动 loadDraft 读回来 → 退出重进也在；发送成功才清 */
    val chatDraft = mutableStateOf("")
    private const val DRAFT_PREF = "chat_draft"

    fun loadDraft(ctx: Context) {
        chatDraft.value = try { ctx.getSharedPreferences(DRAFT_PREF, Context.MODE_PRIVATE).getString("chat_draft", "") ?: "" } catch (_: Exception) { "" }
    }

    fun saveDraft(ctx: Context, text: String) {
        chatDraft.value = text
        try { ctx.getSharedPreferences(DRAFT_PREF, Context.MODE_PRIVATE).edit().putString("chat_draft", text).apply() } catch (_: Exception) {}
    }
}
