package com.example.blinkmonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class FrequencyGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Пороги для зон (соответствуют BlinkDetector)
    companion object {
        private const val EAR_OPEN_THRESHOLD = 0.20f   // выше этого - открыты
        private const val EAR_HALF_THRESHOLD = 0.14f   // 0.14-0.20 - полузакрыты
        // ниже 0.14 - закрыты
    }
    
    // Цветовые зоны для EAR значений
    private val earAreaOpenPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")  // зеленый
        style = Paint.Style.FILL
        alpha = 60
    }
    
    private val earAreaHalfPaint = Paint().apply {
        color = Color.parseColor("#FFC107")  // желтый
        style = Paint.Style.FILL
        alpha = 60
    }
    
    private val earAreaClosedPaint = Paint().apply {
        color = Color.parseColor("#F44336")  // красный
        style = Paint.Style.FILL
        alpha = 60
    }
    
    private val zoneBorderPaint = Paint().apply {
        color = Color.parseColor("#FFFFFF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }
    
    private val earLinePaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val pointPaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 5f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val currentStatePaint = Paint().apply {
        strokeWidth = 8f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#555555")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        isAntiAlias = true
    }
    
    private val smallTextPaint = Paint().apply {
        color = Color.parseColor("#CCCCCC")
        textSize = 14f
        isAntiAlias = true
    }
    
    private var earData: List<Float> = emptyList()
    private var currentEar: Float = 0f
    private var currentState: BlinkDetector.EyeState = BlinkDetector.EyeState.OPEN
    
    fun updateData(data: List<Float>) {
        earData = data
        currentEar = if (data.isNotEmpty()) data.last() else 0f
        
        currentState = when {
            currentEar > EAR_OPEN_THRESHOLD -> BlinkDetector.EyeState.OPEN
            currentEar > EAR_HALF_THRESHOLD -> BlinkDetector.EyeState.HALF_CLOSED
            else -> BlinkDetector.EyeState.CLOSED
        }
        
        invalidate()
    }
    
    fun reset() {
        earData = emptyList()
        currentEar = 0f
        currentState = BlinkDetector.EyeState.OPEN
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0 || height <= 0) return
        
        drawEARZones(canvas, width, height)
        drawGrid(canvas, width, height)
        
        if (earData.isEmpty() || earData.size < 2) {
            canvas.drawText("Ожидание данных...", 20f, height / 2, textPaint)
            drawHorizontalBar(canvas, width, height)
            return
        }
        
        drawEARGraph(canvas, width, height)
        drawScale(canvas, width, height)
        drawTitle(canvas, width)
        drawHorizontalBar(canvas, width, height)
    }
    
    private fun drawEARZones(canvas: Canvas, width: Float, height: Float) {
        val maxEAR = 0.5f
        
        // Вычисляем Y-координаты для порогов
        // EAR = 0 (закрыто) -> низ графика
        // EAR = 0.5 (максимум) -> верх графика
        val yClosed = height  // EAR = 0
        val yHalf = height - (EAR_HALF_THRESHOLD / maxEAR) * height  // EAR = 0.14
        val yOpen = height - (EAR_OPEN_THRESHOLD / maxEAR) * height  // EAR = 0.20
        
        // Красная зона (закрытые глаза): 0 - 0.14
        canvas.drawRect(0f, yHalf, width, yClosed, earAreaClosedPaint)
        
        // Желтая зона (полузакрытые): 0.14 - 0.20
        canvas.drawRect(0f, yOpen, width, yHalf, earAreaHalfPaint)
        
        // Зеленая зона (открытые): 0.20 - 0.50
        canvas.drawRect(0f, 0f, width, yOpen, earAreaOpenPaint)
        
        // Границы зон
        canvas.drawLine(0f, yHalf, width, yHalf, zoneBorderPaint)
        canvas.drawLine(0f, yOpen, width, yOpen, zoneBorderPaint)
        
        // Подписи зон (справа)
        val labelX = width - 90f
        canvas.drawText("ЗАКРЫТЫ", labelX, yHalf - 10f, smallTextPaint)
        canvas.drawText("ПОЛУЗАКРЫТЫ", labelX - 20f, yOpen + (yHalf - yOpen) / 2, smallTextPaint)
        canvas.drawText("ОТКРЫТЫ", labelX, yOpen - 15f, smallTextPaint)
        
        // Подписи порогов на границах (слева)
        smallTextPaint.textSize = 11f
        smallTextPaint.color = Color.parseColor("#AAAAAA")
        canvas.drawText("0.14", 5f, yHalf - 5f, smallTextPaint)
        canvas.drawText("0.20", 5f, yOpen - 5f, smallTextPaint)
    }
    
    private fun drawGrid(canvas: Canvas, width: Float, height: Float) {
        val maxEAR = 0.5f
        
        // Горизонтальные линии для 0.1, 0.2, 0.3, 0.4
        for (ear in listOf(0.1f, 0.2f, 0.3f, 0.4f)) {
            val y = height - (ear / maxEAR) * height
            canvas.drawLine(0f, y, width, y, gridPaint)
            smallTextPaint.textSize = 11f
            smallTextPaint.color = Color.parseColor("#888888")
            canvas.drawText(String.format("%.1f", ear), 5f, y - 3f, smallTextPaint)
        }
        
        // Вертикальные линии
        if (earData.isNotEmpty()) {
            val xStep = width / (earData.size - 1).coerceAtLeast(1)
            for (i in 0 until earData.size step 10) {
                val x = i * xStep
                canvas.drawLine(x, 0f, x, height, gridPaint)
            }
        }
    }
    
    private fun drawEARGraph(canvas: Canvas, width: Float, height: Float) {
        val maxEAR = 0.5f
        val xStep = width / (earData.size - 1)
        val yScale = height / maxEAR
        
        var prevX = 0f
        var prevY = height - earData[0].coerceAtMost(maxEAR) * yScale
        
        earData.forEachIndexed { index, value ->
            val x = index * xStep
            val y = height - value.coerceAtMost(maxEAR) * yScale
            
            if (index > 0) {
                canvas.drawLine(prevX, prevY, x, y, earLinePaint)
            }
            
            val isLastPoint = (index == earData.size - 1)
            if (isLastPoint) {
                currentStatePaint.color = when (currentState) {
                    BlinkDetector.EyeState.OPEN -> Color.parseColor("#4CAF50")
                    BlinkDetector.EyeState.HALF_CLOSED -> Color.parseColor("#FFC107")
                    BlinkDetector.EyeState.CLOSED -> Color.parseColor("#F44336")
                }
                canvas.drawCircle(x, y, 8f, currentStatePaint)
            } else {
                pointPaint.color = Color.CYAN
                canvas.drawCircle(x, y, 4f, pointPaint)
            }
            
            prevX = x
            prevY = y
        }
    }
    
    private fun drawScale(canvas: Canvas, width: Float, height: Float) {
        val maxEAR = 0.5f
        
        // Шкала справа
        for (ear in listOf(0f, 0.1f, 0.14f, 0.2f, 0.3f, 0.4f, 0.5f)) {
            val y = height - (ear / maxEAR) * height
            if (y in 0f..height) {
                canvas.drawLine(width - 30f, y, width - 15f, y, gridPaint)
                smallTextPaint.textSize = 11f
                smallTextPaint.color = Color.WHITE
                canvas.drawText(String.format("%.2f", ear), width - 65f, y + 4f, smallTextPaint)
            }
        }
    }
    
    private fun drawTitle(canvas: Canvas, width: Float) {
        textPaint.color = Color.WHITE
        textPaint.textSize = 18f
        canvas.drawText("EAR (Eye Aspect Ratio)", 20f, 25f, textPaint)
        
        val currentText = "Текущий: %.3f".format(currentEar)
        val currentColor = when (currentState) {
            BlinkDetector.EyeState.OPEN -> Color.parseColor("#4CAF50")
            BlinkDetector.EyeState.HALF_CLOSED -> Color.parseColor("#FFC107")
            BlinkDetector.EyeState.CLOSED -> Color.parseColor("#F44336")
        }
        textPaint.color = currentColor
        textPaint.textSize = 16f
        canvas.drawText(currentText, width - 130f, 25f, textPaint)
        textPaint.color = Color.WHITE
    }
    
    private fun drawHorizontalBar(canvas: Canvas, width: Float, height: Float) {
        val barWidth = width - 80f
        val barHeight = 20f
        val barX = 40f
        val barY = height - 35f
        
        // Фон бара
        val bgPaint = Paint().apply { color = Color.parseColor("#333333") }
        canvas.drawRect(barX, barY, barX + barWidth, barY + barHeight, bgPaint)
        
        // Красный сегмент (закрытые: 0 - 0.14)
        val closedWidth = barWidth * (0.14f / 0.5f)
        canvas.drawRect(barX, barY, barX + closedWidth, barY + barHeight, earAreaClosedPaint)
        
        // Желтый сегмент (полузакрытые: 0.14 - 0.20)
        val halfWidth = barWidth * ((0.20f - 0.14f) / 0.5f)
        canvas.drawRect(barX + closedWidth, barY, barX + closedWidth + halfWidth, barY + barHeight, earAreaHalfPaint)
        
        // Зеленый сегмент (открытые: 0.20 - 0.50)
        val openWidth = barWidth * ((0.50f - 0.20f) / 0.5f)
        canvas.drawRect(barX + closedWidth + halfWidth, barY, barX + barWidth, barY + barHeight, earAreaOpenPaint)
        
        // Маркер текущего значения
        val markerX = barX + barWidth * (currentEar / 0.5f).coerceIn(0f, 1f)
        val markerPaint = Paint().apply {
            color = when (currentState) {
                BlinkDetector.EyeState.OPEN -> Color.parseColor("#4CAF50")
                BlinkDetector.EyeState.HALF_CLOSED -> Color.parseColor("#FFC107")
                BlinkDetector.EyeState.CLOSED -> Color.parseColor("#F44336")
            }
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(markerX, barY - 4f, markerX, barY + barHeight + 4f, markerPaint)
        canvas.drawCircle(markerX, barY - 7f, 5f, markerPaint)
        
        // Подписи под баром
        smallTextPaint.textSize = 11f
        smallTextPaint.color = Color.parseColor("#AAAAAA")
        canvas.drawText("0", barX, barY + barHeight + 15f, smallTextPaint)
        canvas.drawText("0.14", barX + closedWidth - 15f, barY + barHeight + 15f, smallTextPaint)
        canvas.drawText("0.20", barX + closedWidth + halfWidth - 15f, barY + barHeight + 15f, smallTextPaint)
        canvas.drawText("0.50", barX + barWidth - 20f, barY + barHeight + 15f, smallTextPaint)
    }
}