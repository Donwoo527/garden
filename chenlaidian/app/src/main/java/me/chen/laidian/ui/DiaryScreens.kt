package me.chen.laidian.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.chen.laidian.Tls
import me.chen.laidian.net.ChatClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0928 她报的：日记在 app 里点不开——百宝箱「日记本」「小陈的日记」两张卡之前只弹 Toast「下一版」。
 * 网页版接口早就有，这个文件负责把它们接进 app：
 *   - 辰的日记（/api/diary + /api/read）只读
 *   - 小陈的日记（/api/my_diary 系列）能增删改
 * 只加这一个文件 + MainScreen.ToolsScreen 那几行，不碰 ChatApi/ChatClient——同时有人在改别的页面。
 */

// ---------- 网络：照 ChatApi.kt 的写法自己包一份，避免碰 ChatApi.kt ----------
private object DiaryApi {
    private const val TOKEN = "chen_home_2026"
    @Volatile private var client: OkHttpClient? = null
    private fun http(ctx: Context): OkHttpClient = client ?: Tls.client(ctx.applicationContext).also { client = it }

    data class DiaryFile(val name: String, val mtime: Double)
    data class MyEntry(val name: String, val content: String, val mtime: Double)

    /** GET /api/diary，服务端已按 mtime 倒序（新的在前） */
    fun listChen(ctx: Context): List<DiaryFile>? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/api/diary").header("X-Token", TOKEN).get().build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val arr = JSONObject(r.body?.string() ?: return null).optJSONArray("files") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { DiaryFile(it.optString("name", ""), it.optDouble("mtime", 0.0)) } }
            }
        } catch (e: Exception) { null }
    }

    /** POST /api/read，读一篇辰的日记正文 */
    fun readChen(ctx: Context, name: String): String? {
        val body = JSONObject().put("path", "diary/$name")
        val req = Request.Builder().url(ChatClient.baseUrl() + "/api/read").header("X-Token", TOKEN)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                JSONObject(r.body?.string() ?: return null).optString("content", "")
            }
        } catch (e: Exception) { null }
    }

    /** GET /api/my_diary，正文随列表直接带回来；接口没保证顺序，客户端自己按 mtime 倒序排 */
    fun listMine(ctx: Context): List<MyEntry>? {
        val req = Request.Builder().url(ChatClient.baseUrl() + "/api/my_diary").header("X-Token", TOKEN).get().build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val arr = JSONObject(r.body?.string() ?: return null).optJSONArray("entries") ?: return emptyList()
                (0 until arr.length())
                    .mapNotNull { i -> arr.optJSONObject(i)?.let { MyEntry(it.optString("name", ""), it.optString("content", ""), it.optDouble("mtime", 0.0)) } }
                    .sortedByDescending { it.mtime }
            }
        } catch (e: Exception) { null }
    }

    /** POST /api/my_diary，新建或覆盖同名；name 空串=让服务端起名。成功返回真正落盘的名字 */
    fun save(ctx: Context, name: String, content: String): String? {
        val body = JSONObject().put("name", name).put("content", content)
        val req = Request.Builder().url(ChatClient.baseUrl() + "/api/my_diary").header("X-Token", TOKEN)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return try {
            http(ctx).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val o = JSONObject(r.body?.string() ?: return null)
                if (!o.optBoolean("ok", false)) return null
                o.optString("name", name)
            }
        } catch (e: Exception) { null }
    }

    /** POST /api/my_diary/delete */
    fun delete(ctx: Context, name: String): Boolean {
        val body = JSONObject().put("name", name)
        val req = Request.Builder().url(ChatClient.baseUrl() + "/api/my_diary/delete").header("X-Token", TOKEN)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return try {
            http(ctx).newCall(req).execute().use { r -> r.isSuccessful && JSONObject(r.body?.string() ?: "{}").optBoolean("ok", false) }
        } catch (e: Exception) { false }
    }
}

/** 列表/正文的加载态三态，页面照这个显示提示文字 */
private sealed class Load<out T> {
    object Loading : Load<Nothing>()
    object Failed : Load<Nothing>()
    data class Ok<T>(val value: T) : Load<T>()
}

