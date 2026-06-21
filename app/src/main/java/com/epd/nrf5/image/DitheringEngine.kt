package com.epd.nrf5.image

import android.graphics.Color
import kotlin.math.*

/**
 * 墨水屏图像抖动处理引擎
 * 移植自 dithering.js（VIP版），支持 14 种抖动算法 + 多色板
 */
object DitheringEngine {

    // ==================== 调色板定义 ====================
    data class PaletteColor(val name: String, val r: Int, val g: Int, val b: Int, val value: Int)

    val sixColorPalette = listOf(
        PaletteColor("黄色", 255, 255, 0, 0xE2),
        PaletteColor("绿色", 41, 204, 20, 0x96),
        PaletteColor("蓝色", 0, 0, 255, 0x1D),
        PaletteColor("红色", 255, 0, 0, 0x4C),
        PaletteColor("黑色", 0, 0, 0, 0x00),
        PaletteColor("白色", 255, 255, 255, 0xFF)
    )

    val fourColorPalette = listOf(
        PaletteColor("黑色", 0, 0, 0, 0x00),
        PaletteColor("白色", 255, 255, 255, 0x01),
        PaletteColor("红色", 255, 0, 0, 0x03),
        PaletteColor("黄色", 255, 255, 0, 0x02)
    )

    val threeColorPalette = listOf(
        PaletteColor("黑色", 0, 0, 0, 0x00),
        PaletteColor("白色", 255, 255, 255, 0x01),
        PaletteColor("红色", 255, 0, 0, 0x02)
    )

    // EPD 实际显示颜色（用于更精确的颜色匹配）
    data class RealColor(
        val name: String,
        val realR: Int, val realG: Int, val realB: Int,
        val r: Int, val g: Int, val b: Int, val value: Int
    )

    val epdRealColors = mapOf(
        "fourColor" to listOf(
            RealColor("黑色", 30, 30, 30, 0, 0, 0, 0x00),
            RealColor("白色", 220, 215, 205, 255, 255, 255, 0x01),
            RealColor("红色", 180, 50, 50, 255, 0, 0, 0x03),
            RealColor("黄色", 200, 195, 60, 255, 255, 0, 0x02)
        ),
        "threeColor" to listOf(
            RealColor("黑色", 30, 30, 30, 0, 0, 0, 0x00),
            RealColor("白色", 220, 215, 205, 255, 255, 255, 0x01),
            RealColor("红色", 180, 50, 50, 255, 0, 0, 0x02)
        ),
        "blackWhiteColor" to listOf(
            RealColor("黑色", 30, 30, 30, 0, 0, 0, 0x00),
            RealColor("白色", 220, 215, 205, 255, 255, 255, 0x01)
        ),
        "sixColor" to listOf(
            RealColor("黄色", 200, 195, 60, 255, 255, 0, 0xE2),
            RealColor("绿色", 35, 140, 35, 41, 204, 20, 0x96),
            RealColor("蓝色", 30, 40, 140, 0, 0, 255, 0x1D),
            RealColor("红色", 180, 50, 50, 255, 0, 0, 0x4C),
            RealColor("黑色", 30, 30, 30, 0, 0, 0, 0x00),
            RealColor("白色", 220, 215, 205, 255, 255, 255, 0xFF)
        )
    )

    // ==================== Lab 色彩空间 ====================
    data class Lab(val l: Double, val a: Double, val b: Double)

    private fun rgbToLab(r: Int, g: Int, b: Int): Lab {
        var rr = r / 255.0
        var gg = g / 255.0
        var bb = b / 255.0
        rr = if (rr > 0.04045) ((rr + 0.055) / 1.055).pow(2.4) else rr / 12.92
        gg = if (gg > 0.04045) ((gg + 0.055) / 1.055).pow(2.4) else gg / 12.92
        bb = if (bb > 0.04045) ((bb + 0.055) / 1.055).pow(2.4) else bb / 12.92
        rr *= 100; gg *= 100; bb *= 100
        var x = rr * 0.4124 + gg * 0.3576 + bb * 0.1805
        var y = rr * 0.2126 + gg * 0.7152 + bb * 0.0722
        var z = rr * 0.0193 + gg * 0.1192 + bb * 0.9505
        x /= 95.047; y /= 100.0; z /= 108.883
        x = if (x > 0.008856) x.pow(1.0 / 3) else 7.787 * x + 16.0 / 116
        y = if (y > 0.008856) y.pow(1.0 / 3) else 7.787 * y + 16.0 / 116
        z = if (z > 0.008856) z.pow(1.0 / 3) else 7.787 * z + 16.0 / 116
        return Lab(116 * y - 16, 500 * (x - y), 200 * (y - z))
    }

