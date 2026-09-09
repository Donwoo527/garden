package me.chen.laidian

/** app 是否在前台（前台就不弹消息通知）。 */
object AppState {
    @Volatile var visible = false
    /** 上次启动崩过 → 这次用旧版聊天页兜底（安全模式） */
    @Volatile var safeMode = false
}
