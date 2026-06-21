package com.epd.nrf5.ble

/**
 * EPD 蓝牙命令协议（与固件保持一致）
 */
object EpdcProtocol {
    const val SERVICE_UUID = "62750001-d828-918d-fb46-b6c11c675aec"
    const val RX_CHAR_UUID = "62750002-d828-918d-fb46-b6c11c675aec"
    const val TX_CHAR_UUID = "62750003-d828-918d-fb46-b6c11c675aec"

    // 基础命令
    const val SET_PINS: Byte = 0x00
    const val INIT: Byte = 0x01
    const val CLEAR: Byte = 0x02
    const val SEND_DATA: Byte = 0x04
    const val REFRESH: Byte = 0x05
    const val SLEEP: Byte = 0x06

    // 图像传输
    const val WRITE_IMG: Byte = 0x30
    const val WRITE_BLOCK: Byte = 0x31
    const val QUERY_STATUS: Byte = 0x32
    const val RESET_TRANSFER: Byte = 0x33

    // 系统
    const val SET_CONFIG: Byte = 0x90.toByte()
    const val SYS_RESET: Byte = 0x91.toByte()
    const val SYS_SLEEP: Byte = 0x92.toByte()
    const val CFG_ERASE: Byte = 0x99.toByte()

    // 通知类型
    const val NOTIFY_BLOCK_ACK: Byte = 0xA0.toByte()
    const val NOTIFY_STATUS: Byte = 0xA1.toByte()

    // 图层标志
    const val LAYER_BW: Byte = 0x0F
    const val LAYER_COLOR: Byte = 0x00
    const val BLOCK_FIRST: Byte = 0x00
    const val BLOCK_CONTINUE: Byte = (0xF0).toByte()

    /**
     * 构建图像传输数据包（CRC 模式）
     */
    fun buildImageBlock(
        blockId: Int,
        totalBlocks: Int,
        layer: Byte,
        isFirst: Boolean,
        payload: ByteArray,
        crc: Int
    ): ByteArray {
        val cfg = if (isFirst) {
            (0x00.toInt() or (layer.toInt() and 0x0F)).toByte()
        } else {
            (BLOCK_CONTINUE.toInt() or (layer.toInt() and 0x0F)).toByte()
        }
        val packet = ByteArray(8 + payload.size)
        packet[0] = WRITE_BLOCK
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
     * 构建普通图像传输数据包（旧模式）
     */
    fun buildLegacyImagePayload(
        data: ByteArray,
        offset: Int,
        chunkSize: Int,
        layer: Byte,
        isFirst: Boolean
    ): ByteArray {
        val header = if (isFirst) {
            (layer.toInt() and 0x0F).toByte()
        } else {
            (0xF0.toInt() or (layer.toInt() and 0x0F)).toByte()
        }
        val chunk = data.copyOfRange(offset, minOf(offset + chunkSize, data.size))
        val packet = ByteArray(1 + chunk.size)
        packet[0] = header
        System.arraycopy(chunk, 0, packet, 1, chunk.size)
        return packet
    }
}
