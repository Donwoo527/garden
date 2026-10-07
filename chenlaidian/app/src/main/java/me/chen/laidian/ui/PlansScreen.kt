package me.chen.laidian.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.model.Plan
import me.chen.laidian.net.ChatApi
import me.chen.laidian.net.ChatClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** 0.128 输入框草稿放页面外头：她打了半句退出去，再进来字还在（发失败也不清）。
 *  0.129 再记住上次停在哪个标签；详情页没存上的标题/备注也先留这（下次点进那条还在） */
private object PlanDraft {
    var text by mutableStateOf("")
    var tab by mutableStateOf(Plan.TAB_COMMON)
    val details = HashMap<String, Pair<String, String>>()
}

private val StarGold = Color(0xFFF0A63A)
private val DelRed = Color(0xFFE53935)

// 0.129 星的扇形泡泡（她画的：星上面 1 / 2 / 3 三个小气泡，2 在中间最高）。位置相对星的中心，单位 dp
private val FAN_DX = floatArrayOf(-50f, 0f, 50f)
private val FAN_DY = floatArrayOf(-50f, -72f, -50f)
private const val FAN_BODY = 40f    // 泡泡本体边长
private const val FAN_HIT = 38f     // 手指离泡泡中心多近算选中；三个都不够近 = 滑出去了，松手不变

private class Holder<T>(var v: T)

/** 星的扇形：按住星满长按时间后弹出，手指在哪个泡泡上松手就是几星。状态放页面最外层，泡泡画在整页最上面（不被卡片裁掉） */
@Stable
private class StarFan {
    var id by mutableStateOf<String?>(null)
    var center by mutableStateOf(Offset.Zero)   // 星的中心（窗口坐标）
    var shift by mutableFloatStateOf(0f)         // 星靠屏幕右边时整把扇子往左挪（px，≤0），不然 3 那个泡泡出屏
    var hover by mutableIntStateOf(0)
    var current by mutableIntStateOf(0)
    var rootPos = Offset.Zero                    // 页面根 Box 的窗口坐标/宽：算往左挪多少、画的时候换算
    var rootW = 0

    fun bubble(i: Int, d: Density): Offset = with(d) { center + Offset(shift + FAN_DX[i - 1].dp.toPx(), FAN_DY[i - 1].dp.toPx()) }
    fun open(planId: String, c: Offset, cur: Int, d: Density) {
        center = c; current = cur; hover = 0
        val right = with(d) { c.x + (FAN_DX[2] + FAN_BODY / 2 + 12f).dp.toPx() }
        val edge = rootPos.x + rootW
        shift = if (rootW > 0 && right > edge) edge - right else 0f
        id = planId
    }
    fun hit(p: Offset, d: Density): Int {
        val lim = with(d) { FAN_HIT.dp.toPx() }
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 1..3) {
            val dd = (bubble(i, d) - p).getDistance()
            if (dd < lim && dd < bestD) { best = i; bestD = dd }
        }
        return best
    }
    fun close() { id = null; hover = 0 }
}

private fun Plan.title1() = text.replace('\n', ' ')

/**
 * 0.129 计划清单 v2（百宝箱→我们的清单），照她 1007 手画的三张图：
 * - 标题「计划清单」，顶上四个标签 我们共同 / 小陈个人 / 辰想做 / 已完成；前三栏里分「置顶」「其他」两块
 * - 每条：左圆圈 + 标题，下一行小字是备注（便签，一行省略），右边写下那天的日期 + 谁加的，最右星
 * - 星只管排序（星多在前），不等于置顶；点星 0↔1，按住星往上滑弹 1/2/3 选几星
 * - 长按整条 → 小气泡「置顶 / 取消置顶」；双击标题直接改；单击进详情；左滑露红色删除（有星的先确认）
 * - 打勾 → 变灰删除线，进「已完成」，原栏也继续显示；勾了三天没动静沉到该栏最底
 * 数据全在服务端 data/plans.json（辰在服务器上走 8301 /plans），ws "plans" 广播全量实时刷新。
 * 失败只 Toast + 回弹，输入的字不丢。0.128 的打勾动画（CheckCircle）原样留着。
 *
 * 手势怎么分（谁先拿到手指 = 谁在最里层）：
 *   星（最里层）：按下就吃掉 down，整条的单击/长按收不到；没挪远就松手 = 单击；挪过 touchSlop = 放弃，交给列表滚动 / 左滑；
 *              按满系统长按时间 = 弹扇形，之后吃掉所有移动（列表不滚、不左滑），松手结算。
 *   圆圈：clickable，只管打勾。
 *   标题文字（只有字本身那么宽）：单击进详情 / 双击改标题 / 长按置顶气泡。有双击所以单击要等双击超时（约 300ms）才进详情。
 *   整条其余地方（备注、日期、空白）：单击立刻进详情（不等）/ 长按置顶气泡。
 *   整条外层：横向拖过 touchSlop = 左滑删除（只认水平分量）；竖向拖 = 列表滚动（verticalScroll），谁先过 slop 归谁。
 *   有一条左滑开着时，点任何一条 = 先把它合上（不进详情）。
 */
