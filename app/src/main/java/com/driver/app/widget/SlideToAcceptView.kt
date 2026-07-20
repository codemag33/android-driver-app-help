package com.driver.app.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * Свайп-слайдер для принятия заказа (стиль Яндекс Про).
 *
 * Пользователь перетаскивает кружок вправо. Если дотянул до конца —
 * срабатывает callback onSlideComplete.
 */
class SlideToAcceptView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onSlideComplete: (() -> Unit)? = null

    private val thumbRadius by lazy { (52 * resources.displayMetrics.density).toInt() }
    private val trackHeight by lazy { (56 * resources.displayMetrics.density).toInt() }
    private val trackCornerRadius by lazy { (28 * resources.displayMetrics.density) }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF.toInt()
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF06C167.toInt()
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF06C167.toInt()
        style = Paint.Style.FILL
    }
    private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4 * resources.displayMetrics.density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 16 * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private var thumbX = 0f
    private var isDragging = false
    private var maxTravel = 0f

    private val trackRect = RectF()
    private val progressRect = RectF()
    private val trackPath = android.graphics.Path()

    private val trackStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val top = (h - trackHeight) / 2f
        val bottom = top + trackHeight
        trackRect.set(0f, top, w.toFloat(), bottom)
        maxTravel = w.toFloat() - thumbRadius * 2
        thumbX = thumbRadius.toFloat()

        trackPath.reset()
        trackPath.addRoundRect(trackRect, trackCornerRadius, trackCornerRadius, android.graphics.Path.Direction.CW)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, trackHeight + 16 * resources.displayMetrics.density.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.clipPath(trackPath)

        // Full track background
        canvas.drawRoundRect(trackRect, trackCornerRadius, trackCornerRadius, trackPaint)

        // Progress fill (green part)
        if (thumbX > thumbRadius) {
            progressRect.set(trackRect.left, trackRect.top, thumbX, trackRect.bottom)
            canvas.drawRoundRect(progressRect, trackCornerRadius, trackCornerRadius, progressPaint)
        }

        canvas.restore()

        // Track border
        canvas.drawRoundRect(trackRect, trackCornerRadius, trackCornerRadius, trackStrokePaint)

        // Arrow hint text on track (behind thumb)
        if (!isDragging && thumbX <= thumbRadius + 10) {
            val textX = width / 2f
            val textY = trackRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText("→  ПРИНЯТЬ  →", textX, textY, textPaint)
        }

        // Thumb circle
        val cy = trackRect.centerY()
        canvas.drawCircle(thumbX, cy, thumbRadius.toFloat(), thumbPaint)
        canvas.drawCircle(thumbX, cy, thumbRadius.toFloat(), thumbStrokePaint)

        // Arrow inside thumb
        drawArrow(canvas, thumbX, cy)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float) {
        val size = 10 * resources.displayMetrics.density
        val path = android.graphics.Path().apply {
            moveTo(cx - size * 0.6f, cy - size * 0.5f)
            lineTo(cx + size * 0.4f, cy)
            lineTo(cx - size * 0.6f, cy + size * 0.5f)
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val dx = event.x - thumbX
                val dy = event.y - trackRect.centerY()
                if (dx * dx + dy * dy < (thumbRadius * 2.5f) * (thumbRadius * 2.5f)) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                thumbX = event.x.coerceIn(thumbRadius.toFloat(), maxTravel + thumbRadius)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return false
                isDragging = false

                val progress = (thumbX - thumbRadius) / maxTravel
                if (progress >= 0.85f) {
                    // Complete — snap to end and fire
                    animateThumbTo(maxTravel + thumbRadius)
                    onSlideComplete?.invoke()
                } else {
                    // Snap back
                    animateThumbTo(thumbRadius.toFloat())
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun animateThumbTo(targetX: Float) {
        ValueAnimator.ofFloat(thumbX, targetX).apply {
            duration = 250
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener {
                thumbX = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setAccentColor(color: Int) {
        thumbPaint.color = color
        progressPaint.color = color
        invalidate()
    }

    fun reset() {
        animateThumbTo(thumbRadius.toFloat())
    }
}
