package com.aaronhrndz.ultraremapper

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.*

/**
 * Non-blocking system overlay showing futuristic perimeter ring animation
 * + solid (or user-configured opacity) mode-colored native TextView pill with soft glow.
 */
object ModeOverlayManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeRoot: FrameLayout? = null
    private var activeRingView: ModeOverlayCanvasView? = null
    private var activeWm: WindowManager? = null

    fun show(context: Context, mode: ButtonMode, config: ModeOverlayConfig = ModeStore.getOverlayConfig(context)) {
        val appCtx = context.applicationContext
        if (!Settings.canDrawOverlays(appCtx)) return
        mainHandler.post {
            dismissNow()
            try {
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                val dm = appCtx.resources.displayMetrics
                val screenMinPx = min(dm.widthPixels, dm.heightPixels).toFloat()
                val scale = (config.pillSizePct / 100f) * (screenMinPx / 400f)

                val root = FrameLayout(appCtx).apply {
                    clipChildren = false
                    clipToPadding = false
                }

                // 1. Perimeter Ring Canvas View (no forced LAYER_TYPE_HARDWARE)
                val ringView = ModeOverlayCanvasView(appCtx)
                root.addView(
                    ringView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )

                // 2. Native TextView Pill — guarantees 100% solid opaque pixels when pillAlphaPct == 100
                var pillView: TextView? = null
                if (config.showPill && mode.name.isNotBlank()) {
                    val modeColorOpaque = Color.rgb(
                        Color.red(mode.colorArgb),
                        Color.green(mode.colorArgb),
                        Color.blue(mode.colorArgb)
                    )
                    val bgAlphaInt = ((config.pillAlphaPct.coerceIn(20, 100) / 100f) * 255f).roundToInt()
                    val pillFillColor = Color.argb(
                        bgAlphaInt,
                        Color.red(modeColorOpaque),
                        Color.green(modeColorOpaque),
                        Color.blue(modeColorOpaque)
                    )
                    val lum = (0.299f * Color.red(modeColorOpaque) +
                        0.587f * Color.green(modeColorOpaque) +
                        0.114f * Color.blue(modeColorOpaque)) / 255f
                    val contrastTextColor = if (lum > 0.58f) Color.rgb(10, 13, 18) else Color.WHITE

                    val bgDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 999f * dm.density
                        setColor(pillFillColor)
                    }

                    val padH = (18f * scale).roundToInt().coerceAtLeast(8)
                    val padV = (8f * scale).roundToInt().coerceAtLeast(4)

                    val tv = TextView(appCtx).apply {
                        text = mode.name
                        setTextColor(contrastTextColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * scale)
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        includeFontPadding = false
                        background = bgDrawable
                        setPadding(padH, padV, padH, padV)
                        elevation = 14f * dm.density
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            outlineAmbientShadowColor = modeColorOpaque
                            outlineSpotShadowColor = modeColorOpaque
                        }
                        alpha = 0f
                    }

                    val marginPx = (36f * (screenMinPx / 400f)).roundToInt()
                    val lp = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = when (config.pillPosition) {
                            ModeOverlayConfig.POS_CENTER -> Gravity.CENTER
                            ModeOverlayConfig.POS_BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        }
                        if (config.pillPosition == ModeOverlayConfig.POS_TOP) topMargin = marginPx
                        if (config.pillPosition == ModeOverlayConfig.POS_BOTTOM) bottomMargin = marginPx
                    }
                    root.addView(tv, lp)
                    pillView = tv
                }

                val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    flags,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    alpha = 1.0f
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }

                wm.addView(root, params)
                activeRoot = root
                activeRingView = ringView
                activeWm = wm

                ringView.play(
                    colorArgb = mode.colorArgb,
                    cfg = config,
                    onFrame = { t ->
                        pillView?.let { tv ->
                            val pillAlpha = when {
                                t < 0.08f -> (t / 0.08f).coerceIn(0f, 1f)
                                t > 0.86f -> ((1f - (t - 0.86f) / 0.08f)).coerceIn(0f, 1f)
                                else -> 1f
                            }
                            val entryScale = if (t < 0.10f) 0.92f + (t / 0.10f) * 0.08f else 1f
                            tv.alpha = pillAlpha
                            tv.scaleX = entryScale
                            tv.scaleY = entryScale
                        }
                    },
                    onEnd = { dismissNow() }
                )
            } catch (_: Throwable) {
                dismissNow()
            }
        }
    }

    private fun dismissNow() {
        val root = activeRoot
        val ring = activeRingView
        val wm = activeWm
        activeRoot = null
        activeRingView = null
        activeWm = null
        ring?.stop()
        if (root != null) {
            try {
                wm?.removeViewImmediate(root)
            } catch (_: Throwable) {
                try {
                    wm?.removeView(root)
                } catch (_: Throwable) {}
            }
        }
    }
}

