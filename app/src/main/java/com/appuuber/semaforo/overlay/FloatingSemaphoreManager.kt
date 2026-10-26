package com.appuuber.semaforo.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.appuuber.semaforo.domain.RideEvaluation
import java.util.Locale
import kotlin.math.abs

/**
 * Pílula Flutuante Ultra-Leve (Overlay Semáforo).
 * - Renderização nativa vetorial direta sem bitmaps ou imagens estáticas.
 * - Animação suave de entrada com escala elástica (Overshoot).
 * - Feedback tátil (Haptic) nas interações de toque e arraste.
 * - FLAG_NOT_FOCUSABLE para nunca reter o foco ou bloquear toques no app da Uber/99.
 */
class FloatingSemaphoreManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var dotIndicator: View? = null
    private var titleText: TextView? = null
    private var metricsText: TextView? = null
    private var subMetricsText: TextView? = null
    private var containerDrawable: GradientDrawable? = null

    private val autoHideRunnable = Runnable { hide() }

    fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    fun showEvaluation(evaluation: RideEvaluation) {
        if (!canDrawOverlays()) return

        mainHandler.post {
            mainHandler.removeCallbacks(autoHideRunnable)
            overlayView?.animate()?.cancel()
            ensureOverlayCreated()
            bindEvaluation(evaluation)

            // Animação de entrada rápida e fluida sem consumir CPU
            overlayView?.let { view ->
                view.scaleX = 0.85f
                view.scaleY = 0.85f
                view.alpha = 0f
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(1.0f)
                    .setDuration(220L)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }

            mainHandler.postDelayed(autoHideRunnable, AUTO_DISMISS_MS)
        }
    }

    fun hide(immediate: Boolean = false) {
        mainHandler.post {
            mainHandler.removeCallbacks(autoHideRunnable)
            val view = overlayView ?: return@post
            view.animate().cancel()
            if (immediate) {
                safeRemoveView(view)
                overlayView = null
                return@post
            }
            view.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .alpha(0f)
                .setDuration(160L)
                .withEndAction {
                    safeRemoveView(view)
                    if (overlayView === view) {
                        overlayView = null
                    }
                }
                .start()
        }
    }

    private fun safeRemoveView(view: View) {
        try {
            if (view.isAttachedToWindow) {
                windowManager.removeViewImmediate(view)
            } else {
                windowManager.removeView(view)
            }
        } catch (_: Exception) {
            // Ignora com segurança caso o sistema operacional já tenha recolhido a view
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureOverlayCreated() {
        if (overlayView != null) return

        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        // Container Cápsula Translúcido com Alta Profundidade (Glassmorphism ultra-leve)
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            elevation = dp(14).toFloat()
        }

        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(32).toFloat()
            setColor(Color.parseColor("#F5090D16")) // Slate escuro fosco de alta opacidade
            setStroke(dp(2), Color.parseColor("#334155"))
        }
        containerDrawable = bgDrawable
        rootLayout.background = bgDrawable

        // Lâmpada de Status Neon (Glow suave)
        val dot = View(context).apply {
            val size = dp(20)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(12)
            }
        }
        dotIndicator = dot
        rootLayout.addView(dot)

        // Coluna de tipografia moderna
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val statusTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            letterSpacing = 0.05f
        }
        titleText = statusTv
        textColumn.addView(statusTv)

        val primaryMetricsTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(1), 0, dp(1))
        }
        metricsText = primaryMetricsTv
        textColumn.addView(primaryMetricsTv)

        val secondaryMetricsTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor("#94A3B8"))
        }
        subMetricsText = secondaryMetricsTv
        textColumn.addView(secondaryMetricsTv)

        rootLayout.addView(textColumn)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(100)
        }
        layoutParams = params

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        rootLayout.setOnTouchListener { v, event ->
            val lp = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = initialX + (event.rawX - initialTouchX).toInt()
                    lp.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(rootLayout, lp)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val movedX = abs(event.rawX - initialTouchX)
                    val movedY = abs(event.rawY - initialTouchY)
                    if (movedX < dp(8) && movedY < dp(8)) {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        hide()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(rootLayout, params)
        overlayView = rootLayout
    }

    private fun bindEvaluation(evaluation: RideEvaluation) {
        val density = context.resources.displayMetrics.density
        val strokePx = (2.2f * density).toInt()
        val color = evaluation.status.hexColor

        dotIndicator?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

        containerDrawable?.setStroke(strokePx, color)

        val ptBr = Locale("pt", "BR")
        val badges = buildList {
            add(statusLabelWithStops(evaluation))
            if (evaluation.isRadarOffer) add("📡 RADAR")
            evaluation.passengerRating?.let { add(String.format(ptBr, "★ %.2f", it)) }
        }.joinToString(" • ")

        titleText?.apply {
            text = badges
            setTextColor(color)
        }

        metricsText?.text = String.format(
            ptBr,
            "R$ %.2f/km  •  R$ %.0f/h",
            evaluation.earningsPerKm,
            evaluation.earningsPerHour
        )

        subMetricsText?.text = String.format(
            ptBr,
            "Líq: R$ %.2f  (%.1f km • %d min)",
            evaluation.netProfit,
            evaluation.totalDistanceKm,
            evaluation.totalDurationMin
        )
    }

    companion object {
        private const val AUTO_DISMISS_MS = 12_000L

        /** "VALE A PENA" ou "VALE A PENA (paradas 2)" quando a oferta tem paradas. */
        internal fun statusLabelWithStops(evaluation: RideEvaluation): String =
            if (evaluation.stopCount > 0) "${evaluation.status.label} (paradas ${evaluation.stopCount})"
            else evaluation.status.label
    }
}
