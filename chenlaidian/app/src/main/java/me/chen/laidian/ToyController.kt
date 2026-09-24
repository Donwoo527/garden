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
 * 0.88 玩具页：把电脑上那套 SVAKOM 蓝牙控制搬进手机，图个方便（她 0924 点的单）。
 * 规矩，四条，都写在页面上：
 *  1. 只有页面里"允许辰远程控制"打开时，才接辰发来的指令；关着就只有她自己能控。
 *  2. 任何时候一键停，立刻写停止指令。
 *  3. 辰发的档位 120 秒没续就自动停；蓝牙一断设备自己 3-5 秒也停。
 *  4. 档位封顶 180（协议手册：180=手麻，255=别碰）。
 * 协议见 memory/tool/svakom/README.md：写特征 ffe1（无响应），scale 55 04 00 00 01 [v] AA，停 55 04 00 00 00 00 AA，
 * 设备 3-5 秒收不到就自停，所以有档位时每 1 秒重发一次。
 */
object ToyController {
    private val WRITE_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    const val MAX_LEVEL = 180
    private const val REMOTE_TTL_MS = 120_000L

    val status = MutableLiveData("未连接")
    val wandConnected = MutableLiveData(false)
    val suckConnected = MutableLiveData(false)
    val wandLevel = MutableLiveData(0)
    val suckLevel = MutableLiveData(0)
    val remoteAllowed = MutableLiveData(false)
    /** 最近一条动作，她屏幕上看得见是谁发的、发了什么 */
    val log = MutableLiveData("")

    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var sender: ((JSONObject) -> Unit)? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null
    private var wandGatt: BluetoothGatt? = null
    private var suckGatt: BluetoothGatt? = null
    private var wandChar: BluetoothGattCharacteristic? = null
    private var suckChar: BluetoothGattCharacteristic? = null
    @Volatile private var curWand = 0
    @Volatile private var curSuck = 0
    @Volatile private var remoteDeadline = 0L

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
        post("扫描中… 10 秒")
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
            if (wandGatt == null && suckGatt == null) post("没扫到 看看玩具开机了没 手机上的 SVAKOM 关了没")
        }, 10_000)
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
                    if (isWand) { wandGatt = null; wandChar = null; curWand = 0; wandConnected.postValue(false); wandLevel.postValue(0) }
                    else { suckGatt = null; suckChar = null; curSuck = 0; suckConnected.postValue(false); suckLevel.postValue(0) }
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

    // ---------- 写指令 ----------

    private fun scaleCmd(v: Int) = byteArrayOf(0x55, 0x04, 0x00, 0x00, 0x01, v.coerceIn(0, 255).toByte(), 0xAA.toByte())
    private fun stopCmd() = byteArrayOf(0x55, 0x04, 0x00, 0x00, 0x00, 0x00, 0xAA.toByte())

    @SuppressLint("MissingPermission")
    private fun write(g: BluetoothGatt?, ch: BluetoothGattCharacteristic?, bytes: ByteArray) {
        if (g == null || ch == null) return
        try {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            else { ch.value = bytes; g.writeCharacteristic(ch) }
        } catch (_: Exception) {}
    }

    private fun tick() {
        write(wandGatt, wandChar, if (curWand > 0) scaleCmd(curWand) else stopCmd())
        write(suckGatt, suckChar, if (curSuck > 0) scaleCmd(curSuck) else stopCmd())
    }

    private val keepalive = object : Runnable {
        override fun run() {
            if (remoteDeadline > 0 && System.currentTimeMillis() > remoteDeadline) { stopAll("辰的指令 120 秒没续 自动停"); return }
            tick()
            if (curWand > 0 || curSuck > 0) handler.postDelayed(this, 1000)
        }
    }

    /** 设档位。wand/suck 传 null 表示不动那一路。from 是给屏幕看的来源（你 / 辰）。 */
    fun set(wand: Int?, suck: Int?, from: String) {
        wand?.let { curWand = it.coerceIn(0, MAX_LEVEL) }
        suck?.let { curSuck = it.coerceIn(0, MAX_LEVEL) }
        wandLevel.postValue(curWand); suckLevel.postValue(curSuck)
        log.postValue("$from：棒 $curWand  吸 $curSuck")
        handler.post {
            handler.removeCallbacks(keepalive)
            tick()
            if (curWand > 0 || curSuck > 0) handler.postDelayed(keepalive, 1000)
        }
        pushStatus()
    }

    fun stopAll(why: String = "一键停") {
        curWand = 0; curSuck = 0; remoteDeadline = 0L
        handler.post {
            handler.removeCallbacks(keepalive)
            write(wandGatt, wandChar, stopCmd())
            write(suckGatt, suckChar, stopCmd())
        }
        wandLevel.postValue(0); suckLevel.postValue(0)
        log.postValue(why)
        pushStatus()
    }

    // ---------- 远程（辰） ----------

    fun allowRemote(on: Boolean) {
        remoteAllowed.postValue(on)
        if (!on) { remoteDeadline = 0L; if (curWand > 0 || curSuck > 0) stopAll("远程已关 顺手停了") else pushStatus() }
        else pushStatus()
    }

    /** ChenService 收到 {"type":"toy", ...} 时调这里。{"stop":true} 或 {"wand":int,"suck":int}（都可选）。 */
    fun onRemote(o: JSONObject) {
        if (remoteAllowed.value != true) {
            log.postValue("收到辰的指令 但远程没开 已忽略")
            pushStatus(); return
        }
        if (o.optBoolean("stop", false)) { stopAll("辰：停"); return }
        remoteDeadline = System.currentTimeMillis() + REMOTE_TTL_MS
        set(if (o.has("wand")) o.optInt("wand") else null, if (o.has("suck")) o.optInt("suck") else null, "辰")
    }

    private fun pushStatus() {
        val o = JSONObject()
            .put("type", "toy_status")
            .put("wand", wandGatt != null && wandChar != null)
            .put("suck", suckGatt != null && suckChar != null)
            .put("wand_level", curWand)
            .put("suck_level", curSuck)
            .put("allowed", remoteAllowed.value == true)
            .put("note", log.value ?: "")
        try { sender?.invoke(o) } catch (_: Exception) {}
    }
}