@Composable
fun PlansScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LaunchedEffect(Unit) { SubPage.open = true }
    DisposableEffect(Unit) { onDispose { SubPage.open = false } }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    fun why() = ChatApi.lastError ?: "网络不通"

    // 服务端那份：ws 广播 / GET / POST 回包都落 ChatClient.plans；POST 成功时这里也直接换（和撤掉"等回包"标记在同一帧，不闪回旧值）
    var server by remember { mutableStateOf(ChatClient.plans.value) }
    LaunchedEffect(Unit) { ChatClient.plans.collect { server = it } }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(reloadKey) {
        loadFailed = false
        if (withContext(Dispatchers.IO) { ChatApi.plans(ctx) } == null) {
            loadFailed = true
            toast("清单没拉到（${why()}）")
        }
    }
    // 0.129 小红点：进了清单页就清（在页里时辰又改了，亮一下马上也清掉）
    val unread by ChatClient.plansUnread.collectAsState()
    LaunchedEffect(unread) { if (unread) withContext(Dispatchers.IO) { ChatApi.plansSeen(ctx) } }

    // 点了、服务端还没回的：先按想要的画出来，失败弹回去（每种改动一张表，同一条同一种改动没回包前不再发）
    val pDone = remember { mutableStateMapOf<String, Boolean>() }
    val pStars = remember { mutableStateMapOf<String, Int>() }
    val pPin = remember { mutableStateMapOf<String, Boolean>() }
    val pTab = remember { mutableStateMapOf<String, String>() }
    val pText = remember { mutableStateMapOf<String, String>() }
    val pNote = remember { mutableStateMapOf<String, String>() }
    val pDel = remember { mutableStateMapOf<String, Unit>() }

    var detailId by remember { mutableStateOf<String?>(null) }
    var swipeOpenId by remember { mutableStateOf<String?>(null) }
    var editTitleId by remember { mutableStateOf<String?>(null) }
    var titleDraft by remember { mutableStateOf(TextFieldValue("")) }
    var pinMenu by remember { mutableStateOf<Pair<String, Offset>?>(null) }   // id → 按下的点（相对页面根）
    var confirmDel by remember { mutableStateOf<Plan?>(null) }
    var adding by remember { mutableStateOf(false) }
    var justAdded by remember { mutableStateOf<String?>(null) }
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    val fan = remember { StarFan() }
    val scroll = rememberScrollState()
    val nowSec = System.currentTimeMillis() / 1000.0

    fun view(p: Plan): Plan {
        val d = pDone[p.id]
        return p.copy(
            done = d ?: p.done,
            doneAt = when { d == true && !p.done -> nowSec; d == false -> 0.0; else -> p.doneAt },
            doneBy = when { d == true && !p.done -> "xiaochen"; d == false -> ""; else -> p.doneBy },
            stars = pStars[p.id] ?: p.stars,
            pinned = pPin[p.id] ?: p.pinned,
            tab = pTab[p.id] ?: p.tab,
            text = pText[p.id] ?: p.text,
            note = pNote[p.id] ?: p.note,
        )
    }
    fun current(id: String) = server?.firstOrNull { it.id == id }?.let(::view)

    fun <T : Any> send(map: SnapshotStateMap<String, T>, id: String, value: T, fail: String, call: () -> List<Plan>?) {
        if (id in map) return   // 上一下还没回 别连点来回翻
        map[id] = value
        scope.launch {
            val r = withContext(Dispatchers.IO) { call() }
            if (r != null) server = r
            map.remove(id)
            if (r == null) toast("$fail（${why()}）")
        }
    }

    // 带上 done：服务端按"设成这个状态"处理，跟辰同时点也不会翻回去
    fun toggle(p: Plan) { val want = !p.done; send(pDone, p.id, want, if (want) "没勾上" else "没取消") { ChatApi.planToggle(ctx, p.id, want) } }
    fun setStars(p: Plan, n: Int) { if (n != p.stars) send(pStars, p.id, n, "星没标上") { ChatApi.planStar(ctx, p.id, n) } }
    fun setPin(p: Plan, v: Boolean) { if (v != p.pinned) send(pPin, p.id, v, if (v) "没置顶上" else "没取消置顶") { ChatApi.planPin(ctx, p.id, v) } }
    fun setTab(p: Plan, t: String) { if (t != p.tab) send(pTab, p.id, t, "没挪过去") { ChatApi.planEdit(ctx, p.id, tab = t) } }

    fun delete(p: Plan) {
        if (p.id in pDel) return
        pDel[p.id] = Unit
        if (swipeOpenId == p.id) swipeOpenId = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { ChatApi.planDelete(ctx, p.id) }
            if (r != null) server = r
            pDel.remove(p.id)
            if (r == null) toast("没删掉（${why()}）")
        }
    }
    // 她 1007：有星的删除前确认一下
    fun requestDelete(p: Plan) { if (p.stars > 0) confirmDel = p else delete(p) }

    // 双击标题就地改：回车 / 点别处 / 返回键 = 保存；失败把输入框连字一起放回去
    fun saveTitle(p: Plan, raw: String) {
        val t = raw.replace('\n', ' ').trim()
        if (t.isEmpty() || t == p.title1().trim()) return
        pText[p.id] = t
        scope.launch {
            val r = withContext(Dispatchers.IO) { ChatApi.planEdit(ctx, p.id, text = t) }
            if (r != null) server = r
            pText.remove(p.id)
            if (r == null) {
                toast("没改上（${why()}）字还在")
                titleDraft = TextFieldValue(t, TextRange(t.length))
                editTitleId = p.id
            }
        }
    }
    fun commitTitleEdit(id: String) {
        if (editTitleId != id) return
        editTitleId = null
        current(id)?.let { saveTitle(it, titleDraft.text) }
    }
    fun startTitleEdit(p: Plan) {
        editTitleId?.let { if (it != p.id) commitTitleEdit(it) }
        val t = p.title1()
        titleDraft = TextFieldValue(t, TextRange(t.length))
        editTitleId = p.id
        swipeOpenId = null
    }
    BackHandler(enabled = editTitleId != null && detailId == null) { editTitleId?.let { commitTitleEdit(it) } }

    // 详情页：标题 + 备注一次发（服务端 edit 带哪个改哪个）
    fun saveDetail(p: Plan, title: String, note: String, done: (Boolean) -> Unit) {
        val t = title.replace('\n', ' ').trim()
        val n = note.trim()
        val tt = if (t.isNotEmpty() && t != p.title1().trim()) t else null
        val nn = if (n != p.note) n else null
        if (tt == null && nn == null) { done(true); return }
        tt?.let { pText[p.id] = it }
        nn?.let { pNote[p.id] = it }
        scope.launch {
            val r = withContext(Dispatchers.IO) { ChatApi.planEdit(ctx, p.id, text = tt, note = nn) }
            if (r != null) server = r
            if (tt != null) pText.remove(p.id)
            if (nn != null) pNote.remove(p.id)
            if (r == null) toast("没存上（${why()}）字还在")
            done(r != null)
        }
    }

    fun add() {
        val t = PlanDraft.text.trim()
        val tab = PlanDraft.tab
        if (t.isEmpty() || adding || tab == Plan.TAB_DONE) return
        adding = true
        val before = server?.map { it.id }?.toHashSet() ?: hashSetOf()
        scope.launch {
            val r = withContext(Dispatchers.IO) { ChatApi.planAdd(ctx, t, tab) }
            adding = false
            if (r != null) {
                server = r
                if (PlanDraft.text.trim() == t) PlanDraft.text = ""   // 等回包时她又改了字 就别清
                val added = r.lastOrNull { it.id !in before }
                justAdded = added?.id   // 那条自己滚进视野
                // 服务端还没重启（0.128 版不认 tab）时会落到「我们共同」，说一声别让她以为丢了
                if (added != null && added.tab != tab) toast("服务端还是旧版，先放进了「我们共同」")
            } else {
                toast("没加上（${why()}）字还在 再点一次")
            }
        }
    }
    LaunchedEffect(justAdded) { if (justAdded != null) { delay(1500); justAdded = null } }

    fun selectTab(k: String) {
        editTitleId?.let { commitTitleEdit(it) }
        swipeOpenId = null
        if (PlanDraft.tab != k) { PlanDraft.tab = k; scope.launch { scroll.scrollTo(0) } }
    }

    val all = (server ?: emptyList()).filter { it.id !in pDel }.map(::view)
    val tab = PlanDraft.tab
    // 她定的排序：星多在前，同星按写下的先后（新的在后）；勾了的三天内原地不动，过了三天沉到最底（沉下去的按勾的时间 新的在上）
    val order = Comparator<Plan> { a, b ->
        val sa = a.sunk(nowSec)
        val sb = b.sunk(nowSec)
        when {
            sa != sb -> if (sa) 1 else -1
            sa -> b.doneAt.compareTo(a.doneAt)
            a.stars != b.stars -> b.stars - a.stars
            else -> a.created.compareTo(b.created)
        }
    }
    val inTab = all.filter { it.tab == tab }
    val pinned = inTab.filter { it.pinned }.sortedWith(order)
    val others = inTab.filter { !it.pinned }.sortedWith(order)
    val doneList = all.filter { it.done }.sortedByDescending { it.doneAt }

    val row: @Composable (Plan, Boolean) -> Unit = { p, inDoneTab ->
        PlanRow(
            p = p,
            label = if (inDoneTab) Plan.tabName(p.tab) else if (p.isChen) "辰加的" else "我加的",
            editing = editTitleId == p.id,
            draft = titleDraft,
            onDraft = { titleDraft = it },
            onCommitTitle = { commitTitleEdit(p.id) },
            swipeOpen = swipeOpenId == p.id,
            anyOpen = swipeOpenId != null,
            onSwipe = { open -> if (open) swipeOpenId = p.id else if (swipeOpenId == p.id) swipeOpenId = null },
            onCloseAll = { swipeOpenId = null },
            onToggle = { toggle(p) },
            onOpen = { editTitleId?.let { commitTitleEdit(it) }; detailId = p.id },
            onEditTitle = { startTitleEdit(p) },
            onLongPress = { win -> swipeOpenId = null; pinMenu = p.id to (win - rootPos) },
            fan = fan,
            onStarTap = { setStars(p, if (p.stars > 0) 0 else 1) },   // 她画的：点一下标星，再点一下取消
            onStarSet = { n -> setStars(p, n) },
            onDelete = { requestDelete(p) },
            bring = justAdded == p.id,
        )
    }

    Box(
        Modifier.fillMaxSize().background(skin.bg).onGloballyPositioned {
            rootPos = it.positionInWindow(); fan.rootPos = rootPos; fan.rootW = it.size.width
        }
    ) {
        val did = detailId
        if (did != null) {
            PlanDetail(
                p = all.firstOrNull { it.id == did },
                onBack = { detailId = null },
                onToggle = { toggle(it) },
                onStars = { p, n -> setStars(p, n) },
                onTab = { p, t -> setTab(p, t) },
                onSave = { p, t, n, done -> saveDetail(p, t, n, done) },
            )
        } else Column(Modifier.fillMaxSize()) {
            // ---------- 头：返回 + 计划清单 + 四个标签（不跟着滚）----------
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                TextButton(onClick = onBack) { Text("← 百宝箱", color = skin.muted) }
                Text("计划清单", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = skin.ink)
                Spacer(Modifier.height(14.dp))
                PlanTabs(tab) { selectTab(it) }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 14.dp)) {
                when {
                    server == null && loadFailed -> Text("没拉到清单，点这里再试一次", fontSize = 14.sp, color = skin.muted,
                        modifier = Modifier.clickable { reloadKey++ }.padding(vertical = 8.dp))
                    server == null -> Text("加载中…", fontSize = 14.sp, color = skin.muted, modifier = Modifier.padding(vertical = 8.dp))
                    tab == Plan.TAB_DONE -> {
                        if (doneList.isEmpty()) PlanHint("还没有做完的。勾掉一条，它就会出现在这里（原来那栏也还在）")
                        else PlanCard(doneList) { row(it, true) }
                    }
                    else -> {
                        PlanSection("置顶")
                        if (pinned.isEmpty()) PlanHint("长按一条 → 置顶，就到这里来")
                        else PlanCard(pinned) { row(it, false) }
                        Spacer(Modifier.height(18.dp))
                        PlanSection("其他")
                        if (others.isEmpty()) PlanHint(if (pinned.isEmpty()) "这一栏还没有，在下面写一条" else "别的都在上面了")
                        else PlanCard(others) { row(it, false) }
                    }
                }
                if (server != null) {
                    Spacer(Modifier.height(22.dp))
                    Text(
                        "单击看详情 · 双击标题改字 · 长按置顶 · 左滑删除\n星：点一下开关，按住往上滑选 1–3 颗",
                        fontSize = 11.5.sp, lineHeight = 17.sp, color = skin.muted.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // ---------- 底部输入条：加到当前这栏（「已完成」里不显示）----------
            if (tab != Plan.TAB_DONE) {
                Row(Modifier.fillMaxWidth().background(skin.bg).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).heightIn(min = 44.dp).sunken(22.dp).padding(horizontal = 16.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                        BasicTextField(
                            value = PlanDraft.text, onValueChange = { PlanDraft.text = it }, singleLine = true,
                            cursorBrush = SolidColor(skin.ink),
                            textStyle = LocalTextStyle.current.copy(color = skin.ink, fontSize = 15.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { add() }, onDone = { add() }),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                Box {
                                    if (PlanDraft.text.isEmpty()) Text("加到「${Plan.tabName(tab)}」…", fontSize = 15.sp, color = skin.muted)
                                    inner()
                                }
                            },
                        )
                    }
                    val canSend = PlanDraft.text.isNotBlank() && !adding
                    Box(
                        Modifier.padding(start = 10.dp).size(44.dp).then(if (canSend) Modifier.pressable(22.dp) { add() } else Modifier.flat(22.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Send, contentDescription = "加一条", tint = if (canSend) skin.accent else skin.muted, modifier = Modifier.size(20.dp)) }
                }
            }
        }

        // ---------- 星的扇形泡泡：画在整页最上层 ----------
        StarFanOverlay(fan, rootPos)

        // ---------- 长按一条 → 按下的地方上面冒个小气泡：置顶 / 取消置顶 ----------
        pinMenu?.let { (id, rel) ->
            val p = all.firstOrNull { it.id == id }
            if (p != null && detailId == null) {
                val gap = with(density) { 8.dp.roundToPx() }
                val margin = with(density) { 8.dp.roundToPx() }
                Popup(
                    popupPositionProvider = remember(rel) { AbovePoint(rel, gap, margin) },
                    onDismissRequest = { pinMenu = null },
                    properties = PopupProperties(focusable = true),
                ) {
                    TipBubble(if (p.pinned) "取消置顶" else "置顶") { pinMenu = null; setPin(p, !p.pinned) }
                }
            }
        }
    }

    // ---------- 有星的删除前确认 ----------
    confirmDel?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text("删掉这条？") },
            text = { Text("「${p.title1()}」标了 ${p.stars} 颗星，删了就没了") },
            confirmButton = { TextButton(onClick = { confirmDel = null; delete(p) }) { Text("删掉", color = DelRed) } },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("算了") } },
        )
    }
}

