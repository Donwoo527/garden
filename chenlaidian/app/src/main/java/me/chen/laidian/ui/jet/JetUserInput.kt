package me.chen.laidian.ui.jet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chen.laidian.R
import me.chen.laidian.ui.Neu
import me.chen.laidian.ui.neuPressable
import me.chen.laidian.ui.neuRaised
import me.chen.laidian.ui.neuSunken

enum class InputSelector { NONE, EMOJI, PLUS }

/** 输入栏（照 Jetchat）：文本框 + 表情/图片/电话 三个选择器 + 发送。表情面板内置。 */
@Composable
fun JetUserInput(
    onMessageSent: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    onPickImages: () -> Unit,
    onCall: () -> Unit,
    resetScroll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selector by rememberSaveable { mutableStateOf(InputSelector.NONE) }
    val dismiss = { selector = InputSelector.NONE }
    if (selector != InputSelector.NONE) BackHandler(onBack = dismiss)
    var textState by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var focused by remember { mutableStateOf(false) }
    val send = {
        val t = textState.text.trim()
        if (t.isNotEmpty()) { onMessageSent(t); textState = TextFieldValue(); onTyping(false); resetScroll() }
    }

    // 0.27 新拟物demo（她0910定的方向）：同色底 凸钮凹槽 按压凸变凹
    Surface(color = Neu.Bg, contentColor = Neu.Ink) {
        Column(modifier) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom) {
                // ➕：面板开着=保持凹陷 否则凸起+按压凹
                val plusOpen = selector == InputSelector.PLUS
                Box(
                    Modifier.size(44.dp)
                        .then(if (plusOpen) Modifier.neuSunken(22.dp) else Modifier.neuRaised(22.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            selector = if (plusOpen) InputSelector.NONE else InputSelector.PLUS
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Add, contentDescription = "更多", tint = Neu.Ink) }
                Spacer(Modifier.size(10.dp))
                // 输入凹槽（她的规范：等你放东西进去的=凹）
                Box(Modifier.weight(1f).neuSunken(24.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.weight(1f).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
                            BasicTextField(
                                value = textState,
                                onValueChange = { textState = it; onTyping(it.text.isNotEmpty()) },
                                modifier = Modifier.fillMaxWidth()
                                    .onFocusChanged { st -> if (st.isFocused) { selector = InputSelector.NONE; resetScroll() }; focused = st.isFocused },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions { send() },
                                maxLines = 4,
                                cursorBrush = SolidColor(Neu.Ink),
                                textStyle = LocalTextStyle.current.copy(color = Neu.Ink),
                            )
                            if (textState.text.isEmpty() && !focused) {
                                Text("说点什么…", style = MaterialTheme.typography.bodyLarge.copy(color = Neu.Dark))
                            }
                        }
                        val emojiOpen = selector == InputSelector.EMOJI
                        Box(
                            Modifier.padding(end = 6.dp, bottom = 4.dp).size(36.dp)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    selector = if (emojiOpen) InputSelector.NONE else InputSelector.EMOJI
                                },
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(R.drawable.ic_mood), contentDescription = "表情", tint = if (emojiOpen) Neu.Ink else Neu.Dark) }
                    }
                }
                Spacer(Modifier.size(10.dp))
                // 发送：常凸 按压凹 enabled用图标深浅表达
                val enabled = textState.text.isNotBlank()
                Box(
                    Modifier.size(46.dp).neuPressable(23.dp) { if (enabled) { send(); dismiss() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "发送",
                        tint = if (enabled) Neu.Ink else Neu.Dark.copy(alpha = 0.5f))
                }
            }
            if (selector == InputSelector.EMOJI) {
                Surface(tonalElevation = 8.dp) {
                    EmojiTable(onTextAdded = { e ->
                        val t = textState.text.replaceRange(textState.selection.start, textState.selection.end, e)
                        textState = TextFieldValue(t, TextRange(t.length))
                        onTyping(true)
                    }, modifier = Modifier.padding(8.dp).focusable())
                }
            }
            if (selector == InputSelector.PLUS) {
                Surface(tonalElevation = 8.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        PlusPanelItem(icon = painterResource(R.drawable.ic_insert_photo), label = "相册", onClick = { dismiss(); onPickImages() })
                        // 以后：拍照 / 文件 / 位置 都往这个面板里加
                    }
                }
            }
        }
    }
}

@Composable
private fun PlusButton(selected: Boolean, onClick: () -> Unit) {
    val bgMod = if (selected) Modifier.background(LocalContentColor.current, RoundedCornerShape(14.dp)) else Modifier
    IconButton(onClick = onClick, modifier = bgMod) {
        val tint = if (selected) contentColorFor(LocalContentColor.current) else LocalContentColor.current
        Icon(Icons.Default.Add, tint = tint, modifier = Modifier.padding(6.dp).size(28.dp), contentDescription = "更多")
    }
}

@Composable
private fun PlusPanelItem(icon: Painter, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
            Icon(icon, contentDescription = label, modifier = Modifier.padding(14.dp).size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun SelectorButton(onClick: () -> Unit, icon: Painter, description: String, selected: Boolean) {
    val bgMod = if (selected) Modifier.background(LocalContentColor.current, RoundedCornerShape(14.dp)) else Modifier
    IconButton(onClick = onClick, modifier = bgMod) {
        val tint = if (selected) contentColorFor(LocalContentColor.current) else LocalContentColor.current
        Icon(icon, tint = tint, modifier = Modifier.padding(6.dp).size(28.dp), contentDescription = description)
    }
}

private const val EMOJI_COLUMNS = 8

@Composable
private fun EmojiTable(onTextAdded: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState())) {
        val rows = (emojis.size + EMOJI_COLUMNS - 1) / EMOJI_COLUMNS
        repeat(rows) { x ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                repeat(EMOJI_COLUMNS) { y ->
                    val i = x * EMOJI_COLUMNS + y
                    if (i < emojis.size) {
                        val e = emojis[i]
                        Text(e, modifier = Modifier.clickable { onTextAdded(e) }.sizeIn(minWidth = 42.dp, minHeight = 42.dp).padding(8.dp),
                            style = LocalTextStyle.current.copy(fontSize = 20.sp, textAlign = TextAlign.Center))
                    } else Spacer(Modifier.sizeIn(minWidth = 42.dp, minHeight = 42.dp))
                }
            }
        }
    }
}

private val emojis = listOf(
    "😀", "😁", "😂", "🤣", "😅", "😆", "😉", "😊",
    "😋", "😎", "😍", "🥰", "😘", "😗", "😚", "🙂",
    "🤗", "🤔", "😐", "🙄", "😏", "😣", "😮", "😫",
    "😴", "😌", "🤤", "😜", "🤪", "😒", "😔", "😭",
    "😱", "😠", "🤬", "🥺", "🤡", "😈", "👻", "💀",
    "👍", "👎", "👏", "🙏", "💪", "🤝", "✌", "🤞",
    "❤", "🧡", "💛", "💚", "💙", "💜", "💔", "💕",
    "🔥", "✨", "🎉", "🌟", "🌙", "☀", "🌧", "🌈",
    "🐱", "🐶", "🐹", "🐰", "🐧", "🦊", "🐷", "🐭",
    "☕", "🍵", "🍜", "🍚", "🍰", "🍺", "🧋", "🍉",
)
