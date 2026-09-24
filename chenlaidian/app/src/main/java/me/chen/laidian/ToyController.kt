package me.chen.laidian

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import org.json.JSONObject
import java.util.UUID

/**
 * 玩具页（0.88 起，0.89 照司沃康 app 补齐功能）：手机直接连 SVAKOM 蓝牙，图方便，不用再开电脑。
 * 规矩，写在页面上：
 *  1. 只有"允许辰远程控制"打开时才接辰的指令；关着就只有她自己能控。
 *  2. 任何时候一键停：所有马达停、加热关。
 *  3. 辰发的档位 120 秒没续自动停；蓝牙一断设备自己 3-5 秒也停。
 *  4. 强度封顶 180（协议手册：180=手麻，255=别碰）。
 * 协议见 memory/tool/svakom/README.md（写特征 ffe1 无响应，设备 3-5 秒收不到就自停 → 有动作时每 1 秒重发）：
 *  联动强度 55 04 00 00 01 [v] AA（棒=伸缩+转珠+拍打联动；吸=吮吸强度）｜独立伸缩 55 08 00 00 [0-7] 00 00｜独立拍打 55 07 00 00 [0-7] 00 00
 *  振动 55 03 00 00 [mode 1-10] [level 1-10] 00｜加热 55 05 01 37 00 00 00 / 关 55 05 00 00 00 00 00
 */
object ToyController {
    private val WRITE_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    const val MAX_LEVEL = 180
    private const val REMOTE_TTL_MS = 120_000L

    val status = MutableLiveData("未连接")
    val wandConnected = MutableLiveData(false)
    val suckConnected = MutableLiveData(false)
    val remoteAllowed = MutableLiveData(false)
    /** 最近一条动作，她屏幕上看得见是谁发的、发了什么 */
    val log = MutableLiveData("")
    // 各路当前值（给页面显示）
    val wandLevel = MutableLiveData(0)     // 棒联动强度 0-180
    val suckLevel = MutableLiveData(0)     // 吸强度 0-180
    val stretchLevel = MutableLiveData(0)  // 伸缩 0-7
    val patLevel = MutableLiveData(0)      // 拍打 0-7
    val vibMode = MutableLiveData(0)       // 振动模式 0=关 1-10
    val vibLevel = MutableLiveData(5)      // 振动强度 1-10
    val heatWand = MutableLiveData(false)
    val heatSuck = MutableLiveData(false)

    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var sender: ((JSONObject) -> Unit)? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null
    private var wandGatt: BluetoothGatt? = null
    private var suckGatt: BluetoothGatt? = null
    private var wandChar: BluetoothGattCharacteristic? = null
    private var suckChar: BluetoothGattCharacteristic? = null

    @Volatile private var cWand = 0
    @Volatile private var cSuck = 0
    @Volatile private var cStretch = 0
    @Volatile private var cPat = 0
    @Volatile private var cVibMode = 0
    @Volatile private var cVibLevel = 5
    @Volatile private var cHeatWand = false
    @Volatile private var cHeatSuck = false
    @Volatile private var remoteDeadline = 0L
    @Volatile private var lastNote = ""

    fun init(ctx: Context, send: (JSONObject) -> Unit) {
        app = ctx.applicationContext
        sender = send
    }

