package com.epd.nrf5

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.epd.nrf5.ble.BleManager
import com.epd.nrf5.ble.BleTransferManager
import com.epd.nrf5.ble.EpdcProtocol
import com.epd.nrf5.databinding.ActivityMainBinding
import com.epd.nrf5.image.DitheringEngine
import com.epd.nrf5.image.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private val deviceList = mutableListOf<String>() // "name\naddress" for dialog
    private var logBuilder = StringBuilder()
    private var scanDialog: AlertDialog? = null
    private var scanDialogAdapter: ArrayAdapter<String>? = null

    // 固定参数
    private val driverValue = "14"
    private val colorMode = "fourColor"
    private val ditherAlgorithm = "floydSteinberg"
    private val canvasWidth = 768
    private val canvasHeight = 552

    // 原始图像缓存（用于抖动预览 + 发送）
    private var sourceBitmap: Bitmap? = null
    private var previewJob: kotlinx.coroutines.Job? = null

    // 画布合成参数（图像在 768x552 画布上的位置）
    private var compositeOffsetX = 0
    private var compositeOffsetY = 0

    // ==================== 权限请求 ====================
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) addLog("权限已授予")
        else addLog("部分权限未授予，蓝牙功能可能受限")
    }

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadImage(it) }
    }

    // ==================== 生命周期 ====================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleManager = BleManager(this)
        bleManager.callback = bleCallback

        setupListeners()
        requestPermissions()

        // 默认画布 768x552，预览自适应宽度
        binding.imagePreview.post { adjustPreviewSize() }
        addLog("APP v2.1.1 — ${canvasWidth}x$canvasHeight 四色 EPD")
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.destroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.imagePreview.post { adjustPreviewSize() }
    }

    private fun adjustPreviewSize() {
        val display = resources.displayMetrics
        val availableWidth = display.widthPixels - 48
        val heightPx = (availableWidth * canvasHeight / canvasWidth).coerceAtLeast(200)
        val lp = binding.imagePreview.layoutParams
        lp.height = heightPx
        binding.imagePreview.layoutParams = lp
    }

    // ==================== 初始化 ====================
    private fun setupListeners() {
        // 蓝牙断开
        binding.btnDisconnect.setOnClickListener {
            bleManager.disconnect()
        }

        binding.btnScan.setOnClickListener {
            if (!bleManager.isBluetoothEnabled()) {
                addLog("蓝牙未开启，请求打开蓝牙…")
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                binding.tvStatus.text = "请开启蓝牙后重试"
                return@setOnClickListener
            }
            val id = binding.etDeviceId.text.toString().trim()
            val scanFilter = if (id.isNotEmpty() && id.length == 4) id else null
            bleManager.startScan(scanFilter)
            binding.tvStatus.text = "正在扫描…"
            deviceList.clear()

            // 弹出设备列表对话框
            scanDialogAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceList)
            scanDialog = AlertDialog.Builder(this)
                .setTitle("扫描设备中…")
                .setAdapter(scanDialogAdapter) { _, which ->
                    val entry = deviceList[which]
                    val address = entry.substringAfter("\n")
                    scanDialog?.dismiss()
                    scanDialog = null
                    bleManager.stopScan()
                    bleManager.connect(address)
                    binding.tvStatus.text = "正在连接…"
                }
                .setOnCancelListener {
                    bleManager.stopScan()
                    scanDialog = null
                }
                .show()

            // 10秒超时自动关闭
            binding.imagePreview.postDelayed({
                if (bleManager.isConnected) return@postDelayed
                bleManager.stopScan()
                if (scanDialog?.isShowing == true) {
                    scanDialogAdapter?.add("未发现设备")
                    scanDialogAdapter?.notifyDataSetChanged()
                    scanDialog?.setTitle("扫描超时")
                }
                if (binding.tvStatus.text == "正在扫描…") {
                    binding.tvStatus.text = "扫描超时，未发现设备"
                }
            }, 10000)
        }

        // 清除屏幕
        binding.btnClearScreen.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("确认")
                .setMessage("清除屏幕内容?")
                .setPositiveButton("确定") { _, _ ->
                    lifecycleScope.launch {
                        addLog("清屏中 (INIT → CLEAR → REFRESH)…")
                        val ok = bleManager.clearScreen()
                        if (ok) addLog("清屏完成")
                        else addLog("清屏失败")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // ============ 图片 / 文字 模式切换 ============
        binding.toggleContentMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnModeImage -> {
                    binding.layoutImageMode.visibility = android.view.View.VISIBLE
                    binding.layoutTextMode.visibility = android.view.View.GONE
                }
                R.id.btnModeText -> {
                    binding.layoutImageMode.visibility = android.view.View.GONE
                    binding.layoutTextMode.visibility = android.view.View.VISIBLE
                }
            }
        }

        // ============ 图片模式 ============
        binding.btnSelectImage.setOnClickListener {
            imagePicker.launch("image/*")
        }

        binding.etDitherThreshold.addTextChangedListener(simpleTextWatcher { runDitherPreview() })
        binding.etDitherGamma.addTextChangedListener(simpleTextWatcher { runDitherPreview() })
        binding.checkLegacyDither.setOnCheckedChangeListener { _, _ -> runDitherPreview() }

        // ============ 文字模式 ============
        val fontOptions = listOf(
            "默认" to android.graphics.Typeface.DEFAULT,
            "无衬线" to android.graphics.Typeface.SANS_SERIF,
            "衬线" to android.graphics.Typeface.SERIF,
            "等宽" to android.graphics.Typeface.MONOSPACE
        )
        binding.spinnerFont.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            fontOptions.map { it.first }
        )

        binding.seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                binding.tvFontSizeLabel.text = "${maxOf(12, v)}px"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        binding.btnGeneratePreview.setOnClickListener {
            generateTextPreview(fontOptions)
        }

        // ============ 发送 ============
        binding.btnSendImage.setOnClickListener {
            sendImageToDevice()
        }
    }

    // ==================== BLE 回调 ====================
    private val bleCallback = object : BleManager.Callback {
        override fun onDeviceFound(name: String, address: String, rssi: Int) {
            val entry = "$name ($address)\n$address"
            if (deviceList.none { it.endsWith(address) }) {
                deviceList.add(entry)
                scanDialogAdapter?.notifyDataSetChanged()
            }
        }

        override fun onConnected(name: String) {
            binding.tvStatus.text = "已连接: $name"
            binding.btnDisconnect.isEnabled = true
            binding.btnSendImage.isEnabled = sourceBitmap != null
            scanDialog?.dismiss()
            scanDialog = null
            addLog("已连接: $name")
        }

        override fun onDisconnected() {
            binding.tvStatus.text = "未连接"
            binding.btnDisconnect.isEnabled = false
            binding.btnSendImage.isEnabled = false
            addLog("已断开连接")
        }

        override fun onServiceReady() {
            addLog("EPD 服务已就绪")
            // 发送空 INIT 初始化设备通信（参考 HTML：await write(EpdCmd.INIT)）
            bleManager.write(EpdcProtocol.INIT)
            addLog("已发送 INIT 初始化命令")
        }

        override fun onNotification(data: ByteArray) {
            if (BleTransferManager.handleNotification(data)) return
            if (bleManager.parseTextNotification(data)) return

            when {
                data[0] == EpdcProtocol.NOTIFY_BLOCK_ACK -> {
                    val blockId = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
                    val status = data[3].toInt()
                    addLog("块 $blockId ACK: ${if (status == 0) "OK" else "FAIL"}")
                }
                data[0] == EpdcProtocol.NOTIFY_STATUS -> {
                    addLog("状态查询响应")
                }
                else -> {
                    val hex = data.joinToString(" ") { String.format("%02X", it) }
                    if (data.size > 7) addLog("收到配置: $hex")
                    else addLog("收到: $hex", "⇐")
                }
            }
        }

        override fun onFirmwareVersion(version: Int) {
            binding.tvVersionInfo.text = "固件: 0x${version.toString(16)} | APP: v2.1.1"
            addLog("固件版本: 0x${version.toString(16)}")
            addLog("APP版本: v2.1.1")
        }

        override fun onError(message: String) {
            addLog("错误: $message")
        }

        override fun onLog(message: String, direction: String) {
            addLog(message, direction)
        }
    }

    // ==================== 抖动实时预览 ====================
    private fun simpleTextWatcher(onChange: () -> Unit): android.text.TextWatcher {
        return object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { onChange() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
    }

    private fun runDitherPreview() {
        val src = sourceBitmap
        if (src == null || src.width == 0 || src.height == 0) return

        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            // 防抖：短时间内多次触发只执行最后一次
            delay(300)
            if (!isActive) return@launch

            val w = src.width
            val h = src.height
            val threshold = binding.etDitherThreshold.text.toString().toFloatOrNull() ?: 1f
            val gamma = binding.etDitherGamma.text.toString().toFloatOrNull() ?: 1.2f
            val legacyDither = binding.checkLegacyDither.isChecked

            addLog("抖动预览中: $ditherAlgorithm, 阈值=$threshold, Gamma=$gamma, 重色模式=$legacyDither")
            val srcPixels = ImageProcessor.bitmapToPixels(src)

            val dithered = withContext(Dispatchers.Default) {
                DitheringEngine.dither(srcPixels, w, h, ditherAlgorithm, threshold, colorMode, gamma, legacyDither)
            }
            if (!isActive) return@launch

            val result = withContext(Dispatchers.Default) {
                val ditheredBmp = ImageProcessor.pixelsToBitmap(dithered, w, h)
                // 合成到白色画布上，避免抖动噪声影响白色背景
                val composited = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(composited)
                c.drawColor(android.graphics.Color.WHITE)
                c.drawBitmap(ditheredBmp, compositeOffsetX.toFloat(), compositeOffsetY.toFloat(), null)
                composited
            }
            if (!isActive) return@launch

            binding.imagePreview.setImageBitmap(result)
            binding.tvPreviewHint.visibility = android.view.View.GONE
        }
    }

    // ==================== 文字预览生成 ====================
    private fun generateTextPreview(
        fontOptions: List<Pair<String, android.graphics.Typeface>>
    ) {
        val text = binding.etTextInput.text.toString().trim()
        if (text.isEmpty()) {
            addLog("请先输入文字")
            return
        }

        val fontIdx = binding.spinnerFont.selectedItemPosition.coerceIn(0, fontOptions.size - 1)
        val typeface = fontOptions[fontIdx].second
        val style = when {
            binding.checkBold.isChecked && binding.checkItalic.isChecked -> android.graphics.Typeface.BOLD_ITALIC
            binding.checkBold.isChecked -> android.graphics.Typeface.BOLD
            binding.checkItalic.isChecked -> android.graphics.Typeface.ITALIC
            else -> android.graphics.Typeface.NORMAL
        }
        val fontSize = maxOf(12, binding.seekFontSize.progress).toFloat()

        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        val paint = android.graphics.Paint().apply {
            this.typeface = android.graphics.Typeface.create(typeface, style)
            this.textSize = fontSize
            this.color = android.graphics.Color.BLACK
            this.isAntiAlias = true
        }

        // 按字符换行（支持中英文混合）
        val maxWidth = canvasWidth - 40f
        val allLines = mutableListOf<String>()
        for (line in text.split("\n")) {
            if (paint.measureText(line) <= maxWidth) {
                allLines.add(line)
            } else {
                val sb = StringBuilder()
                for (ch in line) {
                    sb.append(ch)
                    if (paint.measureText(sb.toString()) > maxWidth) {
                        allLines.add(sb.substring(0, sb.length - 1))
                        sb.clear()
                        sb.append(ch)
                    }
                }
                if (sb.isNotEmpty()) allLines.add(sb.toString())
            }
        }

        // 居中绘制
        val lineHeight = paint.fontSpacing
        val totalTextHeight = allLines.size * lineHeight
        var y = (canvasHeight - totalTextHeight) / 2 + lineHeight
        paint.textAlign = android.graphics.Paint.Align.CENTER
        for (line in allLines) {
            canvas.drawText(line, canvasWidth / 2f, y, paint)
            y += lineHeight
        }

        sourceBitmap = bitmap
        binding.imagePreview.setImageBitmap(bitmap)
        binding.tvPreviewHint.visibility = android.view.View.GONE
        binding.btnSendImage.isEnabled = bleManager.isConnected
        addLog("✓ 文字预览已生成: \"$text\" (${fontSize.toInt()}px)")
    }

    // ==================== 图像发送 ====================
    private fun sendImageToDevice() {
        lifecycleScope.launch {
            try {
                val bitmap = sourceBitmap
                if (bitmap == null) {
                    addLog("请先选择图片或输入文字")
                    return@launch
                }
                // 立即显示进度条，图像处理阶段显示不确定进度
                binding.progressTransfer.isIndeterminate = true
                binding.progressTransfer.visibility = android.view.View.VISIBLE
                binding.tvTransferStatus.text = "处理图像中…"
                binding.tvTransferStatus.visibility = android.view.View.VISIBLE

                val width = bitmap.width
                val height = bitmap.height

                addLog("开始处理图像: ${width}x${height}, 算法: $ditherAlgorithm, 模式: $colorMode")

                val threshold = binding.etDitherThreshold.text.toString().toFloatOrNull() ?: 1f
                val gamma = binding.etDitherGamma.text.toString().toFloatOrNull() ?: 1.2f
                addLog("抖动参数：阈值=$threshold, Gamma=$gamma")

                val legacyDither = binding.checkLegacyDither.isChecked
                val ditheredPixels = withContext(Dispatchers.Default) {
                    val pixels = ImageProcessor.bitmapToPixels(bitmap)
                    DitheringEngine.dither(pixels, width, height, ditherAlgorithm, threshold, colorMode, gamma, legacyDither)
                }

                // 合成到白色画布并更新预览（避免背景抖动噪声影响 EPD 编码）
                val compositedBmp = withContext(Dispatchers.Default) {
                    val ditheredBmp = ImageProcessor.pixelsToBitmap(ditheredPixels, width, height)
                    val composited = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(composited)
                    c.drawColor(android.graphics.Color.WHITE)
                    c.drawBitmap(ditheredBmp, compositeOffsetX.toFloat(), compositeOffsetY.toFloat(), null)
                    composited
                }
                binding.imagePreview.setImageBitmap(compositedBmp)
                binding.tvPreviewHint.visibility = android.view.View.GONE
                addLog("抖动处理完成")

                val epdData = withContext(Dispatchers.Default) {
                    val compositedPixels = ImageProcessor.bitmapToPixels(compositedBmp)
                    ImageProcessor.encodeToEPD(compositedPixels, canvasWidth, canvasHeight, colorMode)
                }

                addLog("EPD 数据编码完成: ${epdData.size} 字节")

                if (!bleManager.isConnected) {
                    addLog("请先连接设备")
                    return@launch
                }

                addLog("发送配置: 驱动=3.98寸四色(华为手机壳A0), 颜色模式=四色")

                val initOk = bleManager.writeSuspend(EpdcProtocol.INIT, byteArrayOf(0x14), true)
                if (!initOk) {
                    addLog("INIT 失败，设备未响应")
                    return@launch
                }
                delay(10)

                when (colorMode) {
                    "threeColor" -> {
                        val half = epdData.size / 2
                        val bwData = epdData.copyOfRange(0, half)
                        val redData = epdData.copyOfRange(half, epdData.size)
                        addLog("三色模式: BW ${bwData.size}字节 + 红 ${redData.size}字节")
                        sendLayerData(bwData, EpdcProtocol.LAYER_BW)
                        sendLayerData(redData, EpdcProtocol.LAYER_COLOR)
                    }
                    "fourColor", "sixColor" -> {
                        addLog("${colorMode}模式: ${epdData.size}字节, 图层=COLOR")
                        sendLayerData(epdData, EpdcProtocol.LAYER_COLOR)
                    }
                    else -> {
                        addLog("黑白模式: ${epdData.size}字节, 图层=BW")
                        sendLayerData(epdData, EpdcProtocol.LAYER_BW)
                    }
                }

                delay(20)  // 原 100ms，提速后 20ms
                bleManager.refresh()
                addLog("已发送 REFRESH 命令，等待屏幕刷新…")
                delay(100)
                binding.progressTransfer.visibility = android.view.View.GONE
                binding.tvTransferStatus.visibility = android.view.View.GONE
                addLog("✓ 发送完成！屏幕即将刷新")
            } catch (e: Exception) {
                addLog("处理失败: ${e.message}")
            }
        }
    }

    // ==================== 图层数据发送 ====================
    private suspend fun sendLayerData(data: ByteArray, layer: Byte) {
        val chunkSize = bleManager.dataChunkSize
        val totalChunks = (data.size + chunkSize - 1) / chunkSize
        var noReplyCount = 0
        val interleavedCount = 50
        val startTimeMs = System.currentTimeMillis()

        binding.progressTransfer.isIndeterminate = false
        binding.progressTransfer.max = totalChunks
        binding.progressTransfer.progress = 0
        binding.tvTransferStatus.text = "0/$totalChunks (0%) — 0 KB/s"

        for (offset in data.indices step chunkSize) {
            val chunkIdx = offset / chunkSize
            val isFirst = offset == 0
            val isLast = offset + chunkSize >= data.size

            val header = (layer.toInt() and 0x0F or if (isFirst) 0x00 else 0xF0).toByte()

            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            val payload = ByteArray(1 + chunk.size).apply {
                this[0] = header
                System.arraycopy(chunk, 0, this, 1, chunk.size)
            }

            val needsResponse = noReplyCount <= 0 || isLast

            if (needsResponse) {
                // 有响应写入：等待 BLE ACK，作为流量控制点
                val ok = bleManager.writeSuspend(EpdcProtocol.WRITE_IMG, payload, true)
                if (!ok) {
                    addLog("写入失败: 分片 $chunkIdx/$totalChunks")
                    throw Exception("写入失败")
                }
                noReplyCount = interleavedCount
            } else {
                // 无响应写入：等待 onCharacteristicWrite 回调确认后再发下一个
                val ok = bleManager.writeSuspend(EpdcProtocol.WRITE_IMG, payload, false)
                if (!ok) {
                    addLog("写入失败: 分片 $chunkIdx/$totalChunks")
                    throw Exception("写入失败")
                }
                noReplyCount--
            }

            // 更新进度
            val sent = chunkIdx + 1
            val percent = (sent * 100) / totalChunks
            val sentBytes = sent * chunkSize
            val elapsed = System.currentTimeMillis() - startTimeMs
            val speedKB = if (elapsed > 0) (sentBytes * 1000L / elapsed) / 1024 else 0
            binding.progressTransfer.progress = sent
            binding.tvTransferStatus.text = "$sent/$totalChunks ($percent%) — ${speedKB}KB/s"
        }
    }

    // ==================== 图片加载 ====================
    private fun loadImage(uri: Uri) {
        try {
            // 读取 EXIF 方向信息，自动旋转竖屏照片
            var rotation = 0f
            try {
                val exifIn = contentResolver.openInputStream(uri)
                if (exifIn != null) {
                    val exif = android.media.ExifInterface(exifIn)
                    val orientation = exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                    exifIn.close()
                    rotation = when (orientation) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                }
            } catch (_: Exception) {}

            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap != null) {
                // 应用 EXIF 旋转（竖屏照片自动摆正）
                val oriented = if (rotation != 0f) {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(rotation)
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else bitmap

                // 如果图片高>宽（竖屏），自动旋转 90° 适应 EPD 横屏
                val landscape = if (oriented.height > oriented.width) {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(90f)
                    Bitmap.createBitmap(oriented, 0, 0, oriented.width, oriented.height, matrix, true)
                } else oriented

                val scale = maxOf(canvasWidth.toFloat() / landscape.width, canvasHeight.toFloat() / landscape.height)
                val newW = (landscape.width * scale).toInt().coerceAtLeast(1)
                val newH = (landscape.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(landscape, newW, newH, true)

                // 保存原始缩放图像（不含白色背景）用于抖动，避免背景边缘抖动噪声
                sourceBitmap = scaled
                compositeOffsetX = (canvasWidth - newW) / 2
                compositeOffsetY = (canvasHeight - newH) / 2

                // 居中放置到 768x552 背景上（仅用于即时预览）
                val finalBitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(finalBitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(scaled, compositeOffsetX.toFloat(), compositeOffsetY.toFloat(), null)

                if (landscape != oriented) landscape.recycle()
                if (oriented != bitmap) oriented.recycle()
                binding.btnSendImage.isEnabled = bleManager.isConnected

                // 先显示原图，后台异步抖动
                binding.imagePreview.setImageBitmap(finalBitmap)
                binding.tvPreviewHint.visibility = android.view.View.GONE
                addLog("图片已加载: ${bitmap.width}x${bitmap.height} (旋转${rotation.toInt()}°) → 缩放至 ${newW}x${newH}")
                runDitherPreview()
            }
        } catch (e: Exception) {
            addLog("加载图片失败: ${e.message}")
        }
    }

    // ==================== 工具函数 ====================
    private fun addLog(message: String, direction: String = "") {
        val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = if (direction.isNotEmpty()) "$time $direction $message" else "$time $message"
        logBuilder.appendLine(line)
        binding.tvLog.text = logBuilder.toString()
        binding.svLog.post { binding.svLog.fullScroll(android.view.View.FOCUS_DOWN) }
        if (logBuilder.length > 5000) {
            logBuilder.delete(0, logBuilder.length - 3000)
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
