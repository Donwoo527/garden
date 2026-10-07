package me.chen.laidian.ui

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.model.Plan
import me.chen.laidian.net.ChatApi
import me.chen.laidian.net.ChatClient
import org.json.JSONObject

/** 0.128 输入框草稿放页面外头：她打了半句退出去，再进来字还在（发失败也不清） */
private object PlanDraft { var text by mutableStateOf("") }

/**
 * 0.128 我们的清单（百宝箱→我们的清单）。她 1007："下次我们弄个计划清单吧，在app里那种" /
 * "清单我也可以加吧，可以学习苹果的待做事项，就是完成的，打个勾他就会灰掉，然后如果那个完成之后的东西我们就是收纳到已完成里面"
 * 照苹果「提醒事项」：前面一个圆圈，点一下打勾变灰、挪进最下面的「已完成」（默认收起，点开能看）；两人都能加，每条右下角小灰字标谁加的。
 * 长按一条 = 编辑 / 删除。数据全在服务端 data/plans.json（辰在服务器上走 8301 /plans），ws "plans" 广播全量实时刷新。
 * 网络失败只 Toast：输入框的字不清，勾不上的圈弹回去。样式照 ListenScreen（LocalSkin、WhiteCard），底栏照 SubPage.open 藏掉。
 */
@Composable
fun PlansScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { SubPage.open = true }
    DisposableEffect(Unit) { onDispose { SubPage.open = false } }

    val plans by ChatClient.plans.collectAsState()
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(reloadKey) {
        loadFailed = false
        if (withContext(Dispatchers.IO) { ChatApi.plans(ctx) } == null) {
            loadFailed = true
            Toast.makeText(ctx, "清单没拉到（${ChatApi.lastError ?: "网络不通"}）", Toast.LENGTH_SHORT).show()
        }
    }

    // 点了圈、服务端还没回的：id → 想要的状态（先画出来，失败弹回去）
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    // 刚勾上的在原位多留一下（看得见打勾、字变灰）再挪进已完成
    val linger = remember { mutableStateMapOf<String, Unit>() }
    var showDone by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Plan?>(null) }
    var adding by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    val all = plans ?: emptyList()
    fun isDone(p: Plan) = pending[p.id] ?: p.done
    // 没做的按加的先后（新加的在最下面，挨着输入框）；做完的按勾的时间 最近勾的在上
    val undone = all.filter { !isDone(it) || it.id in linger }.sortedBy { it.created }
    val done = all.filter { isDone(it) && it.id !in linger }.sortedByDescending { it.doneAt }

    fun toggle(p: Plan) {
        if (p.id in pending) return   // 上一下还没回 别连点来回翻
        val want = !isDone(p)
        pending[p.id] = want
        if (want) linger[p.id] = Unit
        scope.launch {
            if (want) launch { delay(650); linger.remove(p.id) }
            // 带上 done：服务端按"设成这个状态"处理，跟辰同时点也不会翻回去
            val r = withContext(Dispatchers.IO) { ChatApi.planOp(ctx, JSONObject().put("op", "toggle").put("id", p.id).put("done", want)) }
            pending.remove(p.id)
            if (r == null) {
                linger.remove(p.id)
                Toast.makeText(ctx, "没${if (want) "勾" else "取消"}上（${ChatApi.lastError ?: "网络不通"}）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun add() {
        val t = PlanDraft.text.trim()
        if (t.isEmpty() || adding) return
        adding = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { ChatApi.planOp(ctx, JSONObject().put("op", "add").put("text", t)) }
            adding = false
            if (r != null) {
                if (PlanDraft.text.trim() == t) PlanDraft.text = ""   // 等回包时她又改了字 就别清
                if (!showDone) { delay(80); scroll.animateScrollTo(scroll.maxValue) }   // 新的一条在没做列表最底下 滚过去看得见
            } else {
                Toast.makeText(ctx, "没加上（${ChatApi.lastError ?: "网络不通"}）字还在 再点一次", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun delete(p: Plan) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { ChatApi.planOp(ctx, JSONObject().put("op", "delete").put("id", p.id)) }
            if (r == null) Toast.makeText(ctx, "没删掉（${ChatApi.lastError ?: "网络不通"}）", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(skin.bg)) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 百宝箱", color = skin.muted) }
            }
            Text("我们的清单", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = skin.ink)
            Spacer(Modifier.height(6.dp))
            Text("两个人都能加。点前面的圈打勾，做完的收进最下面「已完成」；长按一条改或删。", fontSize = 13.sp, color = skin.muted)
            Spacer(Modifier.height(14.dp))

            when {
                plans == null && loadFailed -> Text("没拉到清单，点这里再试一次", fontSize = 14.sp, color = skin.muted,
                    modifier = Modifier.clickable { reloadKey++ }.padding(vertical = 8.dp))
                plans == null -> Text("加载中…", fontSize = 14.sp, color = skin.muted, modifier = Modifier.padding(vertical = 8.dp))
                undone.isEmpty() -> Text(if (done.isEmpty()) "还没有要做的，在下面写一条" else "都做完了", fontSize = 14.sp, color = skin.muted, modifier = Modifier.padding(vertical = 8.dp))
                else -> WhiteCard {
                    Column(Modifier.fillMaxWidth().animateContentSize().padding(vertical = 4.dp)) {
                        undone.forEachIndexed { i, p ->
                            key(p.id) {
                                if (i > 0) PlanDivider()
                                PlanRow(p, checked = isDone(p), onToggle = { toggle(p) }, onEdit = { editing = p }, onDelete = { delete(p) })
                            }
                        }
                    }
                }
            }

            // ---------- 已完成 N（默认收起）----------
            if (done.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showDone = !showDone }.padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已完成 ${done.size}", fontSize = 14.sp, color = skin.muted, modifier = Modifier.weight(1f))
                    Text(if (showDone) "收起 ▾" else "展开 ▸", fontSize = 13.sp, color = skin.muted)
                }
                if (showDone) {
                    Spacer(Modifier.height(6.dp))
                    WhiteCard {
                        Column(Modifier.fillMaxWidth().animateContentSize().padding(vertical = 4.dp)) {
                            done.forEachIndexed { i, p ->
                                key(p.id) {
                                    if (i > 0) PlanDivider()
                                    PlanRow(p, checked = true, onToggle = { toggle(p) }, onEdit = { editing = p }, onDelete = { delete(p) })
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ---------- 底部输入条：回车 / 发送键 = 加一条 ----------
        Row(Modifier.fillMaxWidth().background(skin.bg).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).heightIn(min = 44.dp).sunken(22.dp).padding(horizontal = 16.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = PlanDraft.text, onValueChange = { PlanDraft.text = it }, singleLine = true,
                    cursorBrush = SolidColor(skin.ink),
                    textStyle = LocalTextStyle.current.copy(color = skin.ink, fontSize = 15.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { add() }, onDone = { add() }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner -> Box { if (PlanDraft.text.isEmpty()) Text("加一条…", fontSize = 15.sp, color = skin.muted); inner() } },
                )
            }
            val canSend = PlanDraft.text.isNotBlank() && !adding
            Box(
                Modifier.padding(start = 10.dp).size(44.dp).then(if (canSend) Modifier.pressable(22.dp) { add() } else Modifier.flat(22.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Send, contentDescription = "加一条", tint = if (canSend) skin.accent else skin.muted, modifier = Modifier.size(20.dp)) }
        }
    }

    // ---------- 长按→编辑：失败不关框，字还在 ----------
    editing?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.text) }
        var saving by remember(p.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) editing = null },
            title = { Text("改一下") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = !saving && text.isNotBlank(), onClick = {
                    val t = text.trim()
                    if (t == p.text) { editing = null; return@TextButton }
                    saving = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { ChatApi.planOp(ctx, JSONObject().put("op", "edit").put("id", p.id).put("text", t)) }
                        saving = false
                        if (r != null) editing = null
                        else Toast.makeText(ctx, "没改上（${ChatApi.lastError ?: "网络不通"}）字还在", Toast.LENGTH_SHORT).show()
                    }
                }) { Text(if (saving) "保存中…" else "保存") }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { editing = null }) { Text("算了") } },
        )
    }
}