    private fun labDistance(l1: Lab, l2: Lab): Double {
        val dl = l1.l - l2.l
        val da = l1.a - l2.a
        val db = l1.b - l2.b
        return sqrt(0.2 * dl * dl + 3 * da * da + 3 * db * db)
    }

    // ==================== 颜色匹配 ====================
    private fun findClosestColor(r: Int, g: Int, b: Int, mode: String): PaletteColor {
        val palette = when (mode) {
            "fourColor" -> fourColorPalette
            "threeColor" -> threeColorPalette
            else -> sixColorPalette
        }
        // 三色模式优先检测红色
        if (mode == "threeColor") {
            if (r > 120 && r > g * 1.5 && r > b * 1.5) return threeColorPalette[2]
            val luminance = 0.299 * r + 0.587 * g + 0.114 * b
            return if (luminance < 128) threeColorPalette[0] else threeColorPalette[1]
        }
        val inputLab = rgbToLab(r, g, b)
        var minDist = Double.MAX_VALUE
        var closest = palette[0]
        for (color in palette) {
            val colorLab = rgbToLab(color.r, color.g, color.b)
            val dist = labDistance(inputLab, colorLab)
            if (dist < minDist) { minDist = dist; closest = color }
        }
        return closest
    }

    // 重色模式（legacy）开关
    private var useLegacyColors = false

    private fun findClosestColorReal(r: Int, g: Int, b: Int, mode: String): RealColor {
        // 重色模式下使用标准调色板的 findClosestColor
        if (useLegacyColors) {
            val pc = findClosestColor(r, g, b, mode)
            return RealColor(pc.name, pc.r, pc.g, pc.b, pc.r, pc.g, pc.b, pc.value)
        }
        val palette = epdRealColors[mode] ?: epdRealColors["sixColor"]!!
        if (mode == "blackWhiteColor") {
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            return if (lum < 128) palette[0] else palette[1]
        }
        val inputLab = rgbToLab(r, g, b)
        var minDist = Double.MAX_VALUE
        var closest = palette[0]
        for (color in palette) {
            val colorLab = rgbToLab(color.realR, color.realG, color.realB)
            val dist = labDistance(inputLab, colorLab)
            if (dist < minDist) { minDist = dist; closest = color }
        }
        return closest
    }

    // ==================== sRGB 线性转换 ====================
    private val srgbToLinear = FloatArray(256) { i ->
        val v = i / 255.0
        if (v > 0.04045) ((v + 0.055) / 1.055).pow(2.4).toFloat() else (v / 12.92).toFloat()
    }
    private val linearToSrgb = IntArray(4096) { i ->
        val v = i / 4095.0
        min(255, max(0, (255 * if (v > 0.0031308) 1.055 * v.pow(1.0 / 2.4) - 0.055 else 12.92 * v).toInt()))
    }