/**
 * Custom canvas view that renders the 4 approved futuristic perimeter ring animations
 * driven by real wall-clock uptimeMillis (immune to system Animator Duration Scale).
 */
class ModeOverlayCanvasView(context: Context) : View(context) {

    private var modeColor: Int = 0xFFFF5500.toInt()
    private var config: ModeOverlayConfig = ModeOverlayConfig()
    private var progress: Float = 1f
    private var startUptimeMs: Long = 0L
    private var isPlaying: Boolean = false
    private var onFrameCallback: ((Float) -> Unit)? = null
    private var onEndCallback: (() -> Unit)? = null

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val elapsed = SystemClock.uptimeMillis() - startUptimeMs
            val dur = config.durationMs.toFloat().coerceIn(350f, 3000f)
            progress = (elapsed / dur).coerceIn(0f, 1f)
            onFrameCallback?.invoke(progress)
            invalidate()
            if (progress < 1f) {
                postOnAnimation(this)
            } else {
                isPlaying = false
                onEndCallback?.invoke()
            }
        }
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val arcRect = RectF()
    private val sweepMatrix = Matrix()

    fun play(
        colorArgb: Int,
        cfg: ModeOverlayConfig,
        onFrame: ((Float) -> Unit)? = null,
        onEnd: (() -> Unit)? = null
    ) {
        stop()
        modeColor = Color.rgb(Color.red(colorArgb), Color.green(colorArgb), Color.blue(colorArgb))
        config = cfg
        onFrameCallback = onFrame
        onEndCallback = onEnd
        progress = 0f
        startUptimeMs = SystemClock.uptimeMillis()
        isPlaying = true
        postOnAnimation(frameRunnable)
    }

    fun stop() {
        isPlaying = false
        removeCallbacks(frameRunnable)
        onFrameCallback = null
        onEndCallback = null
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

        val env = when {
            t < 0.14f -> t / 0.14f
            t > 0.72f -> ((1f - (t - 0.72f) / 0.28f).coerceIn(0f, 1f)).pow(1.25f)
            else -> 1f
        }
        val a = env * maxAlpha

        val speed = config.speedPct / 100f
        when (config.style) {
            ModeOverlayConfig.STYLE_BREATHING -> drawBreathing(canvas, cx, cy, r, t * speed, thickPct, a)
            ModeOverlayConfig.STYLE_ORBITAL -> drawOrbital(canvas, cx, cy, r, (t * speed).coerceAtMost(1f), thickPct, a)
            ModeOverlayConfig.STYLE_DUALWAVE -> drawDualWave(canvas, cx, cy, r, (t * speed).coerceAtMost(1f), thickPct, maxAlpha, a)
            ModeOverlayConfig.STYLE_TWINARC -> drawTwinArc(canvas, cx, cy, r, t * speed, thickPct, a)
            else -> drawBreathing(canvas, cx, cy, r, t * speed, thickPct, a)
        }
    }

    private fun drawBreathing(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float, thickPct: Float, a: Float) {
        val pulse = sin(t * PI.toFloat() * 2.2f) * 0.045f
        val innerR = (r * (1f - thickPct - pulse)).coerceIn(r * 0.15f, r * 0.96f)
        val startStop = (innerR / r).coerceIn(0.05f, 0.95f)
        val mid1 = startStop + (1f - startStop) * 0.45f
        val mid2 = startStop + (1f - startStop) * 0.85f

        fillPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                withAlpha(modeColor, 0f),
                withAlpha(modeColor, 0f),
                withAlpha(modeColor, a * 0.55f),
                withAlpha(modeColor, a),
                withAlpha(modeColor, a)
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

        val innerStop = ((r - ringW * 1.4f) / r).coerceIn(0.1f, 0.95f)
        fillPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(modeColor, 0f), withAlpha(modeColor, 0f), withAlpha(modeColor, a * 0.65f)),
            floatArrayOf(0f, innerStop, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null

        val arcR = r - ringW * 0.35f
        arcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        strokePaint.color = withAlpha(modeColor, a)
        strokePaint.strokeWidth = ringW * 0.55f
        canvas.drawArc(arcRect, startDeg, sweepDeg, false, strokePaint)

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
            canvas.drawCircle(cx, cy, rad, strokePaint)
        }
        val innerStop = (1f - thickPct).coerceIn(0.1f, 0.95f)
        fillPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(modeColor, 0f), withAlpha(modeColor, 0f), withAlpha(modeColor, a * 0.75f)),
            floatArrayOf(0f, innerStop, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null
    }

    private fun drawTwinArc(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float, thickPct: Float, a: Float) {
        val rotDeg = t * 450f
        val ringW = r * thickPct

        val baseStop = ((r - ringW * 1.3f) / r).coerceIn(0.1f, 0.95f)
        fillPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(modeColor, 0f), withAlpha(modeColor, 0f), withAlpha(modeColor, a * 0.65f)),
            floatArrayOf(0f, baseStop, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null

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
}
