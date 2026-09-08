package me.chen.laidian

/** app 是否在前台（前台就不弹消息通知）。 */
object AppState {
    @Volatile var visible = false
}
