package com.epd.nrf5.image

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 图像处理器
 * 将 Bitmap 转换为墨水屏可用的位图数据
 */
object ImageProcessor {

    /**
     * 将 Bitmap 转为 ARGB 像素数组
     */
    fun bitmapToPixels(bitmap: Bitmap): IntArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels
    }

    /**
     * 将 ARGB 像素数组转回 Bitmap
     */
    fun pixelsToBitmap(pixels: IntArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * 将处理后的像素数组编码为 EPD 位图格式
     * @param pixels 处理后的像素数组（已抖动）
     * @param width 图像宽度
     * @param height 图像高度
     * @param colorMode 颜色模式
     * @return EPD 格式的字节数组
     */
    fun encodeToEPD(pixels: IntArray, width: Int, height: Int, colorMode: String): ByteArray {
        return when (colorMode) {
            "blackWhiteColor" -> encodeBW(pixels, width, height)
            "threeColor" -> encodeThreeColor(pixels, width, height)
            "fourColor" -> encodeFourColor(pixels, width, height)
            "sixColor" -> encodeSixColor(pixels, width, height)
            else -> encodeBW(pixels, width, height)
        }
    }

    /**
     * 黑白编码：每 8 个像素压缩为 1 字节
     * 与 HTML processImageData 一致：bit=1 为白色，bit=0 为黑色
     */
    private fun encodeBW(pixels: IntArray, width: Int, height: Int): ByteArray {
        val bytes = ByteArray(ceil(width * height / 8.0).toInt())
        for (i in pixels.indices) {
            val p = pixels[i]
            val lum = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
            if (lum >= 128) {
                val byteIdx = i / 8
                val bitIdx = 7 - (i % 8)
                bytes[byteIdx] = (bytes[byteIdx].toInt() or (1 shl bitIdx)).toByte()
            }
        }
        return bytes
    }

    /**
     * 三色编码：黑白层 + 红色层
     * 与 HTML processImageData 一致：
     *   BW 层：bit=1 白色，bit=0 黑色
     *   红色层：bit=0 红色（清除位），bit=1 非红色（默认）
     */
    private fun encodeThreeColor(pixels: IntArray, width: Int, height: Int): ByteArray {
        val pixelCount = width * height
        val bwBytes = ByteArray(ceil(pixelCount / 8.0).toInt())
        val redBytes = ByteArray(ceil(pixelCount / 8.0).toInt()).apply {
            // 初始化为 0xFF：非红色默认 bit=1
            for (i in indices) this[i] = 0xFF.toByte()
        }

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            val isRed = r > 120 && r > g * 1.5 && r > b * 1.5

            val byteIdx = i / 8
            val bitIdx = 7 - (i % 8)
            val mask = 1 shl bitIdx

            // BW bit：亮=1，暗=0
            if (lum >= 128) {
                bwBytes[byteIdx] = (bwBytes[byteIdx].toInt() or mask).toByte()
            }

            // Red bit：红色=0，非红色=1
            if (isRed) {
                redBytes[byteIdx] = (redBytes[byteIdx].toInt() and mask.inv()).toByte()
            }
        }

        // 合并为一个数组：前半部分黑白，后半部分红色
        val result = ByteArray(bwBytes.size + redBytes.size)
        System.arraycopy(bwBytes, 0, result, 0, bwBytes.size)
        System.arraycopy(redBytes, 0, result, bwBytes.size, redBytes.size)
        return result
    }

    /**
     * 四色编码：每 4 个像素压缩为 1 字节
     * 0x00=黑, 0x01=白, 0x02=黄, 0x03=红
     */
    private fun encodeFourColor(pixels: IntArray, width: Int, height: Int): ByteArray {
        val pixelCount = width * height
        val bytes = ByteArray(ceil(pixelCount / 4.0).toInt())

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val lum = 0.299 * r + 0.587 * g + 0.114 * b

            val colorValue = when {
                r > 200 && g > 200 && b < 50 -> 0x02  // 黄色
                r > 180 && g < 80 && b < 80 -> 0x03     // 红色
                lum < 128 -> 0x00                         // 黑色
                else -> 0x01                               // 白色
            }

            val byteIdx = i / 4
            val shift = (3 - (i % 4)) * 2
            bytes[byteIdx] = (bytes[byteIdx].toInt() or (colorValue shl shift)).toByte()
        }
        return bytes
    }

    /**
     * 六色编码：每像素 1 字节
     * 0x00=黑, 0x01=白, 0x02=红, 0x03=黄, 0x04=蓝, 0x05=绿
     */
    private fun encodeSixColor(pixels: IntArray, width: Int, height: Int): ByteArray {
        val bytes = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            bytes[i] = when {
                r < 50 && g < 50 && b < 50 -> 0x00     // 黑色
                r > 200 && g < 80 && b < 80 -> 0x02    // 红色
                r > 200 && g > 200 && b < 50 -> 0x03   // 黄色
                r < 80 && g < 80 && b > 180 -> 0x04    // 蓝色
                r < 80 && g > 180 && b < 80 -> 0x05    // 绿色
                else -> 0x01                            // 白色
            }
        }
        return bytes
    }

    /**
     * UC8159 特殊转换（7.5寸低分屏）
     */
    fun convertUC8159(bwData: ByteArray, redData: ByteArray): ByteArray {
        val halfLength = bwData.size
        val payload = ByteArray(halfLength * 4)
        var idx = 0
        for (i in 0 until halfLength) {
            var black = bwData[i].toInt() and 0xFF
            var red = redData[i].toInt() and 0xFF
            for (j in 0..7) {
                var data: Int
                if (red and 0x80 == 0) data = 0x04
                else if (black and 0x80 == 0) data = 0x00
                else data = 0x03
                data = (data shl 4) and 0xFF
                black = (black shl 1) and 0xFF
                red = (red shl 1) and 0xFF
                if (red and 0x80 == 0) data = data or 0x04
                else if (black and 0x80 == 0) data = data or 0x00
                else data = data or 0x03
                black = (black shl 1) and 0xFF
                red = (red shl 1) and 0xFF
                payload[idx++] = data.toByte()
            }
        }
        return payload
    }
}