    fun needed(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(ctx: Context): Boolean =
        needed().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    private fun post(s: String) = status.postValue(s)
    private fun note(s: String) { lastNote = s; log.postValue(s) }

    // ---------- 扫描 / 连接 ----------

    @SuppressLint("MissingPermission")
    fun scan() {
        val ctx = app ?: return
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) { post("这手机没有蓝牙"); return }
        if (!adapter.isEnabled) { post("蓝牙没开 先去开一下"); return }
        if (!hasPermissions(ctx)) { post("没给蓝牙权限"); return }
        stopScan()
        scanner = adapter.bluetoothLeScanner
        post("扫描中… 15 秒")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                when {
                    name.contains("SL278H") && wandGatt == null -> connect(result.device, isWand = true)
                    name.contains("SL278J") && suckGatt == null -> connect(result.device, isWand = false)
                }
            }
            override fun onScanFailed(errorCode: Int) { post("扫描失败 $errorCode") }
        }
        scanCb = cb
        scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        handler.postDelayed({
            stopScan()
            if (wandGatt == null && suckGatt == null) post("没扫到 看看玩具开机了没（灯亮） 手机上的 SVAKOM 关了没")
            else if (suckGatt == null) post("只连到棒 吸的没在广播（充电/开机看灯）")
            else if (wandGatt == null) post("只连到吸 棒没在广播")
        }, 15_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanCb?.let { try { scanner?.stopScan(it) } catch (_: Exception) {} }
        scanCb = null
    }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice, isWand: Boolean) {
        val ctx = app ?: return
        val label = if (isWand) "棒" else "吸"
        post("连接$label…")
        val gatt = dev.connectGatt(ctx, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    try { g.close() } catch (_: Exception) {}
                    if (isWand) { wandGatt = null; wandChar = null; wandConnected.postValue(false); clearWand() }
                    else { suckGatt = null; suckChar = null; suckConnected.postValue(false); clearSuck() }
                    post("$label 断开了")
                    pushStatus()
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val ch = g.services.flatMap { it.characteristics }.firstOrNull { it.uuid == WRITE_UUID }
                if (ch == null) { post("$label 没找到写入口"); g.disconnect(); return }
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                if (isWand) { wandChar = ch; wandConnected.postValue(true) } else { suckChar = ch; suckConnected.postValue(true) }
                post("$label 已连接")
                pushStatus()
            }
        }, BluetoothDevice.TRANSPORT_LE)
        if (isWand) wandGatt = gatt else suckGatt = gatt
    }

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        stopAll("已断开")
        stopScan()
        try { wandGatt?.disconnect() } catch (_: Exception) {}
        try { suckGatt?.disconnect() } catch (_: Exception) {}
        post("未连接")
    }

    private fun clearWand() { cWand = 0; cStretch = 0; cPat = 0; cVibMode = 0; cHeatWand = false
        wandLevel.postValue(0); stretchLevel.postValue(0); patLevel.postValue(0); vibMode.postValue(0); heatWand.postValue(false) }
    private fun clearSuck() { cSuck = 0; cHeatSuck = false; suckLevel.postValue(0); heatSuck.postValue(false) }

    // ---------- 指令 ----------

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun scaleCmd(v: Int) = b(0x55, 0x04, 0x00, 0x00, 0x01, v.coerceIn(0, 255), 0xAA)
    private fun scaleStop() = b(0x55, 0x04, 0x00, 0x00, 0x00, 0x00, 0xAA)
    private fun stretchCmd(s: Int) = b(0x55, 0x08, 0x00, 0x00, s.coerceIn(0, 7), 0x00, 0x00)
    private fun patCmd(s: Int) = b(0x55, 0x07, 0x00, 0x00, s.coerceIn(0, 7), 0x00, 0x00)
    private fun vibCmd(mode: Int, level: Int) = b(0x55, 0x03, 0x00, 0x00, mode.coerceIn(1, 10), level.coerceIn(1, 10), 0x00)
    private fun vibStop() = b(0x55, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00)
    private fun heatOn() = b(0x55, 0x05, 0x01, 0x37, 0x00, 0x00, 0x00)
    private fun heatOff() = b(0x55, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00)

    @SuppressLint("MissingPermission")
    private fun write(g: BluetoothGatt?, ch: BluetoothGattCharacteristic?, bytes: ByteArray) {
        if (g == null || ch == null) return
        try {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            else { ch.value = bytes; g.writeCharacteristic(ch) }
        } catch (_: Exception) {}
    }
    private fun wWand(bytes: ByteArray) = write(wandGatt, wandChar, bytes)
    private fun wSuck(bytes: ByteArray) = write(suckGatt, suckChar, bytes)

    private fun active() = cWand > 0 || cSuck > 0 || cStretch > 0 || cPat > 0 || cVibMode > 0

    /** 每秒重发一遍当前所有动作（设备 3-5 秒收不到就自停） */
    private fun tick() {
        if (cWand > 0) wWand(scaleCmd(cWand))
        if (cStretch > 0) wWand(stretchCmd(cStretch))
        if (cPat > 0) wWand(patCmd(cPat))
        if (cVibMode > 0) wWand(vibCmd(cVibMode, cVibLevel))
        if (cSuck > 0) wSuck(scaleCmd(cSuck))
    }

    private val keepalive = object : Runnable {
        override fun run() {
            if (remoteDeadline > 0 && System.currentTimeMillis() > remoteDeadline) { stopAll("辰的指令 120 秒没续 自动停"); return }
            tick()
            if (active()) handler.postDelayed(this, 1000)
        }
    }

    private fun restartLoop() {
        handler.post {
            handler.removeCallbacks(keepalive)
            tick()
            if (active()) handler.postDelayed(keepalive, 1000)
        }
    }

    /**
     * 统一入口。传 null 的项不动。from 是给屏幕看的来源（你 / 辰）。
     * 某一路调成 0 时立刻发那一路的停止指令。
     */
    fun apply(from: String, wand: Int? = null, suck: Int? = null, stretch: Int? = null, pat: Int? = null,
              vibModeV: Int? = null, vibLevelV: Int? = null, heatW: Boolean? = null, heatS: Boolean? = null) {
        val parts = ArrayList<String>()
        wand?.let { cWand = it.coerceIn(0, MAX_LEVEL); wandLevel.postValue(cWand); parts += "联动$cWand"; if (cWand == 0) handler.post { wWand(scaleStop()) } }
        suck?.let { cSuck = it.coerceIn(0, MAX_LEVEL); suckLevel.postValue(cSuck); parts += "吸$cSuck"; if (cSuck == 0) handler.post { wSuck(scaleStop()) } }
        stretch?.let { cStretch = it.coerceIn(0, 7); stretchLevel.postValue(cStretch); parts += "伸缩$cStretch"; if (cStretch == 0) handler.post { wWand(stretchCmd(0)) } }
        pat?.let { cPat = it.coerceIn(0, 7); patLevel.postValue(cPat); parts += "拍打$cPat"; if (cPat == 0) handler.post { wWand(patCmd(0)) } }
        vibLevelV?.let { cVibLevel = it.coerceIn(1, 10); vibLevel.postValue(cVibLevel) }
        vibModeV?.let { cVibMode = it.coerceIn(0, 10); vibMode.postValue(cVibMode); parts += if (cVibMode == 0) "振动关" else "振动模式$cVibMode×$cVibLevel"; if (cVibMode == 0) handler.post { wWand(vibStop()) } }
        if (vibModeV == null && vibLevelV != null && cVibMode > 0) parts += "振动强度$cVibLevel"
        heatW?.let { cHeatWand = it; heatWand.postValue(it); parts += if (it) "棒加热开" else "棒加热关"; handler.post { wWand(if (it) heatOn() else heatOff()) } }
        heatS?.let { cHeatSuck = it; heatSuck.postValue(it); parts += if (it) "吸加热开" else "吸加热关"; handler.post { wSuck(if (it) heatOn() else heatOff()) } }
        note("$from：" + parts.joinToString(" "))
        restartLoop()
        pushStatus()
    }

    fun stopAll(why: String = "一键停") {
        cWand = 0; cSuck = 0; cStretch = 0; cPat = 0; cVibMode = 0; cHeatWand = false; cHeatSuck = false; remoteDeadline = 0L
        handler.post {
            handler.removeCallbacks(keepalive)
            wWand(scaleStop()); wWand(vibStop()); wWand(stretchCmd(0)); wWand(patCmd(0)); wWand(heatOff())
            wSuck(scaleStop()); wSuck(heatOff())
        }
        wandLevel.postValue(0); suckLevel.postValue(0); stretchLevel.postValue(0); patLevel.postValue(0); vibMode.postValue(0)
        heatWand.postValue(false); heatSuck.postValue(false)
        note(why)
        pushStatus()
    }

    // ---------- 远程（辰） ----------

    fun allowRemote(on: Boolean) {
        remoteAllowed.postValue(on)
        if (!on) { remoteDeadline = 0L; if (active() || cHeatWand || cHeatSuck) stopAll("远程已关 顺手停了") else pushStatus() }
        else pushStatus()
    }

    /**
     * ChenService 收到 {"type":"toy", ...} 时调这里。
     * 字段都可选：stop / wand / suck / stretch / pat / vib_mode / vib_level / heat_wand / heat_suck
     */
    fun onRemote(o: JSONObject) {
        if (remoteAllowed.value != true) {
            note("收到辰的指令 但远程没开 已忽略")
            pushStatus(); return
        }
        if (o.optBoolean("stop", false)) { stopAll("辰：停"); return }
        remoteDeadline = System.currentTimeMillis() + REMOTE_TTL_MS
        fun i(k: String) = if (o.has(k)) o.optInt(k) else null
        fun bo(k: String) = if (o.has(k)) o.optBoolean(k) else null
        apply("辰", wand = i("wand"), suck = i("suck"), stretch = i("stretch"), pat = i("pat"),
            vibModeV = i("vib_mode"), vibLevelV = i("vib_level"), heatW = bo("heat_wand"), heatS = bo("heat_suck"))
    }

    fun pushStatus() {
        val o = JSONObject()
            .put("type", "toy_status")
            .put("wand", wandGatt != null && wandChar != null)
            .put("suck", suckGatt != null && suckChar != null)
            .put("wand_level", cWand).put("suck_level", cSuck)
            .put("stretch", cStretch).put("pat", cPat)
            .put("vib_mode", cVibMode).put("vib_level", cVibLevel)
            .put("heat_wand", cHeatWand).put("heat_suck", cHeatSuck)
            .put("allowed", remoteAllowed.value == true)
            .put("note", lastNote)
        try { sender?.invoke(o) } catch (_: Exception) {}
    }
}
