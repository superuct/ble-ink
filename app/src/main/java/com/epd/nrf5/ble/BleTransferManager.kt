package com.epd.nrf5.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log

/**
 * CRC 校验传输管理器
 * 支持 CRC16-CCITT、批量 ACK、断点续传
 */
object BleTransferManager {

    private const val TAG = "BleTransfer"

    // 配置
    var mtu: Int = 20
    var interleavedCount: Int = 50
    var batchSize: Int = 20
    var maxRetries: Int = 3

    // 状态
    private var sessionId: Int = 0
    private var currentLayer: Byte = 0x0F
    private var block0Sent: Boolean = false

    // 传输状态查询
    private var pendingStatus: TransferStatus? = null
    private var statusRequestId: Int = 0

    // 统计
    private var transferStartTime: Long = 0
    private var bytesSent: Int = 0
    private var blocksSent: Int = 0

    // 回调
    var onProgress: ((sent: Int, total: Int, speed: String, elapsed: Double) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    data class TransferStatus(
        val total: Int,
        val received: Int,
        val sessionId: Int,
        val active: Boolean,
        val bitmap: ByteArray
    )

    /**
     * CRC16-CCITT
     */
    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    /**
     * 发送单个 CRC 块
     */
    fun buildBlockPacket(blockId: Int, totalBlocks: Int, payload: ByteArray, layer: Byte): ByteArray {
        val cfg: Byte = if (blockId == 0) {
            (0x00 or (layer.toInt() and 0x0F)).toByte()
        } else {
            (0xF0 or (layer.toInt() and 0x0F)).toByte()
        }
        val crc = crc16(payload)
        val packet = ByteArray(8 + payload.size)
        packet[0] = EpdcProtocol.WRITE_BLOCK
        packet[1] = (blockId and 0xFF).toByte()
        packet[2] = ((blockId shr 8) and 0xFF).toByte()
        packet[3] = (totalBlocks and 0xFF).toByte()
        packet[4] = ((totalBlocks shr 8) and 0xFF).toByte()
        packet[5] = cfg
        System.arraycopy(payload, 0, packet, 6, payload.size)
        packet[6 + payload.size] = (crc and 0xFF).toByte()
        packet[7 + payload.size] = ((crc shr 8) and 0xFF).toByte()
        return packet
    }

    /**
     * 处理通知（来自 BLE 回调）
     */
    fun handleNotification(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        return when (data[0].toInt() and 0xFF) {
            0xA0 -> {
                val blockId = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
                val status = data[3].toInt()
                onLog?.invoke("块 $blockId ACK: ${if (status == 0) "OK" else "FAIL"}")
                true
            }
            0xA1 -> {
                pendingStatus = TransferStatus(
                    total = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8),
                    received = (data[3].toInt() and 0xFF) or ((data[4].toInt() and 0xFF) shl 8),
                    sessionId = data[5].toInt() and 0xFF,
                    active = data[6].toInt() == 1,
                    bitmap = data.sliceArray(7 until data.size)
                )
                true
            }
            else -> false
        }
    }

    /**
     * 计算传输速度
     */
    fun getSpeedString(): String {
        val elapsed = (System.currentTimeMillis() - transferStartTime) / 1000.0
        if (elapsed <= 0) return "0 B/s"
        val bps = bytesSent / elapsed
        return if (bps < 1024) "%.0f B/s".format(bps) else "%.1f KB/s".format(bps / 1024)
    }

    fun getElapsed(): Double = (System.currentTimeMillis() - transferStartTime) / 1000.0

    /**
     * 重置传输状态
     */
    suspend fun resetTransfer(bleManager: BleManager) {
        sessionId = (System.currentTimeMillis() and 0xFF).toInt()
        block0Sent = false
        transferStartTime = System.currentTimeMillis()
        bytesSent = 0
        blocksSent = 0

        bleManager.write(EpdcProtocol.RESET_TRANSFER, byteArrayOf(sessionId.toByte()))
        kotlinx.coroutines.delay(100)
        onLog?.invoke("CRC传输已重置, session=$sessionId")
    }

