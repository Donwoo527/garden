package me.chen.laidian.ui.jet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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

enum class InputSelector { NONE, EMOJI }

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

    Surface(tonalElevation = 2.dp, contentColor = MaterialTheme.colorScheme.secondary) {
        Column(modifier) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).fillMaxSize()) {
                    BasicTextField(
                        value = textState,
                        onValueChange = { textState = it; onTyping(it.text.isNotEmpty()) },
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp).align(Alignment.CenterStart)
                            .onFocusChanged { st -> if (st.isFocused) { selector = InputSelector.NONE; resetScroll() }; focused = st.isFocused },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions { send() },
                        maxLines = 4,
                        cursorBrush = SolidColor(LocalContentColor.current),
                        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                    )
                    if (textState.text.isEmpty() && !focused) {
                        Text("说点什么…", modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp),
                            style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
            }
            Row(Modifier.height(64.dp).padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SelectorButton(onClick = { selector = if (selector == InputSelector.EMOJI) InputSelector.NONE else InputSelector.EMOJI },
                    icon = painterResource(R.drawable.ic_mood), selected = selector == InputSelector.EMOJI, description = "表情")
                SelectorButton(onClick = { dismiss(); onPickImages() }, icon = painterResource(R.drawable.ic_insert_photo), selected = false, description = "图片")
                SelectorButton(onClick = { dismiss(); onCall() }, icon = painterResource(R.drawable.ic_duo), selected = false, description = "打电话")
                Spacer(Modifier.weight(1f))
                val enabled = textState.text.isNotBlank()
                Button(
                    modifier = Modifier.height(36.dp), enabled = enabled, onClick = { send(); dismiss() },
                    colors = ButtonDefaults.buttonColors(disabledContainerColor = Color.Transparent, disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)),
                    border = if (!enabled) BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)) else null,
                    contentPadding = PaddingValues(0.dp),
                ) { Text("发送", Modifier.padding(horizontal = 16.dp)) }
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
        }
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
