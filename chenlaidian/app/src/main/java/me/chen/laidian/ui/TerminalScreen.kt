package me.chen.laidian.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import me.chen.laidian.net.TermClient

private val TermBg = Color(0xFF161615)
private val TermFg = Color(0xFFE8E4DE)
private val ChipBg = Color(0xFF26262A)

// 控制序列（写成转义，源码里不能有裸控制字符）
private const val ESC = "\u001b"
private const val CTRL_C = "\u0003"
private const val CTRL_D = "\u0004"
private const val BACKSPACE = "\u007f"

// 逻辑列数固定 80：手机只"看"，不把共享 tmux 窗口挤成手机的窄尺寸。
// (0909 教训：跟随屏宽上报 48 列 → 窗口被挤 → 80 列画的历史全被硬折成碎行)
private const val COLS = 80

/**
 * 终端页：Termux 的模拟器负责解析，这里用 Canvas 按格子画。
 * 服务器侧尺寸(80×rows)首连定死；A-/A+ 只缩放显示，超出屏幕用双向滚动看。
 */
@Composable
fun TerminalScreen() {
    val ctx = LocalContext.current
    val tick by TermClient.redraw.collectAsState()
    val status by TermClient.status.collectAsState()
    // zoom=1 → 80列正好铺满屏宽；只影响本机渲染，不改服务器窗口
    var zoom by remember { mutableFloatStateOf(1f) }
    var viewW by remember { mutableIntStateOf(0) }
    var viewH by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // 等宽字体每 1px 字号占的横向像素，用来反推"80列铺满"所需字号
    val unitW = remember {
        Paint().apply { typeface = Typeface.MONOSPACE; textSize = 100f }.measureText("W") / 100f
    }
    val basePx = if (viewW > 0) viewW / (COLS * unitW) else 12f
    val fontPx = basePx * zoom
    val paint = remember(fontPx) {
        Paint().apply { typeface = Typeface.MONOSPACE; isAntiAlias = true; textSize = fontPx }
    }
    val charW = remember(paint) { paint.measureText("W") }
    val fm = remember(paint) { paint.fontMetrics }
    val rowH = remember(fm) { fm.descent - fm.ascent }
    var field by remember { mutableStateOf(TextFieldValue("")) }

    Column(Modifier.fillMaxSize().background(TermBg)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (TermClient.mode == "shell") "切到辰的session" else "切到纯shell") { TermClient.switchMode(if (TermClient.mode == "shell") "attach" else "shell") }
            Chip("A-") { if (zoom > 0.71f) zoom /= 1.2f }
            Chip("A+") { if (zoom < 2.9f) zoom *= 1.2f }
            Chip("重连") { TermClient.reconnect() }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key("Esc", ESC); Key("Tab", "\t"); Key("^C", CTRL_C); Key("^D", CTRL_D)
            Key("↑", ESC + "[A"); Key("↓", ESC + "[B"); Key("←", ESC + "[D"); Key("→", ESC + "[C")
            Key("⌫", BACKSPACE); Key("⏎", "\r"); Key("/", "/"); Key("-", "-")
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)
                .onSizeChanged { sz -> viewW = sz.width; viewH = sz.height }
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState())
        ) {
            if (viewW > 0 && viewH > 0) {
                // 首连一次性定逻辑尺寸：80 列 × 按铺满字号能显示的行数。之后不再上报 resize，
                // 键盘弹出/调字号都只是"看"的变化，共享窗口不抖。
                LaunchedEffect(Unit) {
                    val p0 = Paint().apply { typeface = Typeface.MONOSPACE; textSize = viewW / (COLS * unitW) }
                    val rh0 = p0.fontMetrics.let { it.descent - it.ascent }
                    val r = (viewH / rh0).toInt().coerceIn(20, 60)
                    TermClient.ensure(ctx, COLS, r)
                }
                val em = TermClient.emulator
                if (em != null) {
                    val canvasW = with(density) { (em.mColumns * charW).toDp() }
                    val canvasH = with(density) { (em.mRows * rowH).toDp() }
                    Canvas(Modifier.size(canvasW, canvasH)) {
                        @Suppress("UNUSED_EXPRESSION") tick
                        val screen = em.screen
                        val colors = em.mColors.mCurrentColors
                        val defBg = colors[TextStyle.COLOR_INDEX_BACKGROUND]
                        fun resolve(v: Int): Int = if ((v and 0xff000000.toInt()) == 0xff000000.toInt()) v else colors[v.coerceIn(0, 258)]
                        drawIntoCanvas { cv ->
                            val nc = cv.nativeCanvas
                            val cursorRow = em.cursorRow
                            val cursorCol = em.cursorCol
                            for (r in 0 until em.mRows) {
                                val row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(r))
                                val baseline = r * rowH - fm.ascent
                                var c = 0
                                while (c < em.mColumns) {
                                    val style = row.getStyle(c)
                                    val effect = TextStyle.decodeEffect(style)
                                    var fg = resolve(TextStyle.decodeForeColor(style))
                                    var bg = resolve(TextStyle.decodeBackColor(style))
                                    if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0) { val t = fg; fg = bg; bg = t }
                                    val start = row.findStartOfColumn(c)
                                    val end = row.findStartOfColumn(c + 1)
                                    val text = if (end > start) String(row.mText, start, end - start) else ""
                                    val wide = text.isNotEmpty() && WcWidth.width(text.codePointAt(0)) == 2
                                    val cells = if (wide) 2 else 1
                                    if (bg != defBg) {
                                        drawRect(Color(bg), Offset(c * charW, r * rowH), Size(charW * cells, rowH))
                                    }
                                    if (text.isNotEmpty() && text != " " && (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
                                        paint.color = if ((effect and TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0) (fg and 0x00ffffff) or 0x99000000.toInt() else fg
                                        paint.isFakeBoldText = (effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0
                                        paint.isUnderlineText = (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
                                        nc.drawText(text, c * charW, baseline, paint)
                                    }
                                    c += cells
                                }
                            }
                            if (em.isCursorEnabled && em.shouldCursorBeVisible()) {
                                drawRect(Color(0x99E8E4DE), Offset(cursorCol * charW, cursorRow * rowH), Size(charW, rowH))
                            }
                        }
                    }
                }
            }
        }
        Text(status, color = Color(0xFF9A9590), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Row(Modifier.fillMaxWidth().background(ChipBg).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$ ", color = TermFg, fontSize = 14.sp)
            BasicTextField(
                value = field,
                onValueChange = { v ->
                    val old = field.text
                    val new = v.text
                    when {
                        new.startsWith(old) && new.length > old.length -> {
                            val added = new.substring(old.length)
                            if (added.all { it.code in 32..126 }) { TermClient.sendText(added); field = TextFieldValue("") }
                            else field = v
                        }
                        old.startsWith(new) -> {
                            if (old.all { it.code in 32..126 }) repeat(old.length - new.length) { TermClient.sendText(BACKSPACE) }
                            field = v
                        }
                        else -> field = v
                    }
                },
                textStyle = ComposeTextStyle(color = TermFg, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (field.text.isNotEmpty()) TermClient.sendText(field.text)
                    TermClient.sendText("\r")
                    field = TextFieldValue("")
                }),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(Modifier.background(ChipBg, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, color = TermFg, fontSize = 12.sp)
    }
}

@Composable
private fun Key(label: String, seq: String) {
    Box(Modifier.background(ChipBg, RoundedCornerShape(8.dp)).clickable { TermClient.sendText(seq) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, color = TermFg, fontSize = 14.sp)
    }
}