    /**
     * 获取缺失块列表
     */
    private fun getMissingBlocks(status: TransferStatus?, totalBlocks: Int): List<Int> {
        if (status == null || status.bitmap.isEmpty()) {
            return (0 until totalBlocks).toList()
        }
        val missing = mutableListOf<Int>()
        for (i in 0 until totalBlocks) {
            val byteIdx = i / 8
            val bitIdx = i % 8
            if (byteIdx >= status.bitmap.size || (status.bitmap[byteIdx].toInt() and (1 shl bitIdx)) == 0) {
                missing.add(i)
            }
        }
        return missing
    }

    /**
     * 带 CRC 校验和断点续传的图像传输
     */
    suspend fun sendImageWithResume(
        bleManager: BleManager,
        epdData: ByteArray,
        step: String = "bw",
        mtu: Int = this.mtu,
        onProgress: ((sent: Int, total: Int) -> Unit)? = null
    ) {
        val chunkSize = maxOf(mtu - 12, 20)
        val totalBlocks = (epdData.size + chunkSize - 1) / chunkSize
        currentLayer = if (step == "bw") 0x0F else 0x00

        onLog?.invoke("开始 CRC 传输: $totalBlocks 块, ${epdData.size} 字节, 图层=$step, MTU=$mtu")

        // 先发送 INIT
        bleManager.write(EpdcProtocol.INIT)
        kotlinx.coroutines.delay(50)

        resetTransfer(bleManager)

        for (retryRound in 0 until maxRetries) {
            val missingBlocks: List<Int> = if (retryRound == 0) {
                (0 until totalBlocks).toList()
            } else {
                val status = queryTransferStatus(bleManager)
                val missing = getMissingBlocks(status, totalBlocks)
                if (missing.isEmpty()) {
                    val elapsed = getElapsed()
                    onLog?.invoke("✅ CRC 传输完成: $totalBlocks 块, ${getSpeedString()}, ${"%.1f".format(elapsed)}s")
                    this.onProgress?.invoke(totalBlocks, totalBlocks, getSpeedString(), elapsed)
                    return
                }
                onLog?.invoke("重试第 ${retryRound + 1} 轮: ${missing.size} 块丢失")
                missing
            }

            for ((idx, blockId) in missingBlocks.withIndex()) {
                val offset = blockId * chunkSize
                val payload = epdData.copyOfRange(offset, minOf(offset + chunkSize, epdData.size))
                if (payload.isEmpty()) continue

                val isLastInBatch = (idx + 1) % batchSize == 0
                val isLastBlock = idx == missingBlocks.size - 1

                val packet = buildBlockPacket(blockId, totalBlocks, payload, currentLayer)
                // 使用 writeSuspend 确保每包写入都等待回调，保证稳定性
                val needsResponse = isLastInBatch || isLastBlock
                bleManager.writeSuspend(EpdcProtocol.WRITE_BLOCK, packet.drop(1).toByteArray(), withResponse = needsResponse)

                // 无响应写入后添加小延迟，防止设备 BLE 接收缓冲区溢出
                if (!needsResponse) kotlinx.coroutines.delay(10)

                bytesSent += payload.size
                blocksSent++

                onProgress?.invoke(blocksSent, totalBlocks)
            }

            kotlinx.coroutines.delay(150)
        }

        onError?.invoke("CRC 传输失败: 已达最大重试次数 $maxRetries")
        throw Exception("CRC 传输失败 ($maxRetries 次重试)")
    }

    /**
     * 查询传输状态
     */
    private suspend fun queryTransferStatus(bleManager: BleManager): TransferStatus? {
        pendingStatus = null
        statusRequestId++
        val requestId = statusRequestId

        bleManager.write(EpdcProtocol.QUERY_STATUS)
        kotlinx.coroutines.delay(500)

        return if (statusRequestId == requestId) {
            pendingStatus
        } else null
    }

    fun reset() {
        pendingStatus = null
        statusRequestId = 0
        block0Sent = false
        transferStartTime = 0
        bytesSent = 0
        blocksSent = 0
    }
}
