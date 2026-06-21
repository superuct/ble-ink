package com.epd.nrf5.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID

class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
    }

    interface Callback {
        fun onDeviceFound(name: String, address: String, rssi: Int)
        fun onConnected(name: String)
        fun onDisconnected()
        fun onServiceReady()
        fun onNotification(data: ByteArray)
        fun onFirmwareVersion(version: Int)
        fun onError(message: String)
        fun onLog(message: String, direction: String = "")
    }

    var callback: Callback? = null
    var firmwareVersion: Int = 0
    val isConnected: Boolean
        get() = _isConnected

    private val handler = Handler(Looper.getMainLooper())

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null

    private var _isConnected = false
    private var isScanning = false
    private val writeLock = Any()
    var currentMtu: Int = 0
    var dataChunkSize: Int = 18  // 每分片传输的数据字节数，从设备 mtu= 通知中更新

    // 写回调驱动（保证每次写入完成后才发下一次）
    private var pendingWrite: CompletableDeferred<Boolean>? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _isConnected = true
                    val name = gatt.device.name ?: gatt.device.address
                    handler.post { callback?.onConnected(name) }
                    handler.post { callback?.onLog("GATT 已连接") }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _isConnected = false
                    handler.post { callback?.onDisconnected() }
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post { callback?.onError("服务发现失败: $status") }
                return
            }
            val service = gatt.getService(UUID.fromString(EpdcProtocol.SERVICE_UUID))
            if (service == null) {
                handler.post { callback?.onError("未找到 EPD 服务") }
                return
            }
            rxCharacteristic = service.getCharacteristic(UUID.fromString(EpdcProtocol.RX_CHAR_UUID))
            txCharacteristic = service.getCharacteristic(UUID.fromString(EpdcProtocol.TX_CHAR_UUID))
            if (rxCharacteristic == null || txCharacteristic == null) {
                handler.post { callback?.onError("未找到特征值") }
                return
            }
            gatt.setCharacteristicNotification(txCharacteristic, true)
            val descriptor = txCharacteristic!!.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
            handler.post { callback?.onServiceReady() }
            // 请求更大的 MTU 和高优先级连接，以加快传输速度
            gatt.requestMtu(512)
            try {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } catch (_: Exception) { }
            // 尝试启用 BLE 5 2M PHY（Android 13+），物理层速率翻倍
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                try {
                    gatt.setPreferredPhy(
                        android.bluetooth.BluetoothDevice.PHY_LE_2M,
                        android.bluetooth.BluetoothDevice.PHY_LE_2M,
                        0 // PHY_OPTIONS_NO_PREFERRED
                    )
                } catch (_: Exception) { }
            }
            readFirmwareVersion()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (characteristic.uuid == UUID.fromString(EpdcProtocol.TX_CHAR_UUID)) {
                val version = characteristic.value[0].toInt() and 0xFF
                firmwareVersion = version
                handler.post {
                    callback?.onFirmwareVersion(version)
                    callback?.onLog("固件版本: 0x${version.toString(16)}, APP版本: v2.1.0")
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == UUID.fromString(EpdcProtocol.TX_CHAR_UUID)) {
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    handler.post { callback?.onNotification(data) }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            if (!success) {
                handler.post { callback?.onError("写入失败: $status") }
            }
            // 通知等待中的协程
            pendingWrite?.complete(success)
            pendingWrite = null
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handler.post { callback?.onLog("通知已开启") }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
                // ATT_MTU = mtu, 最大值长度 = mtu - 3, 每包 = cmd(1) + header(1) + data = mtu - 3
                dataChunkSize = maxOf(18, mtu - 5)
                handler.post { callback?.onLog("MTU: $mtu, 分片: ${dataChunkSize}B") }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: "未知设备"
            val address = result.device.address
            val rssi = result.rssi
            handler.post { callback?.onDeviceFound(name, address, rssi) }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            handler.post { callback?.onError("扫描失败: $errorCode") }
        }
    }

    init {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled ?: false

    @SuppressLint("MissingPermission")
    fun startScan(deviceIdFilter: String? = null) {
        if (isScanning) return
        if (!isBluetoothEnabled()) {
            handler.post { callback?.onError("蓝牙未开启") }
            return
        }
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            handler.post { callback?.onError("无法获取 BLE 扫描器，请检查权限和蓝牙状态") }
            return
        }
        isScanning = true
        val filters = if (!deviceIdFilter.isNullOrBlank()) {
            val prefix = deviceIdFilter.uppercase()
            listOf(
                ScanFilter.Builder().setDeviceName("NRF_EPD_$prefix").build(),
                ScanFilter.Builder().setDeviceName("EPD_$prefix").build()
            )
        } else {
            emptyList()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        handler.post { callback?.onLog("开始扫描设备…") }
        scanner?.startScan(filters, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        scanner?.stopScan(scanCallback)
        isScanning = false
        handler.post { callback?.onLog("停止扫描") }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        stopScan()
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: run {
            callback?.onError("未找到设备: $address")
            return
        }
        handler.post { callback?.onLog("正在连接: ${device.name ?: address}") }
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

    private fun cleanup() {
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "close error", e)
        }
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
    }

    @SuppressLint("MissingPermission")
    fun write(cmd: Byte, data: ByteArray? = null, withResponse: Boolean = true): Boolean {
        if (!_isConnected || rxCharacteristic == null) {
            callback?.onError("未连接")
            return false
        }
        val payload = if (data != null) {
            ByteArray(1 + data.size).apply {
                this[0] = cmd
                System.arraycopy(data, 0, this, 1, data.size)
            }
        } else {
            byteArrayOf(cmd)
        }
        val hex = payload.joinToString(" ") { String.format("%02X", it) }
        callback?.onLog(hex, "⇑")
        return try {
            synchronized(writeLock) {
                rxCharacteristic!!.writeType = if (withResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                rxCharacteristic!!.value = payload
                bluetoothGatt?.writeCharacteristic(rxCharacteristic) == true
            }
        } catch (e: Exception) {
            callback?.onError("write: ${e.message}")
            false
        }
    }

    /**
     * 协程版写入：等待 onCharacteristicWrite 回调确认后再返回
     */
    @SuppressLint("MissingPermission")
    suspend fun writeSuspend(cmd: Byte, data: ByteArray? = null, withResponse: Boolean = true): Boolean {
        if (!_isConnected || rxCharacteristic == null) {
            callback?.onError("未连接")
            return false
        }
        val payload = if (data != null) {
            ByteArray(1 + data.size).apply {
                this[0] = cmd
                System.arraycopy(data, 0, this, 1, data.size)
            }
        } else {
            byteArrayOf(cmd)
        }
        val hex = payload.joinToString(" ") { String.format("%02X", it) }
        callback?.onLog(hex, "⇑")
        return try {
            val deferred = CompletableDeferred<Boolean>()
            synchronized(writeLock) {
                rxCharacteristic!!.writeType = if (withResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                rxCharacteristic!!.value = payload
                pendingWrite = deferred
                if (bluetoothGatt?.writeCharacteristic(rxCharacteristic) != true) {
                    pendingWrite = null
                    return false
                }
            }
            // 等待 onCharacteristicWrite 回调确认
            deferred.invokeOnCompletion { pendingWrite = null }
            return deferred.await()
        } catch (e: Exception) {
            callback?.onError("writeSuspend: ${e.message}")
            false
        }
    }

    /**
     * 写入原始数据包（用于 CRC 块传输）
     */
    @SuppressLint("MissingPermission")
    fun writeRaw(packet: ByteArray, withResponse: Boolean = true): Boolean {
        if (!_isConnected || rxCharacteristic == null) return false
        return try {
            synchronized(writeLock) {
                rxCharacteristic!!.writeType = if (withResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                rxCharacteristic!!.value = packet
                bluetoothGatt?.writeCharacteristic(rxCharacteristic) == true
            }
        } catch (e: Exception) {
            callback?.onError("writeRaw: ${e.message}")
            false
        }
    }

    fun readFirmwareVersion() {
        if (!_isConnected || txCharacteristic == null) return
        bluetoothGatt?.readCharacteristic(txCharacteristic)
    }

    /**
     * 处理设备发来的文本通知（如 MTU 更新）
     */
    fun parseTextNotification(data: ByteArray): Boolean {
        val msg = try {
            String(data, Charsets.UTF_8)
        } catch (e: Exception) { return false }
        when {
            msg.startsWith("mtu=") && msg.length > 4 -> {
                val mtu = msg.substring(4).trim().toIntOrNull()
                if (mtu != null) {
                    // 使用与 onMtuChanged 一致的安全公式，总写入 = cmd(1) + header(1) + chunk ≤ MTU - 3
                    dataChunkSize = maxOf(18, mtu - 5)
                    handler.post { callback?.onLog("MTU: $mtu, 分片: ${dataChunkSize}B") }
                }
                return true
            }
        }
        return false
    }

    fun setDriver(driverValue: String) {
        write(EpdcProtocol.INIT, hex2bytes(driverValue))
    }

    suspend fun clearScreen(): Boolean {
        // INIT 重新初始化控制器（REFRESH 后必须），再 CLEAR 清屏，最后 REFRESH 刷新
        if (!writeSuspend(EpdcProtocol.INIT, byteArrayOf(0x14), true)) return false
        kotlinx.coroutines.delay(10)
        if (!writeSuspend(EpdcProtocol.CLEAR)) return false
        kotlinx.coroutines.delay(10)
        return writeSuspend(EpdcProtocol.REFRESH)
    }

    suspend fun refresh(): Boolean {
        return writeSuspend(EpdcProtocol.REFRESH)
    }

    private fun hex2bytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "").replace(":", "")
        val bytes = ByteArray(cleanHex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = Integer.parseInt(cleanHex.substring(i * 2, i * 2 + 2), 16).toByte()
        }
        return bytes
    }

    fun destroy() {
        stopScan()
        cleanup()
    }
}