private fun formatTime(mtime: Double): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date((mtime * 1000).toLong()))

/** 加载中/失败/空列表共用的提示行 */
@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 14.sp, color = LocalSkin.current.muted, modifier = Modifier.padding(24.dp))
}

// ==================== 辰的日记（只读）====================

/**
 * 百宝箱→日记本。列表（文件名去 .md + 时间，新的在上）点一条进阅读页；阅读页标题+正文，只读。
 * 返回键：阅读页先退回列表，列表页才真正退出（onBack）。
 */
@Composable
fun ChenDiaryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val skin = LocalSkin.current
    var list by remember { mutableStateOf<Load<List<DiaryApi.DiaryFile>>>(Load.Loading) }
    var selected by remember { mutableStateOf<DiaryApi.DiaryFile?>(null) }
    var content by remember { mutableStateOf<Load<String>>(Load.Loading) }

    LaunchedEffect(Unit) {
        list = withContext(Dispatchers.IO) { DiaryApi.listChen(ctx)?.let { Load.Ok(it) } ?: Load.Failed }
    }
    LaunchedEffect(selected) {
        val f = selected ?: return@LaunchedEffect
        content = Load.Loading
        content = withContext(Dispatchers.IO) { DiaryApi.readChen(ctx, f.name)?.let { Load.Ok(it) } ?: Load.Failed }
    }
    BackHandler(onBack = { if (selected != null) selected = null else onBack() })

    Column(Modifier.fillMaxSize().background(skin.bg)) {
        val cur = selected
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (cur != null) selected = null else onBack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = skin.ink)
            }
            Text(
                cur?.name?.removeSuffix(".md") ?: "辰的日记",
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = skin.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (cur == null) {
            when (val l = list) {
                is Load.Loading -> Hint("加载中…")
                is Load.Failed -> Hint("没读到 服务没开或者网络断了")
                is Load.Ok -> if (l.value.isEmpty()) Hint("还没有日记") else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(l.value, key = { it.name }) { f ->
                            WhiteCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Column(Modifier.fillMaxWidth().clickable { selected = f }.padding(14.dp)) {
                                    Text(f.name.removeSuffix(".md"), fontSize = 15.sp, color = skin.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(4.dp))
                                    Text(formatTime(f.mtime), fontSize = 12.sp, color = skin.muted)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            when (val c = content) {
                is Load.Loading -> Hint("加载中…")
                is Load.Failed -> Hint("没读到 服务没开或者网络断了")
                is Load.Ok -> Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(formatTime(cur.mtime), fontSize = 12.sp, color = skin.muted)
                    Spacer(Modifier.height(10.dp))
                    Text(c.value, fontSize = 15.sp, lineHeight = 24.sp, color = skin.ink)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

// ==================== 小陈的日记（能增删改）====================

private sealed class MyView {
    object ListView : MyView()
    data class Read(val entry: DiaryApi.MyEntry) : MyView()
    data class Edit(val entry: DiaryApi.MyEntry?) : MyView()   // entry=null 是新建
}

/**
 * 百宝箱→小陈的日记。列表右上「写日记」；点一条进阅读页，阅读页里能编辑/删除。
 * 返回键：阅读/编辑页先退回列表，列表页才真正退出（onBack）。
 */
@Composable
fun XiaochenDiaryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val skin = LocalSkin.current
    var entries by remember { mutableStateOf<Load<List<DiaryApi.MyEntry>>>(Load.Loading) }
    var view by remember { mutableStateOf<MyView>(MyView.ListView) }
    // 保存/删除成功后 ++ 触发重新拉列表
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        entries = Load.Loading
        entries = withContext(Dispatchers.IO) { DiaryApi.listMine(ctx)?.let { Load.Ok(it) } ?: Load.Failed }
    }
    BackHandler(onBack = { if (view is MyView.ListView) onBack() else view = MyView.ListView })

    Column(Modifier.fillMaxSize().background(skin.bg)) {
        when (val v = view) {
            is MyView.ListView -> {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = skin.ink) }
                    Text("小陈的日记", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = skin.ink, modifier = Modifier.weight(1f))
                    Text("写日记", fontSize = 14.sp, color = skin.accent, modifier = Modifier.clickable { view = MyView.Edit(null) })
                }
                when (val l = entries) {
                    is Load.Loading -> Hint("加载中…")
                    is Load.Failed -> Hint("没读到 服务没开或者网络断了")
                    is Load.Ok -> if (l.value.isEmpty()) Hint("还没写过 点右上角写一篇") else {
                        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                            items(l.value, key = { it.name }) { e ->
                                WhiteCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    Column(Modifier.fillMaxWidth().clickable { view = MyView.Read(e) }.padding(14.dp)) {
                                        Text(e.name.removeSuffix(".md"), fontSize = 15.sp, color = skin.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(4.dp))
                                        Text(formatTime(e.mtime), fontSize = 12.sp, color = skin.muted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            is MyView.Read -> MyDiaryReadPage(
                entry = v.entry,
                onEdit = { view = MyView.Edit(v.entry) },
                onBack = { view = MyView.ListView },
                onDeleted = { view = MyView.ListView; refreshKey++ },
            )
            is MyView.Edit -> MyDiaryEditPage(
                entry = v.entry,
                onBack = { view = MyView.ListView },
                onSaved = { view = MyView.ListView; refreshKey++ },
            )
        }
    }
}

/** 小陈日记的阅读页：标题 + 正文（内容已经带在 entry 里，不用再请求）+ 编辑/删除 */
@Composable
private fun MyDiaryReadPage(entry: DiaryApi.MyEntry, onBack: () -> Unit, onEdit: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    var showDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = skin.ink) }
            Text(
                entry.name.removeSuffix(".md"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = skin.ink,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text("编辑", fontSize = 14.sp, color = skin.accent, modifier = Modifier.clickable(enabled = !deleting) { onEdit() })
            Spacer(Modifier.width(12.dp))
            Text("删除", fontSize = 14.sp, color = skin.muted, modifier = Modifier.clickable(enabled = !deleting) { showDelete = true })
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(formatTime(entry.mtime), fontSize = 12.sp, color = skin.muted)
            Spacer(Modifier.height(10.dp))
            Text(entry.content, fontSize = 15.sp, lineHeight = 24.sp, color = skin.ink)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDelete = false },
            title = { Text("删掉这篇？") },
            text = { Text("「${entry.name.removeSuffix(".md")}」删掉就找不回来了") },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { DiaryApi.delete(ctx, entry.name) }
                        deleting = false
                        showDelete = false
                        if (ok) onDeleted() else Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = { showDelete = false }) { Text("算了") } },
        )
    }
}

/** 小陈日记的写/改页：entry=null 是新建。标题可空；保存时若是编辑已有的，name 用原文件名（覆盖），不跟着标题改名 */
@Composable
private fun MyDiaryEditPage(entry: DiaryApi.MyEntry?, onBack: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    var title by remember(entry) { mutableStateOf(entry?.name?.removeSuffix(".md") ?: "") }
    var text by remember(entry) { mutableStateOf(entry?.content ?: "") }
    var saving by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !saving) { Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = skin.ink) }
            Text(if (entry == null) "写日记" else "编辑日记", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = skin.ink)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                // 已存的日记服务端没有改名接口：编辑时标题只读 免得她改了字以为改了名
                readOnly = entry != null,
                label = { Text(if (entry == null) "标题（可不填）" else "标题（已存的不能改名）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("写点什么") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    // 编辑已有的：name 用原文件名覆盖，标题框改了也不重命名（服务端没有单独的改名接口）
                    // 新建：标题去空格、去 / \ ，没 .md 补上；空标题传空串给服务端起名
                    val fileName = entry?.name ?: title.trim().replace("/", "").replace("\\", "").let {
                        if (it.isBlank()) "" else if (it.endsWith(".md")) it else "$it.md"
                    }
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { DiaryApi.save(ctx, fileName, text) }
                        saving = false
                        if (saved != null) { Toast.makeText(ctx, "存好了", Toast.LENGTH_SHORT).show(); onSaved() }
                        else Toast.makeText(ctx, "保存失败", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "保存中…" else "保存") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
