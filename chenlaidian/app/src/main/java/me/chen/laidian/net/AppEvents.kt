package me.chen.laidian.net

import kotlinx.coroutines.flow.MutableSharedFlow
import me.chen.laidian.model.Msg
import org.json.JSONObject

/**
 * 0929 一起读 / 一起听：服务端经聊天 ws 下发的指令在这里分发，页面和服务各自订阅。
 * ChatClient.handle() 里 "music_cmd" / "reading_anchor" 两种 type 直接整个 JSON 丢进来。
 *  - musicCmd: {"type":"music_cmd","action":"next|prev|pause|play|seek|open",...}
 *  - readingAnchor: {"type":"reading_anchor","text":"一句原文","note":"辰的话",...}
 *  - chatMsg（0.124）：ws "msg" 里第一次见到 id 的新消息（双方的都有，订阅方自己挑）；history 分页、重连拉回来的不走这条
 */
object AppEvents {
    val musicCmd = MutableSharedFlow<JSONObject>(replay = 0, extraBufferCapacity = 16)
    val readingAnchor = MutableSharedFlow<JSONObject>(replay = 0, extraBufferCapacity = 16)
    /** 0.124 阅读页顶部消息弹窗订阅：看书时看不到聊天页，辰实时说的话从这里弹出来 */
    val chatMsg = MutableSharedFlow<Msg>(replay = 0, extraBufferCapacity = 32)
}
