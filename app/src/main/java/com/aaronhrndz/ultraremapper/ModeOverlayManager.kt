package com.aaronhrndz.ultraremapper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.*

/**
 * Non-blocking system overlay showing futuristic perimeter ring animation
 * + borderless dark frosted pill with mode name upon mode switch.
 */
object ModeOverlayManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeView: ModeOverlayCanvasView? = null
    private var activeWm: WindowManager? = null

    fun show(context: Context, mode: ButtonMode, config: ModeOverlayConfig = ModeStore.getOverlayConfig(context)) {
        val appCtx = context.applicationContext
        if (!Settings.canDrawOverlays(appCtx)) return
        mainHandler.post {
            dismissNow()
            try {
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                val view = ModeOverlayCanvasView(appCtx).apply {
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                }
                val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    flags,
                    PixelFormat.TRANSLUCENT
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                wm.addView(view, params)
                activeView = view
                activeWm = wm
                view.play(mode.name, mode.colorArgb, config) {
                    dismissNow()
                }
            } catch (_: Throwable) {
                dismissNow()
            }
        }
    }

    private fun dismissNow() {
        val view = activeView
        val wm = activeWm
        activeView = null
        activeWm = null
        if (view != null) {
            view.stop()
            try {
                wm?.removeViewImmediate(view)
            } catch (_: Throwable) {
                try {
                    wm?.removeView(view)
                } catch (_: Throwable) {}
            }
        }
    }
}

/**
 * Reusable custom canvas view that renders the 4 approved futuristic animations
 * and the borderless dark frosted pill (used by both system overlay and live preview).
 */
class ModeOverlayCanvasView(context: Context) : View(context) {

    private var modeName: String = ""
    private var modeColor: Int = 0xFFFF5500.toInt()
    private var config: ModeOverlayConfig = ModeOverlayConfig()
    private var progress: Float = 1f
    private var animator: ValueAnimator? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pillDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val arcRect = RectF()
    private val pillRect = RectF()
    private val sweepMatrix = Matrix()