/** 一条：圈 + 字 + 右下角小灰字（辰加的 / 我加的；勾了的再带上是谁勾的）。长按出编辑/删除 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanRow(p: Plan, checked: Boolean, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val skin = LocalSkin.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    // 打勾时字跟着慢慢变灰（她："打个勾他就会灰掉"）
    val gray by animateFloatAsState(if (checked) 1f else 0f, tween(260), label = "plan_gray")
    val label = buildString {
        append(if (p.isChen) "辰加的" else "我加的")
        if (checked && p.doneBy.isNotBlank()) append(if (p.doneBy == "chen") " · 辰勾的" else " · 我勾的")
    }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
                .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            CheckCircle(checked, onToggle)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f).padding(top = 5.dp)) {
                Text(
                    p.text, fontSize = 15.sp, lineHeight = 21.sp,
                    color = lerp(skin.ink, skin.muted, gray),
                    textDecoration = if (checked) TextDecoration.LineThrough else null,
                )
                Text(label, fontSize = 11.sp, color = skin.muted, modifier = Modifier.align(Alignment.End).padding(top = 2.dp))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
        }
    }
}

/** 圆圈：没做 = 空心细圈；勾上 = 从中心填满强调色，白勾一笔画出来（小小的，别太花） */
@Composable
private fun CheckCircle(checked: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    val p by animateFloatAsState(if (checked) 1f else 0f, tween(if (checked) 320 else 160), label = "plan_check")
    Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 1.6.dp.toPx()
            val r = size.minDimension / 2
            drawCircle(lerp(skin.muted, skin.accent, p), radius = r - stroke / 2, style = Stroke(stroke))
            val fill = (p * 1.6f).coerceAtMost(1f)
            if (fill > 0f) drawCircle(skin.accent, radius = r * fill)
            val tick = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
            if (tick > 0f) {
                val a = Offset(size.width * 0.28f, size.height * 0.52f)
                val b = Offset(size.width * 0.44f, size.height * 0.67f)
                val c = Offset(size.width * 0.73f, size.height * 0.36f)
                val l1 = (b - a).getDistance()
                val l2 = (c - b).getDistance()
                val drawn = (l1 + l2) * tick
                val w = 2.dp.toPx()
                if (drawn <= l1) {
                    drawLine(Color.White, a, a + (b - a) * (drawn / l1), w, StrokeCap.Round)
                } else {
                    drawLine(Color.White, a, b, w, StrokeCap.Round)
                    drawLine(Color.White, b, b + (c - b) * ((drawn - l1) / l2), w, StrokeCap.Round)
                }
            }
        }
    }
}

/** 行间细线：从字的位置起（让开圈），跟苹果那种一样 */
@Composable
private fun PlanDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 46.dp, end = 14.dp).height(0.5.dp).background(LocalSkin.current.line))
}
