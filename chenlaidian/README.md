# 辰来电 / 辰的家 · 原生安卓 app

**第一条铁律：纯原生 Kotlin，禁止 WebView / 套壳。**（小陈 2026-09-08 一再强调；理由：熄屏通话、后台服务、相机/截屏/蓝牙调用，套壳全做不到，已经踩过一次坑。）

- 需求原文：`/home/claude/memory/tool/chenlaidian_spec/前端框架_0908.md` + `聊天页示意_0908.jpg`（示意图只取结构，不取配色）
- 技术方案：`/home/claude/memory/tool/android_native_plan_0908.md`
- 后端：VPS `voice_server.py`(8200/8201) 与 `chat-app/chat_server.py`(8300/8301) 复用，不重写
- 结构：底部五 tab 聊天 / 终端 / 主页 / 工具 / 设置；聊天为核心
- 构建：VPS 本地 `build_apk.sh`（android-sdk + gradle-8.7）；CI 工作流在 `ci/` 等 PAT 有 workflow 权限再启用