/** 气泡放在按下那个点的正上方（贴着屏幕边就往里收）；坐标相对 Popup 的父布局（页面根 Box） */
private class AbovePoint(private val rel: Offset, private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val px = anchorBounds.left + rel.x.roundToInt()
        val py = anchorBounds.top + rel.y.roundToInt()
        val x = (px - popupContentSize.width / 2).coerceIn(margin, max(margin, windowSize.width - popupContentSize.width - margin))
        val y = (py - popupContentSize.height - gap).coerceAtLeast(margin)
        return IntOffset(x, y)
    }
}

/** 四个标签：选中的凹下去（她的凹凸语言：选中 = 按下去的状态），没选的凸起可按 */
@Composable
private fun PlanTabs(selected: String, onSelect: (String) -> Unit) {
    val skin = LocalSkin.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (Plan.TABS + (Plan.TAB_DONE to "已完成")).forEach { (k, name) ->
            val sel = selected == k
            Box(
                Modifier.weight(1f).height(38.dp).then(if (sel) Modifier.sunken(12.dp) else Modifier.pressable(12.dp) { onSelect(k) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(name, fontSize = 13.5.sp, maxLines = 1, color = if (sel) skin.ink else skin.muted,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun PlanSection(text: String) {
    Text(text, fontSize = 13.sp, color = LocalSkin.current.muted, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun PlanHint(text: String) {
    Text(text, fontSize = 12.5.sp, color = LocalSkin.current.muted.copy(alpha = 0.85f), modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp))
}

/** 一块卡片（置顶 / 其他 / 已完成各一块），里面一条条，行间细线 */
@Composable
private fun PlanCard(list: List<Plan>, row: @Composable (Plan) -> Unit) {
    WhiteCard {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).animateContentSize()) {
            list.forEachIndexed { i, p ->
                key(p.id) {
                    if (i > 0) PlanDivider()
                    row(p)
                }
            }
        }
    }
}

/**
 * 一条：○ 标题 / 备注小字 …… 日期 / 谁加的 ☆
 * 外层管左滑删除，前景管单击/长按，标题管双击，星自己管点/按住上滑（见 PlansScreen 顶上的手势说明）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanRow(
    p: Plan,
    label: String,
    editing: Boolean,
    draft: TextFieldValue,
    onDraft: (TextFieldValue) -> Unit,
    onCommitTitle: () -> Unit,
    swipeOpen: Boolean,
    anyOpen: Boolean,
    onSwipe: (Boolean) -> Unit,
    onCloseAll: () -> Unit,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onEditTitle: () -> Unit,
    onLongPress: (Offset) -> Unit,
    fan: StarFan,
    onStarTap: () -> Unit,
    onStarSet: (Int) -> Unit,
    onDelete: () -> Unit,
    bring: Boolean,
) {
    val skin = LocalSkin.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // 手势协程只在 p.id 变时重启（中途重启会把正在拖的手势掐断），里面读的回调/状态都走 rememberUpdatedState
    val editingS = rememberUpdatedState(editing)
    val anyOpenS = rememberUpdatedState(anyOpen)
    val onSwipeS = rememberUpdatedState(onSwipe)
    val onCloseAllS = rememberUpdatedState(onCloseAll)
    val onCommitS = rememberUpdatedState(onCommitTitle)
    val onOpenS = rememberUpdatedState(onOpen)
    val onEditS = rememberUpdatedState(onEditTitle)
    val onLongS = rememberUpdatedState(onLongPress)

    val revealPx = with(density) { 76.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    LaunchedEffect(swipeOpen) { if (!swipeOpen && offsetX.value != 0f) offsetX.animateTo(0f, tween(180)) }
    val rowWin = remember { Holder(Offset.Zero) }
    val titleWin = remember { Holder(Offset.Zero) }
    val bringer = remember { BringIntoViewRequester() }
    LaunchedEffect(bring) { if (bring) { delay(80); bringer.bringIntoView() } }
    // 打勾时字跟着慢慢变灰（她："打个勾他就会灰掉"）
    val gray by animateFloatAsState(if (p.done) 1f else 0f, tween(260), label = "plan_gray")
    val date = remember(p.created) { SimpleDateFormat("yyyy/M/d", Locale.CHINA).format(Date((p.created * 1000).toLong())) }

    Box(
        Modifier.fillMaxWidth().bringIntoViewRequester(bringer).clipToBounds()
            .pointerInput(p.id) {
                detectHorizontalDragGestures(
                    onDragStart = { if (!editingS.value) onSwipeS.value(true) },
                    onDragEnd = {
                        scope.launch {
                            val open = offsetX.value < -revealPx * 0.4f
                            if (!open) onSwipeS.value(false)
                            offsetX.animateTo(if (open) -revealPx else 0f, tween(180))
                        }
                    },
                    onDragCancel = { scope.launch { onSwipeS.value(false); offsetX.animateTo(0f, tween(180)) } },
                    onHorizontalDrag = { ch, dx ->
                        if (!editingS.value) {
                            ch.consume()
                            scope.launch { offsetX.snapTo((offsetX.value + dx).coerceIn(-revealPx * 1.3f, 0f)) }
                        }
                    },
                )
            }
    ) {
        // 右边露出来的红色删除键：只画露出来那么宽，跟前景不重叠（玻璃皮半透明也不会透红）
        val shown = -offsetX.value
        if (shown > 0.5f) {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(with(density) { shown.toDp() })
                        .background(DelRed).clickable { onDelete() },
                    contentAlignment = Alignment.Center,
                ) { Text("删除", fontSize = 14.sp, color = Color.White, maxLines = 1, softWrap = false) }
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .onGloballyPositioned { rowWin.v = it.positionInWindow() }
                .pointerInput(p.id) {
                    detectTapGestures(
                        onTap = {
                            when {
                                editingS.value -> onCommitS.value()
                                anyOpenS.value -> onCloseAllS.value()
                                else -> onOpenS.value()
                            }
                        },
                        onLongPress = { o ->
                            if (!editingS.value) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongS.value(rowWin.v + o)
                            }
                        },
                    )
                }
                .padding(start = 4.dp, end = 2.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            CheckCircle(p.done, onToggle)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f).padding(top = 5.dp)) {
                if (editing) {
                    InlineTitleField(draft, onDraft, onCommitTitle)
                } else {
                    // 只有字本身那么宽接双击：点备注/空白处进详情不用等双击超时
                    Text(
                        p.title1(), fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = lerp(skin.ink, skin.muted, gray),
                        textDecoration = if (p.done) TextDecoration.LineThrough else null,
                        modifier = Modifier
                            .onGloballyPositioned { titleWin.v = it.positionInWindow() }
                            .pointerInput(p.id) {
                                detectTapGestures(
                                    onTap = { if (anyOpenS.value) onCloseAllS.value() else onOpenS.value() },
                                    onDoubleTap = { if (anyOpenS.value) onCloseAllS.value() else onEditS.value() },
                                    onLongPress = { o ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onLongS.value(titleWin.v + o)
                                    },
                                )
                            },
                    )
                }
                if (p.note.isNotBlank()) {
                    Text(
                        p.note.replace('\n', ' '), fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = skin.muted.copy(alpha = if (p.done) 0.7f else 1f), modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.padding(top = 7.dp), horizontalAlignment = Alignment.End) {
                Text(date, fontSize = 11.5.sp, lineHeight = 15.sp, color = skin.muted, maxLines = 1)
                Text(label, fontSize = 10.5.sp, lineHeight = 14.sp, color = skin.muted.copy(alpha = 0.8f), maxLines = 1, modifier = Modifier.padding(top = 3.dp))
            }
            StarButton(p.id, p.stars, fan, onStarTap, onStarSet)
        }
    }
}

/** 双击标题后就地变输入框：自动弹键盘；回车 / 失焦 = 保存（重复调用由外面按 id 挡掉） */
@Composable
private fun InlineTitleField(value: TextFieldValue, onValue: (TextFieldValue) -> Unit, onCommit: () -> Unit) {
    val skin = LocalSkin.current
    val fr = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    var had by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { fr.requestFocus(); kb?.show() }
    BasicTextField(
        value = value,
        onValueChange = { v -> onValue(if (v.text.contains('\n')) v.copy(text = v.text.replace("\n", "")) else v) },
        singleLine = true,
        cursorBrush = SolidColor(skin.ink),
        textStyle = LocalTextStyle.current.copy(color = skin.ink, fontSize = 15.sp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(fr)
            .onFocusChanged { if (it.isFocused) had = true else if (had) { had = false; onCommit() } }
            .background(skin.line.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * 星：她画的——点一下 0↔1；按住往上滑，星上面扇形弹 1/2/3，滑到哪个松手就是几星，滑出去松手不变。
 * 自己从按下就接管手指（整条的单击/长按收不到）；挪远了就放手给列表滚动 / 左滑删除。
 */
@Composable
private fun StarButton(planId: String, stars: Int, fan: StarFan, onTap: () -> Unit, onSet: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val tapS = rememberUpdatedState(onTap)
    val setS = rememberUpdatedState(onSet)
    val starsS = rememberUpdatedState(stars)
    val coords = remember { Holder<LayoutCoordinates?>(null) }
    Box(
        Modifier.size(width = 40.dp, height = 32.dp)
            .onGloballyPositioned { coords.v = it }
            .pointerInput(planId) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val slop = viewConfiguration.touchSlop
                    // 0 = 单击；1 = 放弃（挪远了 / 手指没了）；null = 按满长按时间
                    val first = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var res = 1
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) {
                                if (!c.isConsumed) { c.consume(); res = 0 }
                                break
                            }
                            if ((c.position - down.position).getDistance() > slop) break
                        }
                        res
                    }
                    when (first) {
                        0 -> tapS.value()
                        null -> {
                            val lc = coords.v
                            if (lc == null || !lc.isAttached) return@awaitEachGesture
                            val tl = lc.positionInWindow()
                            fan.open(planId, tl + Offset(size.width / 2f, size.height / 2f), starsS.value, this)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            try {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    ev.changes.forEach { it.consume() }   // 扇子开着：列表不滚、不左滑
                                    val h = fan.hit(tl + c.position, this)
                                    if (h != fan.hover) {
                                        fan.hover = h
                                        if (h != 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    if (!c.pressed) {
                                        val pick = fan.hover
                                        if (pick != 0 && pick != starsS.value) setS.value(pick)
                                        break
                                    }
                                }
                            } finally {
                                fan.close()
                            }
                        }
                        else -> Unit
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        StarGlyph(stars > 0, 19.dp)
        // 两星三星：星的右上角挂个小数字（星本身不变宽，免得日期跟着挪）
        if (stars >= 2) {
            Text("$stars", fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = StarGold,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 4.dp))
        }
    }
}

/** 扇形三个泡泡（她画的那种带小尾巴的对话泡），手指停在哪个上面那个放大变强调色；现在是几星的那个描白边 */
@Composable
private fun StarFanOverlay(fan: StarFan, rootPos: Offset) {
    if (fan.id == null) return
    val skin = LocalSkin.current
    val d = LocalDensity.current
    val half = with(d) { (FAN_BODY / 2).dp.toPx() }
    for (i in 1..3) {
        val c = fan.bubble(i, d) - rootPos
        val hovered = fan.hover == i
        val sc by animateFloatAsState(if (hovered) 1.25f else 1f, tween(110), label = "fan$i")
        val bg = if (hovered) skin.accent else skin.ink.copy(alpha = 0.9f)
        Column(
            Modifier.offset { IntOffset((c.x - half).roundToInt(), (c.y - half).roundToInt()) }
                .graphicsLayer { scaleX = sc; scaleY = sc },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val shape = RoundedCornerShape(13.dp)
            Box(
                Modifier.size(FAN_BODY.dp).shadow(5.dp, shape).background(bg, shape)
                    .then(if (i == fan.current) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.85f), shape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$i", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.width(1.dp))
                    StarGlyph(true, 10.dp, color = if (hovered) Color.White else StarGold)
                }
            }
            BubbleTail(bg)
        }
    }
}

/** 长按冒出来的小气泡（深底白字 + 朝下的小尾巴） */
@Composable
private fun TipBubble(text: String, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.shadow(6.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(skin.ink)
                .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)
        ) { Text(text, fontSize = 14.sp, color = Color.White) }
        BubbleTail(skin.ink)
    }
}

@Composable
private fun BubbleTail(color: Color) {
    Canvas(Modifier.size(width = 14.dp, height = 7.dp)) {
        val path = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close() }
        drawPath(path, color)
    }
}

/** 五角星：实心 = 金色；空心 = 细描边 */
@Composable
private fun StarGlyph(filled: Boolean, size: Dp, color: Color = StarGold, outline: Color = LocalSkin.current.muted) {
    Canvas(Modifier.size(size)) {
        val path = starPath(this.size)
        if (filled) drawPath(path, color)
        else drawPath(path, outline, style = Stroke(width = 1.4.dp.toPx(), join = StrokeJoin.Round))
    }
}

private fun starPath(s: Size): Path {
    val cx = s.width / 2f
    val cy = s.height * 0.54f
    val outer = s.minDimension * 0.5f * 0.92f
    val inner = outer * 0.45f
    return Path().apply {
        for (i in 0 until 10) {
            val a = -Math.PI / 2 + i * Math.PI / 5
            val rad = if (i % 2 == 0) outer else inner
            val x = cx + (rad * cos(a)).toFloat()
            val y = cy + (rad * sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/**
 * 详情页（单击一条进来）：标题（可改）、写下日期 + 谁加的、完成状态、星（三颗点着选）、放在哪栏（可挪）、
 * 具体内容 = 便签（多行，想写多少写多少）。返回 / 右上「保存」才发；没存上留在页里，再按返回就把字先存手机里走。
 */
@Composable
private fun PlanDetail(
    p: Plan?,
    onBack: () -> Unit,
    onToggle: (Plan) -> Unit,
    onStars: (Plan, Int) -> Unit,
    onTab: (Plan, String) -> Unit,
    onSave: (Plan, String, String, (Boolean) -> Unit) -> Unit,
) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    if (p == null) {
        BackHandler { onBack() }
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            TextButton(onClick = onBack) { Text("← 计划清单", color = skin.muted) }
            Spacer(Modifier.height(12.dp))
            Text("这条已经删掉了", fontSize = 15.sp, color = skin.muted)
        }
        return
    }
    val saved = remember(p.id) { PlanDraft.details.remove(p.id) }
    var title by remember(p.id) { mutableStateOf(saved?.first ?: p.title1()) }
    var note by remember(p.id) { mutableStateOf(saved?.second ?: p.note) }
    // 辰那头同时改了：我这边没动过的字跟着换成新的，动过的不覆盖
    var baseTitle by remember(p.id) { mutableStateOf(p.title1()) }
    var baseNote by remember(p.id) { mutableStateOf(p.note) }
    LaunchedEffect(p.text) { if (title == baseTitle) title = p.title1(); baseTitle = p.title1() }
    LaunchedEffect(p.note) { if (note == baseNote) note = p.note; baseNote = p.note }

    val tNorm = title.trim()
    val dirty = (tNorm.isNotEmpty() && tNorm != p.title1().trim()) || note.trim() != p.note
    var saving by remember { mutableStateOf(false) }
    var failedOnce by remember { mutableStateOf(false) }
    fun save(then: (() -> Unit)? = null) {
        if (saving) return
        saving = true
        onSave(p, title, note) { ok ->
            saving = false
            if (ok) { failedOnce = false; then?.invoke() } else failedOnce = true
        }
    }
    fun leave() {
        when {
            !dirty -> onBack()
            saving -> Unit
            failedOnce -> {
                PlanDraft.details[p.id] = title to note
                Toast.makeText(ctx, "没存上的字先留在手机里，下次点进这条还在", Toast.LENGTH_SHORT).show()
                onBack()
            }
            else -> save { onBack() }
        }
    }
    BackHandler { leave() }
    val fmt = remember { SimpleDateFormat("yyyy/M/d", Locale.CHINA) }
    val fmtShort = remember { SimpleDateFormat("M/d HH:mm", Locale.CHINA) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { leave() }) { Text("← 计划清单", color = skin.muted) }
            Spacer(Modifier.weight(1f))
            if (dirty || saving) {
                TextButton(enabled = !saving, onClick = { save() }) { Text(if (saving) "保存中…" else "保存", color = skin.accent) }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = title, onValueChange = { title = it.replace("\n", "") },
                maxLines = 4, cursorBrush = SolidColor(skin.ink),
                textStyle = LocalTextStyle.current.copy(color = skin.ink, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner -> Box { if (title.isEmpty()) Text("标题", fontSize = 21.sp, color = skin.muted); inner() } },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${fmt.format(Date((p.created * 1000).toLong()))} 写下 · ${if (p.isChen) "辰加的" else "我加的"}",
                fontSize = 12.5.sp, color = skin.muted,
            )
            Spacer(Modifier.height(18.dp))

            // 完成状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                CheckCircle(p.done) { onToggle(p) }
                Spacer(Modifier.width(6.dp))
                Text(
                    if (p.done) "做完了 · ${if (p.doneAt > 0) fmtShort.format(Date((p.doneAt * 1000).toLong())) + " " else ""}${if (p.doneBy == "chen") "辰勾的" else "我勾的"}" else "还没做",
                    fontSize = 14.sp, color = if (p.done) skin.muted else skin.ink,
                )
            }
            Spacer(Modifier.height(10.dp))

            // 星：点第几颗就是几星，点当前那颗 = 清零
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("星星", fontSize = 14.sp, color = skin.muted, modifier = Modifier.width(52.dp))
                for (k in 1..3) {
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { onStars(p, if (p.stars == k) 0 else k) }, contentAlignment = Alignment.Center) {
                        StarGlyph(k <= p.stars, 24.dp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // 放在哪栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("放在", fontSize = 14.sp, color = skin.muted, modifier = Modifier.width(52.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Plan.TABS.forEach { (k, name) ->
                        val sel = p.tab == k
                        Box(
                            Modifier.height(34.dp).then(if (sel) Modifier.sunken(10.dp) else Modifier.pressable(10.dp) { onTab(p, k) })
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(name, fontSize = 13.sp, maxLines = 1, color = if (sel) skin.ink else skin.muted,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))

            // 具体内容 = 便签
            Text("具体内容", fontSize = 13.sp, color = skin.muted, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().sunken(16.dp).padding(horizontal = 16.dp, vertical = 14.dp)) {
                BasicTextField(
                    value = note, onValueChange = { note = it },
                    cursorBrush = SolidColor(skin.ink),
                    textStyle = LocalTextStyle.current.copy(color = skin.ink, fontSize = 15.sp, lineHeight = 23.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    decorationBox = { inner ->
                        Box {
                            if (note.isEmpty()) Text("像便签一样，想写多少写多少…", fontSize = 15.sp, color = skin.muted)
                            inner()
                        }
                    },
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 圆圈：没做 = 空心细圈；勾上 = 从中心填满强调色，白勾一笔画出来（小小的，别太花）——0.128 原样 */
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
    Box(Modifier.fillMaxWidth().padding(start = 40.dp, end = 14.dp).height(0.5.dp).background(LocalSkin.current.line))
}