    fun play(name: String, colorArgb: Int, cfg: ModeOverlayConfig, onEnd: (() -> Unit)? = null) {
        stop()
        modeName = name
        modeColor = colorArgb
        config = cfg
        progress = 0f

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = cfg.durationMs.toLong().coerceIn(350L, 2500L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    private fun withAlpha(color: Int, alphaFloat: Float): Int {
        val a = (alphaFloat.coerceIn(0f, 1f) * 255f).roundToInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = progress
        if (t >= 1f || width <= 0 || height <= 0) return

        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val thickPct = config.thicknessPct / 100f
        val maxAlpha = config.intensityPct / 100f

        val env = if (t < 0.18f) {
            t / 0.18f
        } else {
            ((1f - (t - 0.18f) / 0.82f).coerceIn(0f, 1f)).pow(1.35f)
        }
        val a = env * maxAlpha

        when (config.style) {
            ModeOverlayConfig.STYLE_BREATHING -> drawBreathing(canvas, cx, cy, r, t, thickPct, a)
            ModeOverlayConfig.STYLE_ORBITAL -> drawOrbital(canvas, cx, cy, r, t, thickPct, a)
            ModeOverlayConfig.STYLE_DUALWAVE -> drawDualWave(canvas, cx, cy, r, t, thickPct, maxAlpha, a)
            ModeOverlayConfig.STYLE_TWINARC -> drawTwinArc(canvas, cx, cy, r, t, thickPct, a)
            else -> drawBreathing(canvas, cx, cy, r, t, thickPct, a)
        }

        if (config.showPill && modeName.isNotBlank()) {
            drawPill(canvas, cx, cy, r, t)
        }
    }

    private fun drawBreathing(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float, thickPct: Float, a: Float) {
        val pulse = sin(t * PI.toFloat() * 2.2f) * 0.045f
        val innerR = (r * (1f - thickPct - pulse)).coerceIn(r * 0.15f, r * 0.96f)
        val startStop = (innerR / r).coerceIn(0.05f, 0.95f)
        val mid1 = startStop + (1f - startStop) * 0.55f
        val mid2 = startStop + (1f - startStop) * 0.88f

        fillPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                withAlpha(modeColor, 0f),
                withAlpha(modeColor, 0f),
                withAlpha(modeColor, a * 0.45f),
                withAlpha(modeColor, a * 0.95f),
                withAlpha(Color.WHITE, a * 0.85f)
            ),
            floatArrayOf(0f, startStop, mid1, mid2, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null
    }

    private fun drawOrbital(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float, thickPct: Float, a: Float) {
        val sweepDeg = min(1f, t / 0.65f) * 360f
        val startDeg = -90f
        val ringW = r * thickPct

        // Soft base glow
        val innerStop = ((r - ringW * 1.4f) / r).coerceIn(0.1f, 0.95f)
        fillPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(modeColor, 0f), withAlpha(modeColor, 0f), withAlpha(modeColor, a * 0.55f)),
            floatArrayOf(0f, innerStop, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null

        // Bright sweeping arc
        val arcR = r - ringW * 0.35f
        arcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        strokePaint.color = withAlpha(modeColor, a)
        strokePaint.strokeWidth = ringW * 0.55f
        strokePaint.setShadowLayer(18f, 0f, 0f, modeColor)
        canvas.drawArc(arcRect, startDeg, sweepDeg, false, strokePaint)
        strokePaint.clearShadowLayer()

        // Bright white tip
        val tipRad = Math.toRadians((startDeg + sweepDeg).toDouble())
        val tx = (cx + cos(tipRad) * arcR).toFloat()
        val ty = (cy + sin(tipRad) * arcR).toFloat()
        fillPaint.color = withAlpha(Color.WHITE, a)
        canvas.drawCircle(tx, ty, r * 0.025f, fillPaint)
    }

    private fun drawDualWave(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        t: Float,
        thickPct: Float,
        maxAlpha: Float,
        a: Float
    ) {
        val delays = floatArrayOf(0f, 0.22f)
        for (delay in delays) {
            val wt = (t - delay) / (1f - delay)
            if (wt <= 0f || wt >= 1f) continue
            val wa = sin(wt * PI.toFloat()) * maxAlpha
            val rad = r - wt * (r * thickPct * 1.6f)
            strokePaint.color = withAlpha(modeColor, wa)
            strokePaint.strokeWidth = max(2f, (1f - wt) * (r * 0.07f))
            strokePaint.setShadowLayer(14f, 0f, 0f, modeColor)
            canvas.drawCircle(cx, cy, rad, strokePaint)
            strokePaint.clearShadowLayer()
        }
        val innerStop = (1f - thickPct).coerceIn(0.1f, 0.95f)
        fillPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(modeColor, 0f), withAlpha(modeColor, 0f), withAlpha(modeColor, a * 0.65f)),
            floatArrayOf(0f, innerStop, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null
    }

    private fun drawTwinArc(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float, thickPct: Float, a: Float) {
        val rotDeg = t * 450f
        val ringW = r * thickPct

        // Base perimeter glow
        val baseStop = ((r - ringW * 1.3f) / r).coerceIn(0.1f, 0.95f)
        fillPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(modeColor, 0f), withAlpha(modeColor, 0f), withAlpha(modeColor, a * 0.55f)),
            floatArrayOf(0f, baseStop, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null

        // Pure mode color twin diffused arcs masked softly to perimeter ring
        val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        val sweep = SweepGradient(
            cx, cy,
            intArrayOf(
                withAlpha(modeColor, 0f),
                withAlpha(modeColor, a * 0.85f),
                withAlpha(modeColor, a),
                withAlpha(modeColor, a * 0.85f),
                withAlpha(modeColor, 0f),
                withAlpha(modeColor, a * 0.85f),
                withAlpha(modeColor, a),
                withAlpha(modeColor, a * 0.85f),
                withAlpha(modeColor, 0f)
            ),
            floatArrayOf(0f, 0.14f, 0.22f, 0.30f, 0.46f, 0.64f, 0.72f, 0.80f, 0.96f)
        )
        sweepMatrix.setRotate(rotDeg, cx, cy)
        sweep.setLocalMatrix(sweepMatrix)
        fillPaint.shader = sweep
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null

        val innerStop = (1f - thickPct * 1.25f).coerceIn(0.1f, 0.94f)
        maskPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(60, 0, 0, 0), Color.BLACK),
            floatArrayOf(0f, innerStop, innerStop + (1f - innerStop) * 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, maskPaint)
        maskPaint.shader = null
        canvas.restoreToCount(save)
    }

    private fun drawPill(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        // 100% opacity almost entire duration; fade-out same speed (0.08) as fade-in, ending slightly before ring
        val pillAlpha = when {
            t < 0.08f -> t / 0.08f
            t > 0.86f -> ((1f - (t - 0.86f) / 0.08f)).coerceIn(0f, 1f)
            else -> 1f
        }
        if (pillAlpha <= 0.01f) return

        val scale = (config.pillSizePct / 100f) * (r / 200f)
        val entryScale = if (t < 0.10f) 0.92f + (t / 0.10f) * 0.08f else 1f
        val s = scale * entryScale

        pillTextPaint.textSize = 15f * s
        pillTextPaint.alpha = (pillAlpha * 255f).roundToInt()

        val textW = pillTextPaint.measureText(modeName)
        val dotR = 5f * s
        val padH = 15f * s
        val padV = 8f * s
        val gap = 9f * s
        val pillW = padH * 2f + dotR * 2f + gap + textW
        val pillH = padV * 2f + pillTextPaint.textSize * 1.15f

        val pillCy = when (config.pillPosition) {
            ModeOverlayConfig.POS_CENTER -> cy
            ModeOverlayConfig.POS_BOTTOM -> height - (38f * (r / 200f)) - pillH / 2f
            else -> (38f * (r / 200f)) + pillH / 2f
        }

        pillRect.set(cx - pillW / 2f, pillCy - pillH / 2f, cx + pillW / 2f, pillCy + pillH / 2f)

        // Dark frosted background — ZERO border
        pillBgPaint.color = Color.argb((pillAlpha * 218f).roundToInt(), 8, 10, 15)
        pillBgPaint.setShadowLayer(16f * s, 0f, 5f * s, Color.argb((pillAlpha * 160f).roundToInt(), 0, 0, 0))
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, pillBgPaint)
        pillBgPaint.clearShadowLayer()

        // Glowing color dot
        val dotCx = pillRect.left + padH + dotR
        pillDotPaint.color = withAlpha(modeColor, pillAlpha)
        pillDotPaint.setShadowLayer(8f * s, 0f, 0f, modeColor)
        canvas.drawCircle(dotCx, pillCy, dotR, pillDotPaint)
        pillDotPaint.clearShadowLayer()

        // Mode name text
        val fm = pillTextPaint.fontMetrics
        val textBaseline = pillCy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(modeName, dotCx + dotR + gap, textBaseline, pillTextPaint)
    }
}
