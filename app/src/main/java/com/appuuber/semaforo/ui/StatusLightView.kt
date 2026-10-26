package com.appuuber.semaforo.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.appuuber.semaforo.domain.HealthLevel

/**
 * Semáforo Fluido em Canvas Nativo (Zero dependência de drawables pesados ou bibliotecas externas).
 * - Renderização 100% vetorial de altíssima performance (60/120 FPS).
 * - Transição suave com interpolação de escala e brilho dinâmico (glow) na troca de estado.
 * - Ciclo de vida estritamente gerenciado: cancela animadores no onDetachedFromWindow (Zero Memory Leaks).
 */
class StatusLightView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var targetLevel: HealthLevel = HealthLevel.RED
    private var transitionProgress = 1.0f

    private var animator: ValueAnimator? = null

    // Tintas pré-alocadas para evitar garbage collection constante em draws
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#090D16") // Slate 950 ultra-profundo
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.STROKE
    }
    private val lampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rect = RectF()

    fun setLevel(newLevel: HealthLevel, animate: Boolean = true) {
        if (newLevel == targetLevel && isAttachedToWindow) return
        targetLevel = newLevel

        animator?.cancel()

        if (animate && isAttachedToWindow) {
            animator = ValueAnimator.ofFloat(0.4f, 1.0f).apply {
                duration = 350L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { va ->
                    transitionProgress = va.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            transitionProgress = 1.0f
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        // Dimensões compactas e aerodinâmicas (46dp x 120dp)
        setMeasuredDimension((46 * d).toInt(), (120 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = 1.5f * resources.displayMetrics.density
        borderPaint.strokeWidth = stroke

        // Corpo cápsula escuro fosco de alta profundidade
        rect.set(stroke, stroke, w - stroke, h - stroke)
        val corner = w / 2f
        canvas.drawRoundRect(rect, corner, corner, bodyPaint)
        canvas.drawRoundRect(rect, corner, corner, borderPaint)

        val r = w * 0.28f
        val cx = w / 2f

        // Cores vibrantes premium (Red Rose, Amber Gold, Emerald Neon)
        val slots = listOf(
            HealthLevel.RED to Color.parseColor("#F43F5E"),
            HealthLevel.YELLOW to Color.parseColor("#F59E0B"),
            HealthLevel.GREEN to Color.parseColor("#10B981")
        )

        slots.forEachIndexed { index, (lv, color) ->
            val cy = h * (index + 1) / 4f
            val isActive = lv == targetLevel

            if (isActive) {
                // Brilho difuso atenuado (Glow Neon)
                glowPaint.color = color
                glowPaint.alpha = (75 * transitionProgress).toInt()
                val activeRadius = r * (1.2f + 0.35f * transitionProgress)
                canvas.drawCircle(cx, cy, activeRadius, glowPaint)

                // Lâmpada acesa vibrante com pulso suave
                lampPaint.color = color
                lampPaint.alpha = (255 * transitionProgress).toInt().coerceIn(200, 255)
                canvas.drawCircle(cx, cy, r * (0.9f + 0.1f * transitionProgress), lampPaint)
            } else {
                // Lâmpada inativa (subtom elegante com leve transparência)
                lampPaint.color = color
                lampPaint.alpha = 26
                canvas.drawCircle(cx, cy, r * 0.82f, lampPaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