    // ==================== 边缘检测 ====================
    private fun computeEdgeMap(pixels: IntArray, width: Int, height: Int): FloatArray {
        val lum = FloatArray(width * height) { i ->
            val p = pixels[i]
            (0.2126 * srgbToLinear[Color.red(p)] + 0.7152 * srgbToLinear[Color.green(p)] + 0.0722 * srgbToLinear[Color.blue(p)]).toFloat()
        }
        val edge = FloatArray(width * height)
        var maxEdge = 0f
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val gx = -lum[idx - width - 1] + lum[idx - width + 1] -
                        2 * lum[idx - 1] + 2 * lum[idx + 1] -
                        lum[idx + width - 1] + lum[idx + width + 1]
                val gy = -lum[idx - width - 1] - 2 * lum[idx - width] - lum[idx - width + 1] +
                        lum[idx + width - 1] + 2 * lum[idx + width] + lum[idx + width + 1]
                val mag = sqrt(gx * gx + gy * gy)
                edge[idx] = mag
                if (mag > maxEdge) maxEdge = mag
            }
        }
        if (maxEdge > 0) {
            val inv = 1f / maxEdge
            for (i in edge.indices) edge[i] *= inv
        }
        return edge
    }

    // ==================== 误差扩散核心 ====================
    private fun errorDiffusionDither(
        pixels: IntArray, width: Int, height: Int,
        strength: Float, colorMode: String, kernel: Array<FloatArray>
    ): IntArray {
        val result = pixels.copyOf()
        val edgeMap = computeEdgeMap(pixels, width, height)
        val edgeThresh = 0.3f

        // 线性光缓冲
        val linearR = FloatArray(width * height) { srgbToLinear[Color.red(result[it])] }
        val linearG = FloatArray(width * height) { srgbToLinear[Color.green(result[it])] }
        val linearB = FloatArray(width * height) { srgbToLinear[Color.blue(result[it])] }

        for (y in 0 until height) {
            val reverse = (y and 1) == 1
            val xStart = if (reverse) width - 1 else 0
            val xEnd = if (reverse) -1 else width
            val step = if (reverse) -1 else 1

            var x = xStart
            while (x != xEnd) {
                val idx = y * width + x
                val r8 = linearToSrgb[min(4095, max(0, (4095 * linearR[idx] + 0.5f).toInt()))]
                val g8 = linearToSrgb[min(4095, max(0, (4095 * linearG[idx] + 0.5f).toInt()))]
                val b8 = linearToSrgb[min(4095, max(0, (4095 * linearB[idx] + 0.5f).toInt()))]

                val closest = findClosestColorReal(r8, g8, b8, colorMode)
                result[idx] = Color.rgb(closest.r, closest.g, closest.b)

                val edgeStrength = edgeMap[idx]
                val adaptiveStrength = if (edgeStrength > edgeThresh) {
                    (0.15f + 0.85f * (1f - (edgeStrength - edgeThresh) / (1f - edgeThresh))).toFloat()
                } else strength

                val errR = (linearR[idx] - srgbToLinear[closest.r]) * adaptiveStrength
                val errG = (linearG[idx] - srgbToLinear[closest.g]) * adaptiveStrength
                val errB = (linearB[idx] - srgbToLinear[closest.b]) * adaptiveStrength

                // 限制误差
                val maxErr = (0.3f + 0.4f * (1f - 4 * (max(0f, min(1f, linearR[idx])) - 0.5f).pow(2))).toDouble()
                fun clipErr(e: Float) = if (abs(e.toDouble()) > maxErr) (maxErr * tanh(e.toDouble() / maxErr)).toFloat() else e
                val errRc = clipErr(errR)
                val errGc = clipErr(errG)
                val errBc = clipErr(errB)

                for (k in kernel.indices) {
                    val dx = if (reverse) -kernel[k][0].toInt() else kernel[k][0].toInt()
                    val dy = kernel[k][1].toInt()
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until width && ny in 0 until height) {
                        val nIdx = ny * width + nx
                        val weight = kernel[k][2]
                        linearR[nIdx] += errRc * weight
                        linearG[nIdx] += errGc * weight
                        linearB[nIdx] += errBc * weight
                    }
                }
                x += step
            }
        }
        return result
    }

    // ==================== 抖动内核 ====================
    private val FLOYD_STEINBERG = arrayOf(
        floatArrayOf(1f, 0f, 7f/16), floatArrayOf(-1f, 1f, 3f/16),
        floatArrayOf(0f, 1f, 5f/16), floatArrayOf(1f, 1f, 1f/16)
    )
    private val ATKINSON = arrayOf(
        floatArrayOf(1f, 0f, 1f/8), floatArrayOf(2f, 0f, 1f/8),
        floatArrayOf(-1f, 1f, 1f/8), floatArrayOf(0f, 1f, 1f/8),
        floatArrayOf(1f, 1f, 1f/8), floatArrayOf(0f, 2f, 1f/8)
    )
    private val SIERRA_LITE = arrayOf(
        floatArrayOf(1f, 0f, 2f/4), floatArrayOf(-1f, 1f, 1f/4), floatArrayOf(0f, 1f, 1f/4)
    )
    private val BURKES = arrayOf(
        floatArrayOf(1f, 0f, 8f/32), floatArrayOf(2f, 0f, 4f/32),
        floatArrayOf(-2f, 1f, 2f/32), floatArrayOf(-1f, 1f, 4f/32),
        floatArrayOf(0f, 1f, 8f/32), floatArrayOf(1f, 1f, 4f/32), floatArrayOf(2f, 1f, 2f/32)
    )
    private val TWO_ROW_SIERRA = arrayOf(
        floatArrayOf(1f, 0f, 4f/16), floatArrayOf(2f, 0f, 3f/16),
        floatArrayOf(-2f, 1f, 1f/16), floatArrayOf(-1f, 1f, 2f/16),
        floatArrayOf(0f, 1f, 3f/16), floatArrayOf(1f, 1f, 2f/16), floatArrayOf(2f, 1f, 1f/16)
    )
    private val OSTROMOUKHOV = arrayOf(
        floatArrayOf(1f, 0f, 0.5f), floatArrayOf(0f, 1f, 0.3f), floatArrayOf(1f, 1f, 0.2f)
    )
    private val STUCKI = arrayOf(
        floatArrayOf(1f,0f,8f/42), floatArrayOf(2f,0f,4f/42),
        floatArrayOf(-2f,1f,2f/42), floatArrayOf(-1f,1f,4f/42),
        floatArrayOf(0f,1f,8f/42), floatArrayOf(1f,1f,4f/42), floatArrayOf(2f,1f,2f/42),
        floatArrayOf(-2f,2f,1f/42), floatArrayOf(-1f,2f,2f/42),
        floatArrayOf(0f,2f,4f/42), floatArrayOf(1f,2f,2f/42), floatArrayOf(2f,2f,1f/42)
    )
    private val JARVIS = arrayOf(
        floatArrayOf(1f,0f,7f/48), floatArrayOf(2f,0f,5f/48),
        floatArrayOf(-2f,1f,3f/48), floatArrayOf(-1f,1f,5f/48),
        floatArrayOf(0f,1f,7f/48), floatArrayOf(1f,1f,5f/48), floatArrayOf(2f,1f,3f/48),
        floatArrayOf(-2f,2f,1f/48), floatArrayOf(-1f,2f,3f/48),
        floatArrayOf(0f,2f,5f/48), floatArrayOf(1f,2f,3f/48), floatArrayOf(2f,2f,1f/48)
    )

    // ==================== Floyd-Steinberg 专用实现 ====================
    private fun floydSteinbergDither(pixels: IntArray, width: Int, height: Int, strength: Float, mode: String): IntArray {
        val temp = pixels.copyOf()
        val result = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val r = Color.red(temp[idx]); val g = Color.green(temp[idx]); val b = Color.blue(temp[idx])
                val closest = findClosestColorReal(r, g, b, mode)
                result[idx] = Color.rgb(closest.r, closest.g, closest.b)
                val errR = ((r - closest.r) * strength).toInt()
                val errG = ((g - closest.g) * strength).toInt()
                val errB = ((b - closest.b) * strength).toInt()
                fun addErr(i: Int, w: Int) {
                    if (i in 0 until width * height) {
                        temp[i] = Color.rgb(
                            max(0, min(255, Color.red(temp[i]) + errR * w / 16)),
                            max(0, min(255, Color.green(temp[i]) + errG * w / 16)),
                            max(0, min(255, Color.blue(temp[i]) + errB * w / 16))
                        )
                    }
                }
                if (x + 1 < width) addErr(idx + 1, 7)
                if (y + 1 < height) {
                    if (x > 0) addErr(idx + width - 1, 3)
                    addErr(idx + width, 5)
                    if (x + 1 < width) addErr(idx + width + 1, 1)
                }
            }
        }
        return result
    }

    // ==================== Atkinson 专用实现 ====================
    private fun atkinsonDither(pixels: IntArray, width: Int, height: Int, strength: Float, mode: String): IntArray {
        val temp = pixels.copyOf()
        val result = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val r = Color.red(temp[idx]); val g = Color.green(temp[idx]); val b = Color.blue(temp[idx])
                val closest = findClosestColorReal(r, g, b, mode)
                result[idx] = Color.rgb(closest.r, closest.g, closest.b)
                val errR = ((r - closest.r) * strength).toInt()
                val errG = ((g - closest.g) * strength).toInt()
                val errB = ((b - closest.b) * strength).toInt()
                fun addErr(i: Int) {
                    if (i in 0 until width * height) {
                        temp[i] = Color.rgb(
                            max(0, min(255, Color.red(temp[i]) + errR / 8)),
                            max(0, min(255, Color.green(temp[i]) + errG / 8)),
                            max(0, min(255, Color.blue(temp[i]) + errB / 8))
                        )
                    }
                }
                if (x + 1 < width) addErr(idx + 1)
                if (x + 2 < width) addErr(idx + 2)
                if (y + 1 < height) {
                    if (x > 0) addErr(idx + width - 1)
                    addErr(idx + width)
                    if (x + 1 < width) addErr(idx + width + 1)
                }
                if (y + 2 < height) addErr(idx + width * 2)
            }
        }
        return result
    }

    // ==================== Bayer 有序抖动 ====================
    private val BAYER_MATRIX = arrayOf(
        intArrayOf(0,32,8,40,2,34,10,42),
        intArrayOf(48,16,56,24,50,18,58,26),
        intArrayOf(12,44,4,36,14,46,6,38),
        intArrayOf(60,28,52,20,62,30,54,22),
        intArrayOf(3,35,11,43,1,33,9,41),
        intArrayOf(51,19,59,27,49,17,57,25),
        intArrayOf(15,47,7,39,13,45,5,37),
        intArrayOf(63,31,55,23,61,29,53,21)
    )

    private fun bayerDither(pixels: IntArray, width: Int, height: Int, strength: Float, mode: String): IntArray {
        val result = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val r = Color.red(pixels[idx]); val g = Color.green(pixels[idx]); val b = Color.blue(pixels[idx])
                val threshold = (BAYER_MATRIX[y % 8][x % 8] / 64f) * 255f
                val nr = max(0, min(255, (r + (threshold - 127.5f) * strength).toInt()))
                val ng = max(0, min(255, (g + (threshold - 127.5f) * strength).toInt()))
                val nb = max(0, min(255, (b + (threshold - 127.5f) * strength).toInt()))
                val closest = findClosestColorReal(nr, ng, nb, mode)
                result[idx] = Color.rgb(closest.r, closest.g, closest.b)
            }
        }
        return result
    }

    // ==================== 蓝噪声抖动 ====================
    private val BLUE_NOISE_64 = intArrayOf(
        2048,3584,512,3072,1536,4000,256,2816,1024,3840,768,2560,1792,4064,128,3328,
        640,2304,1280,3712,384,2176,896,3456,1664,3200,448,2688,1408,3904,64,2944,
        1920,3136,960,2432,1728,3520,192,2048,1152,3648,576,2880,1600,3072,832,2240,
        3968,480,1344,2752,704,3264,1088,3776,320,1472,2624,3392,896,2112,1856,3584
    )
    private val BLUE_NOISE_SIZE = 64

    private fun blueNoiseDither(pixels: IntArray, width: Int, height: Int, strength: Float, mode: String): IntArray {
        val result = IntArray(pixels.size)
        val edgeMap = computeEdgeMap(pixels, width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val p = pixels[idx]
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                val lum = srgbToLinear[r] * 0.2126f + srgbToLinear[g] * 0.7152f + srgbToLinear[b] * 0.0722f
                val noise = ((BLUE_NOISE_64[(y % BLUE_NOISE_SIZE) * BLUE_NOISE_SIZE + (x % BLUE_NOISE_SIZE) % BLUE_NOISE_SIZE].toFloat() / 65535f - 0.5f) * strength).toInt()
                val nr = max(0, min(255, r + noise))
                val ng = max(0, min(255, g + noise))
                val nb = max(0, min(255, b + noise))
                val closest = findClosestColorReal(nr, ng, nb, mode)
                result[idx] = Color.rgb(closest.r, closest.g, closest.b)
            }
        }
        return result
    }

    // ==================== 线性光 Sierra Lite (Linear Light Sierra) ====================
    private val SIERRA_LITE_KERNEL = arrayOf(
        floatArrayOf(1f, 0f, 2f/4), floatArrayOf(-1f, 1f, 1f/4), floatArrayOf(0f, 1f, 1f/4)
    )

    private fun linearLightSierraDither(pixels: IntArray, width: Int, height: Int, strength: Float, mode: String): IntArray {
        val result = pixels.copyOf()
        val linearR = FloatArray(width * height) { srgbToLinear[Color.red(result[it])] }
        val linearG = FloatArray(width * height) { srgbToLinear[Color.green(result[it])] }
        val linearB = FloatArray(width * height) { srgbToLinear[Color.blue(result[it])] }
        for (y in 0 until height) {
            val reverse = (y and 1) == 1
            val xStart = if (reverse) width - 1 else 0
            val xEnd = if (reverse) -1 else width
            val step = if (reverse) -1 else 1
            var x = xStart
            while (x != xEnd) {
                val idx = y * width + x
                val r8 = linearToSrgb[minOf(4095, maxOf(0, (linearR[idx] * 4095).toInt()))]
                val g8 = linearToSrgb[minOf(4095, maxOf(0, (linearG[idx] * 4095).toInt()))]
                val b8 = linearToSrgb[minOf(4095, maxOf(0, (linearB[idx] * 4095).toInt()))]
                val closest = findClosestColorReal(r8, g8, b8, mode)
                result[idx] = Color.rgb(closest.r, closest.g, closest.b)
                val errR = (linearR[idx] - srgbToLinear[closest.r]) * strength
                val errG = (linearG[idx] - srgbToLinear[closest.g]) * strength
                val errB = (linearB[idx] - srgbToLinear[closest.b]) * strength
                for (k in SIERRA_LITE_KERNEL.indices) {
                    val dx = if (reverse) -SIERRA_LITE_KERNEL[k][0].toInt() else SIERRA_LITE_KERNEL[k][0].toInt()
                    val dy = SIERRA_LITE_KERNEL[k][1].toInt()
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until width && ny in 0 until height) {
                        val nIdx = ny * width + nx
                        val w = SIERRA_LITE_KERNEL[k][2]
                        linearR[nIdx] += errR * w; linearG[nIdx] += errG * w; linearB[nIdx] += errB * w
                    }
                }
                x += step
            }
        }
        return result
    }

    // ==================== Hybrid 混合抖动 ====================
    private fun hybridDither(pixels: IntArray, width: Int, height: Int, strength: Float, mode: String): IntArray {
        // Hybrid: 边缘区域用 Floyd-Steinberg, 平滑区域用 Sierra Lite
        val edgeMap = computeEdgeMap(pixels, width, height)
        val floydResult = floydSteinbergDither(pixels, width, height, strength, mode)
        val sierraResult = errorDiffusionDither(pixels, width, height, strength, mode, SIERRA_LITE_KERNEL)
        val result = floydResult.copyOf()
        for (i in result.indices) {
            if (edgeMap[i] < 0.15f) {
                result[i] = sierraResult[i]
            }
        }
        return result
    }

    // ==================== Riemersma (Hilbert 曲线) ====================
    private fun riemersmaDither(pixels: IntArray, width: Int, height: Int, strength: Float, mode: String): IntArray {
        val result = pixels.copyOf()
        val linearR = FloatArray(width * height) { srgbToLinear[Color.red(result[it])] }
        val linearG = FloatArray(width * height) { srgbToLinear[Color.green(result[it])] }
        val linearB = FloatArray(width * height) { srgbToLinear[Color.blue(result[it])] }
        val weights = floatArrayOf(0.5f, 0.25f, 0.125f, 0.0625f, 0.03125f)
        val order = hilbertOrder(width, height)
        for (o in order) {
            val idx = o.first * width + o.second
            if (idx < 0 || idx >= width * height) continue
            val r8 = linearToSrgb[minOf(4095, maxOf(0, (linearR[idx] * 4095).toInt()))]
            val g8 = linearToSrgb[minOf(4095, maxOf(0, (linearG[idx] * 4095).toInt()))]
            val b8 = linearToSrgb[minOf(4095, maxOf(0, (linearB[idx] * 4095).toInt()))]
            val closest = findClosestColorReal(r8, g8, b8, mode)
            result[idx] = Color.rgb(closest.r, closest.g, closest.b)
            val errR = (linearR[idx] - srgbToLinear[closest.r]) * strength
            val errG = (linearG[idx] - srgbToLinear[closest.g]) * strength
            val errB = (linearB[idx] - srgbToLinear[closest.b]) * strength
            for (wIdx in weights.indices) {
                val pi = o.second - wIdx - 1
                if (pi >= 0) {
                    val nIdx = o.first * width + pi
                    if (nIdx in linearR.indices) {
                        val w = weights[wIdx]
                        linearR[nIdx] += errR * w; linearG[nIdx] += errG * w; linearB[nIdx] += errB * w
                    }
                }
            }
        }
        return result
    }

    // Hilbert 曲线顺序生成（简化版）
    private fun hilbertOrder(width: Int, height: Int): List<Pair<Int, Int>> {
        val order = mutableListOf<Pair<Int, Int>>()
        val n = maxOf(width, height)
        var size = 1
        while (size < n) size = size shl 1
        fun hilbert(x: Int, y: Int, xi: Int, xj: Int, yi: Int, yj: Int, s: Int) {
            if (s <= 0) {
                if (x < width && y < height) order.add(Pair(y, x))
                return
            }
            val half = s shr 1
            hilbert(x + xi * half, y + yi * half, yi, yj, xi, xj, half)
            hilbert(x + xj * half, y + yj * half, xi, xj, yi, yj, half)
            hilbert(x + xj * half + xi * half, y + yj * half + yi * half, xi, xj, yi, yj, half)
            hilbert(x + xi * half, y + yi * half, -yi, -yj, -xi, -xj, half)
        }
        hilbert(0, 0, 1, 0, 0, 1, size)
        return order
    }

    // ==================== 无抖动（直接量化）====================
    private fun noneDither(pixels: IntArray, width: Int, height: Int, mode: String): IntArray {
        val result = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val closest = findClosestColorReal(r, g, b, mode)
            result[i] = Color.rgb(closest.r, closest.g, closest.b)
        }
        return result
    }

    fun adjustContrast(pixels: IntArray, factor: Float): IntArray {
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            Color.rgb(
                max(0, min(255, ((Color.red(p) - 128) * factor + 128).toInt())),
                max(0, min(255, ((Color.green(p) - 128) * factor + 128).toInt())),
                max(0, min(255, ((Color.blue(p) - 128) * factor + 128).toInt()))
            )
        }
    }

    /**
     * 执行抖动处理
     * @param pixels ARGB 像素数组
     * @param width 图像宽度
     * @param height 图像高度
     * @param algorithm 算法名称
     * @param strength 抖动强度
     * @param colorMode 颜色模式
     * @param contrast 对比度
     * @return 处理后的像素数组
     */
    fun dither(
        pixels: IntArray, width: Int, height: Int,
        algorithm: String, strength: Float = 1f,
        colorMode: String = "blackWhiteColor", contrast: Float = 1.2f,
        legacy: Boolean = false
    ): IntArray {
        useLegacyColors = legacy
        var processed = adjustContrast(pixels, contrast)
        processed = when (algorithm) {
            "floydSteinberg" -> floydSteinbergDither(processed, width, height, strength, colorMode)
            "atkinson" -> atkinsonDither(processed, width, height, strength, colorMode)
            "sierraLite" -> errorDiffusionDither(processed, width, height, strength, colorMode, SIERRA_LITE)
            "burkes" -> errorDiffusionDither(processed, width, height, strength, colorMode, BURKES)
            "twoRowSierra" -> errorDiffusionDither(processed, width, height, strength, colorMode, TWO_ROW_SIERRA)
            "ostromoukhov" -> errorDiffusionDither(processed, width, height, strength, colorMode, OSTROMOUKHOV)
            "stucki" -> errorDiffusionDither(processed, width, height, strength, colorMode, STUCKI)
            "jarvis" -> errorDiffusionDither(processed, width, height, strength, colorMode, JARVIS)
            "bayer" -> bayerDither(processed, width, height, strength, colorMode)
            "blueNoise" -> blueNoiseDither(processed, width, height, strength, colorMode)
            "hybrid" -> hybridDither(processed, width, height, strength, colorMode)
            "linearLightSierra" -> linearLightSierraDither(processed, width, height, strength, colorMode)
            "riemersma" -> riemersmaDither(processed, width, height, strength, colorMode)
            "none" -> noneDither(processed, width, height, colorMode)
            else -> processed
        }
        return processed
    }
}
