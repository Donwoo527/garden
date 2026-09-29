package me.chen.laidian.net

import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject

/**
 * 0929 一起读 / 一起听：服务端经聊天 ws 下发的指令在这里分发，页面和服务各自订阅。
 * ChatClient.handle() 里 "music_cmd" / "reading_anchor" 两种 type 直接整个 JSON 丢进来。
 *  - musicCmd: {"type":"music_cmd","action":"next|prev|pause|play|seek|open",...}
 *  - readingAnchor: {"type":"reading_anchor","text":"一句原文","note":"辰的话",...}
 */
object AppEvents {
    val musicCmd = MutableSharedFlow<JSONObject>(replay = 0, extraBufferCapacity = 16)
    val readingAnchor = MutableSharedFlow<JSONObject>(replay = 0, extraBufferCapacity = 16)
}
