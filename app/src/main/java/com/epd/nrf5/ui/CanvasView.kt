package com.epd.nrf5.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * 自定义画板视图
 * 支持画笔绘制、橡皮擦、文字、缩放
 */
class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Tool { NONE, BRUSH, ERASER, TEXT }

    // 画布尺寸（逻辑像素）
    var canvasWidth = 400
    var canvasHeight = 300

    // 位图缓冲
    private var bitmap: Bitmap? = null
    private var drawCanvas: Canvas? = null
    private val bitmapPaint = Paint(Paint.DITHER_FLAG)

    // 绘制
    private var currentTool = Tool.NONE
    private var brushColor = Color.BLACK
    private var brushSize = 4f
    private var lastX = 0f
    private var lastY = 0f
    private var isDrawing = false

    // 缩放
    var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var scaleDetector: ScaleGestureDetector? = null

    // 文字
    private var pendingText: String? = null
    private var textSize = 48f
    private var textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 48f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    // 历史记录
    private val history = mutableListOf<Bitmap>()
    private var historyStep = -1
    private val maxHistory = 30

    init {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = maxOf(0.2f, minOf(5f, scaleFactor))
                invalidate()
                return true
            }
        })
    }

    fun setTool(tool: Tool) {
        currentTool = tool
    }

    fun setBrushColor(color: Int) {
        brushColor = color
    }

    fun setBrushSize(size: Float) {
        brushSize = size
    }

    fun setTextSize(size: Float) {
        textSize = size
        textPaint.textSize = size
    }

    fun setText(text: String) {
        pendingText = text
    }

    fun setTypeface(typeface: Typeface) {
        textPaint.typeface = typeface
    }

    fun setCanvasSize(width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawCanvas = Canvas(bitmap!!)
        drawCanvas!!.drawColor(Color.WHITE)
        history.clear()
        historyStep = -1
        saveToHistory()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scaleFactor, scaleFactor)

        bitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, bitmapPaint)
        }

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector?.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (currentTool == Tool.TEXT && pendingText != null) {
                    placeText(event)
                    return true
                }
                if (currentTool == Tool.NONE) {
                    isDragging = true
                    lastTouchX = event.x
                    lastTouchY = event.y
                    return true
                }
                isDrawing = true
                lastX = (event.x - translateX) / scaleFactor
                lastY = (event.y - translateY) / scaleFactor
                drawDot(lastX, lastY)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    translateX += event.x - lastTouchX
                    translateY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                    return true
                }
                if (isDrawing) {
                    val x = (event.x - translateX) / scaleFactor
                    val y = (event.y - translateY) / scaleFactor
                    drawLine(lastX, lastY, x, y)
                    lastX = x
                    lastY = y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    return true
                }
                if (isDrawing) {
                    isDrawing = false
                    saveToHistory()
                }
            }
        }
        return true
    }

    private fun drawDot(x: Float, y: Float) {
        val paint = Paint().apply {
            color = if (currentTool == Tool.ERASER) Color.WHITE else brushColor
            strokeWidth = brushSize
            style = Paint.Style.FILL
            strokeCap = Paint.Cap.ROUND
        }
        drawCanvas?.drawCircle(x, y, brushSize / 2, paint)
    }

    private fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        val paint = Paint().apply {
            color = if (currentTool == Tool.ERASER) Color.WHITE else brushColor
            strokeWidth = brushSize
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        drawCanvas?.drawLine(x1, y1, x2, y2, paint)
    }

    private fun placeText(event: MotionEvent) {
        val text = pendingText ?: return
        val x = (event.x - translateX) / scaleFactor
        val y = (event.y - translateY) / scaleFactor
        textPaint.color = brushColor
        textPaint.textSize = textSize
        drawCanvas?.drawText(text, x, y, textPaint)
        pendingText = null
        saveToHistory()
        invalidate()
    }

    fun clearCanvas() {
        drawCanvas?.drawColor(Color.WHITE)
        saveToHistory()
        invalidate()
    }

    // ==================== 历史记录 ====================
    private fun saveToHistory() {
        bitmap?.let {
            if (historyStep < history.size - 1) {
                history.subList(historyStep + 1, history.size).clear()
            }
            history.add(it.copy(it.config, true))
            historyStep++
            if (history.size > maxHistory) {
                history.removeAt(0)
                historyStep--
            }
        }
    }

    fun undo() {
        if (historyStep > 0) {
            historyStep--
            restoreFromHistory()
        }
    }

    fun redo() {
        if (historyStep < history.size - 1) {
            historyStep++
            restoreFromHistory()
        }
    }

    private fun restoreFromHistory() {
        if (historyStep in history.indices) {
            val restored = history[historyStep]
            bitmap = restored.copy(restored.config, true)
            drawCanvas = Canvas(bitmap!!)
            invalidate()
        }
    }

    // ==================== 获取数据 ====================
    fun getBitmap(): Bitmap = bitmap ?: Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)

    fun getPixels(): IntArray {
        val bmp = bitmap ?: return IntArray(0)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return pixels
    }

    fun loadBitmap(source: Bitmap) {
        setCanvasSize(source.width, source.height)
        drawCanvas?.drawBitmap(source, 0f, 0f, null)
        saveToHistory()
        invalidate()
    }

    // ==================== 缩放控制 ====================
    fun zoomIn() {
        scaleFactor = minOf(5f, scaleFactor * 1.2f)
        // 保持居中
        adjustTranslateForZoom()
        invalidate()
    }

    fun zoomOut() {
        scaleFactor = maxOf(0.2f, scaleFactor / 1.2f)
        adjustTranslateForZoom()
        invalidate()
    }

    fun resetZoom() {
        scaleFactor = 1f
        translateX = 0f
        translateY = 0f
        invalidate()
    }

    private fun adjustTranslateForZoom() {
        // 防止缩放后画布完全移出视野
        val vw = width.toFloat()
        val vh = height.toFloat()
        val cw = canvasWidth * scaleFactor
        val ch = canvasHeight * scaleFactor
        translateX = maxOf(vw - cw - 50f, minOf(50f, translateX))
        translateY = maxOf(vh - ch - 50f, minOf(50f, translateY))
    }
}
