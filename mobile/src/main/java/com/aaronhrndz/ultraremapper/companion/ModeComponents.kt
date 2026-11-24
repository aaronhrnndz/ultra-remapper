package com.aaronhrndz.ultraremapper.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlin.math.*

private val UltraOrange = Color(0xFFFF5500)

/**
 * Interactive circular watch preview + full animation editor for Mobile Companion.
 */
@Composable
fun MobileAnimationEditorScreen(
    initialConfig: WatchOverlayConfig,
    modes: List<WatchButtonMode>,
    onSaveAndPreviewOnWatch: (WatchOverlayConfig, Boolean) -> Unit,
    onBack: () -> Unit
) {
    val palette = LocalPalette.current
    var cfg by remember { mutableStateOf(initialConfig) }
    var previewIdx by remember { mutableIntStateOf(0) }
    var previewTrigger by remember { mutableIntStateOf(1) }

    val previewModes = remember(modes) {
        if (modes.isNotEmpty()) modes else listOf(
            WatchButtonMode("m1", "Mode 1", 0xFFFF5500.toInt(), emptyMap()),
            WatchButtonMode("m2", "Music", 0xFF00E676.toInt(), emptyMap())
        )
    }
    val activePreviewMode = previewModes[previewIdx % previewModes.size]

    fun updateCfg(next: WatchOverlayConfig, sendToWatch: Boolean = true, previewOnWatch: Boolean = false) {
        cfg = next
        previewTrigger++
        if (sendToWatch) {
            onSaveAndPreviewOnWatch(next, previewOnWatch)
        }
    }

    val styles = listOf(
        "breathing" to stringResource(R.string.anim_style_breathing),
        "orbital" to stringResource(R.string.anim_style_orbital),
        "dualwave" to stringResource(R.string.anim_style_dualwave),
        "twinarc" to stringResource(R.string.anim_style_twinarc)
    )
    val positions = listOf(
        "top" to stringResource(R.string.anim_pill_pos_top),
        "center" to stringResource(R.string.anim_pill_pos_center),
        "bottom" to stringResource(R.string.anim_pill_pos_bottom)
    )

    Scaffold(
        containerColor = palette.bg,
        topBar = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "←",
                    color = UltraOrange,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onBack)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.anim_editor_title),
                    color = palette.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Live Watch Circular Preview
            item(key = "live_preview") {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(220.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF141821))
                            .border(4.dp, Color(0xFF2B3242), CircleShape)
                            .clickable {
                                previewIdx = (previewIdx + 1) % previewModes.size
                                previewTrigger++
                            }
                    ) {
                        AndroidView(
                            factory = { ctx -> MobileWatchPreviewCanvasView(ctx) },
                            update = { view ->
                                view.play(
                                    modeName = activePreviewMode.name,
                                    modeColor = activePreviewMode.colorArgb,
                                    config = cfg,
                                    trigger = previewTrigger
                                )
                            },
                            modifier = Modifier
                                .size(204.dp)
                                .clip(CircleShape)
                        )
                    }
                }
            }

            // Test on Watch button
            item(key = "test_btn") {
                Button(
                    onClick = {
                        previewIdx = (previewIdx + 1) % previewModes.size
                        previewTrigger++
                        onSaveAndPreviewOnWatch(cfg, true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = UltraOrange),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.anim_preview_on_watch),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Style Selector
            item(key = "style_selector") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.cardBg)
                        .border(1.dp, palette.border, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.anim_style_label),
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        styles.chunked(2).forEach { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                row.forEach { (key, label) ->
                                    val sel = cfg.style == key
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (sel) UltraOrange.copy(alpha = 0.18f) else palette.bg)
                                            .border(
                                                1.5.dp,
                                                if (sel) UltraOrange else palette.border,
                                                RoundedCornerShape(12.dp)
                                            )
                                            .clickable { updateCfg(cfg.copy(style = key)) }
                                            .padding(vertical = 10.dp, horizontal = 8.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (sel) UltraOrange else palette.textPrimary,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sliders: Thickness, Duration, Intensity
            item(key = "ring_sliders") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.cardBg)
                        .border(1.dp, palette.border, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SliderParamRow(
                        label = stringResource(R.string.anim_thickness_label),
                        valueText = "${cfg.thicknessPct}%",
                        value = cfg.thicknessPct.toFloat(),
                        valueRange = 6f..26f,
                        onValueChange = { cfg = cfg.copy(thicknessPct = it.roundToInt()) },
                        onValueChangeFinished = { updateCfg(cfg) }
                    )
                    SliderParamRow(
                        label = stringResource(R.string.anim_duration_label),
                        valueText = "${cfg.durationMs} ms",
                        value = cfg.durationMs.toFloat(),
                        valueRange = 400f..2000f,
                        onValueChange = { cfg = cfg.copy(durationMs = (it / 50f).roundToInt() * 50) },
                        onValueChangeFinished = { updateCfg(cfg) }
                    )
                    SliderParamRow(
                        label = stringResource(R.string.anim_intensity_label),
                        valueText = "${cfg.intensityPct}%",
                        value = cfg.intensityPct.toFloat(),
                        valueRange = 40f..100f,
                        onValueChange = { cfg = cfg.copy(intensityPct = it.roundToInt()) },
                        onValueChangeFinished = { updateCfg(cfg) }
                    )
                }
            }

            // Glass Pill Controls
            item(key = "pill_controls") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.cardBg)
                        .border(1.dp, palette.border, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.anim_pill_toggle),
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Switch(
                            checked = cfg.showPill,
                            onCheckedChange = { updateCfg(cfg.copy(showPill = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = UltraOrange)
                        )
                    }

                    if (cfg.showPill) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            positions.forEach { (key, label) ->
                                val sel = cfg.pillPosition == key
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (sel) UltraOrange else palette.bg)
                                        .border(1.dp, if (sel) UltraOrange else palette.border, RoundedCornerShape(10.dp))
                                        .clickable { updateCfg(cfg.copy(pillPosition = key)) }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (sel) Color.White else palette.textSecondary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        SliderParamRow(
                            label = stringResource(R.string.anim_pill_size),
                            valueText = "${cfg.pillSizePct}%",
                            value = cfg.pillSizePct.toFloat(),
                            valueRange = 70f..150f,
                            onValueChange = { cfg = cfg.copy(pillSizePct = (it / 5f).roundToInt() * 5) },
                            onValueChangeFinished = { updateCfg(cfg) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderParamRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    val palette = LocalPalette.current
    Column {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label, color = palette.textSecondary, fontSize = 13.sp)
            Text(valueText, color = UltraOrange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = UltraOrange,
                activeTrackColor = UltraOrange
            )
        )
    }
}

/**
 * Mobile RGB Color Picker Dialog matching the watch's touch-and-hold preview workflow.
 */
@Composable
fun MobileRgbColorPickerDialog(
    initialColorArgb: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    var pickedColor by remember { mutableIntStateOf(initialColorArgb) }
    var awaitingConfirm by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(palette.cardBg)
                .border(1.dp, palette.border, RoundedCornerShape(24.dp))
                .padding(18.dp)
        ) {
            Text(
                text = stringResource(R.string.color_picker_title),
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF080A0F))
            ) {
                AndroidView(
                    factory = { ctx ->
                        MobileRgbWheelView(
                            context = ctx,
                            initialColor = initialColorArgb,
                            onDragging = { c ->
                                pickedColor = c
                                awaitingConfirm = false
                            },
                            onReleased = { c ->
                                pickedColor = c
                                awaitingConfirm = true
                            }
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                text = if (awaitingConfirm) stringResource(R.string.color_picker_confirm_prompt)
                else stringResource(R.string.color_picker_drag_hint),
                color = palette.textSecondary,
                fontSize = 12.sp
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.btn_cancel), color = palette.textPrimary)
                }
                Button(
                    onClick = { onConfirm(pickedColor) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(pickedColor)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.btn_confirm), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private class MobileRgbWheelView(
    context: Context,
    initialColor: Int,
    private val onDragging: (Int) -> Unit,
    private val onReleased: (Int) -> Unit
) : View(context) {
    private var currentHue = 20f
    private var currentSat = 1f
    private var currentColor = initialColor
    private var isDragging = false
    private var cx = 0f
    private var cy = 0f
    private var wheelRadius = 0f
    private var wheelBitmap: Bitmap? = null

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.White.hashCode()
    }

    init {
        strokePaint.color = android.graphics.Color.WHITE
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor, hsv)
        currentHue = hsv[0]
        currentSat = hsv[1].coerceAtLeast(0.15f)
        currentColor = android.graphics.Color.HSVToColor(floatArrayOf(currentHue, currentSat, 1f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        wheelRadius = min(w, h) * 0.40f
        val size = (wheelRadius * 2f).toInt()
        if (size > 0) {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val r = size / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = SweepGradient(
                r, r,
                intArrayOf(
                    android.graphics.Color.RED, android.graphics.Color.MAGENTA, android.graphics.Color.BLUE,
                    android.graphics.Color.CYAN, android.graphics.Color.GREEN, android.graphics.Color.YELLOW,
                    android.graphics.Color.RED
                ),
                null
            )
            c.drawCircle(r, r, r, p)
            val rad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(r, r, r, android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
            }
            c.drawCircle(r, r, r, rad)
            wheelBitmap = bmp
        }
    }

    private fun updateTouch(x: Float, y: Float) {
        val dx = x - cx
        val dy = y - cy
        val dist = min(wheelRadius, hypot(dx, dy))
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0f) angle += 360f
        currentHue = (360f - angle) % 360f
        currentSat = (dist / max(1f, wheelRadius)).coerceIn(0f, 1f)
        currentColor = android.graphics.Color.HSVToColor(floatArrayOf(currentHue, currentSat, 1f))
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isDragging = true
                updateTouch(event.x, event.y)
                onDragging(currentColor)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (isDragging) {
                updateTouch(event.x, event.y)
                onDragging(currentColor)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (isDragging) {
                isDragging = false
                invalidate()
                onReleased(currentColor)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = min(width, height) / 2f
        val ringAlpha = if (isDragging) 255 else 130
        val red = android.graphics.Color.red(currentColor)
        val grn = android.graphics.Color.green(currentColor)
        val blu = android.graphics.Color.blue(currentColor)
        ringPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.argb(ringAlpha, red, grn, blu)
            ),
            floatArrayOf(0f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, ringPaint)
        ringPaint.shader = null

        wheelBitmap?.let { bmp ->
            wheelPaint.alpha = if (isDragging) 65 else 245
            canvas.drawBitmap(bmp, cx - wheelRadius, cy - wheelRadius, wheelPaint)
        }
        if (isDragging) {
            dotPaint.color = currentColor
            canvas.drawCircle(cx, cy, r * 0.17f, dotPaint)
        } else {
            val angleRad = Math.toRadians((360.0 - currentHue))
            val dist = currentSat * wheelRadius
            val tx = (cx + dist * cos(angleRad)).toFloat()
            val ty = (cy + dist * sin(angleRad)).toFloat()
            dotPaint.color = currentColor
            canvas.drawCircle(tx, ty, 13f, dotPaint)
            canvas.drawCircle(tx, ty, 13f, strokePaint)
        }
    }
}

/**
 * Canvas renderer mirroring Watch's ModeOverlayCanvasView inside Mobile's live circular preview.
 */
private class MobileWatchPreviewCanvasView(context: Context) : View(context) {
    private var modeName = ""
    private var modeColor = 0xFFFF5500.toInt()
    private var cfg = WatchOverlayConfig()
    private var lastTrigger = -1
    private var progress = 1f
    private var animator: ValueAnimator? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val arcRect = RectF()
    private val pillRect = RectF()
    private val sweepMatrix = Matrix()

    fun play(modeName: String, modeColor: Int, config: WatchOverlayConfig, trigger: Int) {
        if (trigger == lastTrigger) return
        lastTrigger = trigger
        this.modeName = modeName
        this.modeColor = modeColor
        this.cfg = config
        animator?.cancel()
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = config.durationMs.toLong().coerceIn(350L, 2500L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun withAlpha(c: Int, a: Float): Int =
        android.graphics.Color.argb(
            (a.coerceIn(0f, 1f) * 255f).roundToInt(),
            android.graphics.Color.red(c),
            android.graphics.Color.green(c),
            android.graphics.Color.blue(c)
        )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f

        // Simulated dark watchface
        bgPaint.color = android.graphics.Color.parseColor("#0B0F18")
        canvas.drawCircle(cx, cy, r, bgPaint)
        clockPaint.textSize = r * 0.34f
        canvas.drawText("10:09", cx, cy + clockPaint.textSize * 0.32f, clockPaint)

        val t = progress
        if (t >= 1f) return

        val thickPct = cfg.thicknessPct / 100f
        val maxAlpha = cfg.intensityPct / 100f
        val env = if (t < 0.18f) t / 0.18f else ((1f - (t - 0.18f) / 0.82f).coerceIn(0f, 1f)).pow(1.35f)
        val a = env * maxAlpha

        when (cfg.style) {
            "orbital" -> {
                val sweepDeg = min(1f, t / 0.65f) * 360f
                val ringW = r * thickPct
                val innerStop = ((r - ringW * 1.4f) / r).coerceIn(0.1f, 0.95f)
                fillPaint.shader = RadialGradient(
                    cx, cy, r,
                    intArrayOf(withAlpha(modeColor, 0f), withAlpha(modeColor, 0f), withAlpha(modeColor, a * 0.55f)),
                    floatArrayOf(0f, innerStop, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, r, fillPaint)
                fillPaint.shader = null
                val arcR = r - ringW * 0.35f
                arcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
                strokePaint.color = withAlpha(modeColor, a)
                strokePaint.strokeWidth = ringW * 0.55f
                canvas.drawArc(arcRect, -90f, sweepDeg, false, strokePaint)
            }
            "dualwave" -> {
                floatArrayOf(0f, 0.22f).forEach { delay ->
                    val wt = (t - delay) / (1f - delay)
                    if (wt in 0.001f..0.999f) {
                        val wa = sin(wt * PI.toFloat()) * maxAlpha
                        val rad = r - wt * (r * thickPct * 1.6f)
                        strokePaint.color = withAlpha(modeColor, wa)
                        strokePaint.strokeWidth = max(2f, (1f - wt) * (r * 0.07f))
                        canvas.drawCircle(cx, cy, rad, strokePaint)
                    }
                }
                val innerStop = (1f - thickPct).coerceIn(0.1f, 0.95f)
                fillPaint.shader = RadialGradient(
                    cx, cy, r,
                    intArrayOf(withAlpha(modeColor, 0f), withAlpha(modeColor, 0f), withAlpha(modeColor, a * 0.65f)),
                    floatArrayOf(0f, innerStop, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, r, fillPaint)
                fillPaint.shader = null
            }
            "twinarc" -> {
                val rotDeg = t * 450f
                val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                val sweep = SweepGradient(
                    cx, cy,
                    intArrayOf(
                        withAlpha(modeColor, 0f), withAlpha(modeColor, a * 0.85f), withAlpha(modeColor, a),
                        withAlpha(modeColor, a * 0.85f), withAlpha(modeColor, 0f), withAlpha(modeColor, a * 0.85f),
                        withAlpha(modeColor, a), withAlpha(modeColor, a * 0.85f), withAlpha(modeColor, 0f)
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
                    intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK),
                    floatArrayOf(0f, innerStop, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, r, maskPaint)
                maskPaint.shader = null
                canvas.restoreToCount(save)
            }
            else -> {
                val pulse = sin(t * PI.toFloat() * 2.2f) * 0.045f
                val innerStop = (1f - thickPct - pulse).coerceIn(0.15f, 0.95f)
                fillPaint.shader = RadialGradient(
                    cx, cy, r,
                    intArrayOf(
                        withAlpha(modeColor, 0f), withAlpha(modeColor, 0f),
                        withAlpha(modeColor, a * 0.45f), withAlpha(modeColor, a * 0.95f),
                        withAlpha(android.graphics.Color.WHITE, a * 0.85f)
                    ),
                    floatArrayOf(0f, innerStop, innerStop + (1f - innerStop) * 0.55f, innerStop + (1f - innerStop) * 0.88f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, r, fillPaint)
                fillPaint.shader = null
            }
        }

        if (cfg.showPill && modeName.isNotBlank()) {
            val pillAlpha = when {
                t < 0.08f -> t / 0.08f
                t > 0.86f -> ((1f - (t - 0.86f) / 0.08f)).coerceIn(0f, 1f)
                else -> 1f
            }
            if (pillAlpha > 0.01f) {
                val s = (cfg.pillSizePct / 100f) * (r / 200f)
                pillTextPaint.textSize = 15f * s
                pillTextPaint.alpha = (pillAlpha * 255f).roundToInt()
                val textW = pillTextPaint.measureText(modeName)
                val dotR = 5f * s
                val padH = 15f * s
                val padV = 8f * s
                val gap = 9f * s
                val pillW = padH * 2f + dotR * 2f + gap + textW
                val pillH = padV * 2f + pillTextPaint.textSize * 1.15f
                val pillCy = when (cfg.pillPosition) {
                    "center" -> cy
                    "bottom" -> height - (38f * (r / 200f)) - pillH / 2f
                    else -> (38f * (r / 200f)) + pillH / 2f
                }
                pillRect.set(cx - pillW / 2f, pillCy - pillH / 2f, cx + pillW / 2f, pillCy + pillH / 2f)
                pillBgPaint.color = android.graphics.Color.argb((pillAlpha * 218f).roundToInt(), 8, 10, 15)
                canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, pillBgPaint)
                val dotCx = pillRect.left + padH + dotR
                pillDotPaint.color = withAlpha(modeColor, pillAlpha)
                canvas.drawCircle(dotCx, pillCy, dotR, pillDotPaint)
                val fm = pillTextPaint.fontMetrics
                canvas.drawText(modeName, dotCx + dotR + gap, pillCy - (fm.ascent + fm.descent) / 2f, pillTextPaint)
            }
        }
    }
}

/**
 * Horizontal mode selector strip + smart mode header (large clickable color ball + clickable mode name)
 * mirroring the watch UI.
 */
@Composable
fun MobileModesHeaderCard(
    modes: List<WatchButtonMode>,
    selectedMode: WatchButtonMode,
    activeModeId: String,
    enabled: Boolean,
    onSelectMode: (String) -> Unit,
    onCreateMode: () -> Unit,
    onActivateMode: (String) -> Unit,
    onRenameClick: () -> Unit,
    onColorClick: () -> Unit
) {
    val palette = LocalPalette.current
    val modeColor = Color(selectedMode.colorArgb)
    val isActive = selectedMode.id == activeModeId

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.cardBg)
            .border(1.5.dp, modeColor.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .padding(12.dp)
    ) {
        // Horizontal mode pills + "+ New" pill
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            modes.forEach { m ->
                val sel = m.id == selectedMode.id
                val mc = Color(m.colorArgb)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (sel) mc.copy(alpha = 0.20f) else palette.bg)
                        .border(1.dp, if (sel) mc else palette.border, RoundedCornerShape(999.dp))
                        .clickable { onSelectMode(m.id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(mc))
                    Text(
                        text = m.name,
                        color = if (sel) palette.textPrimary else palette.textSecondary,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }
            }
            // Add Mode chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF00E676).copy(alpha = 0.16f))
                    .border(1.dp, Color(0xFF00E676).copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                    .clickable(enabled = enabled) { onCreateMode() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "+ " + stringResource(R.string.mode_add_short),
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        // Smart Mode Header: Large clickable color ball + clickable mode name + Active/Activate pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Large clickable color ball
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(modeColor)
                        .clickable(enabled = enabled) { onColorClick() }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) { onRenameClick() }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = selectedMode.name,
                        color = palette.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.mode_tap_to_edit_hint),
                        color = palette.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isActive) modeColor.copy(alpha = 0.22f) else palette.bg)
                    .border(1.dp, if (isActive) modeColor else palette.border, RoundedCornerShape(999.dp))
                    .clickable(enabled = enabled && !isActive) { onActivateMode(selectedMode.id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isActive) stringResource(R.string.mode_badge_active) else stringResource(R.string.mode_btn_activate),
                    color = if (isActive) modeColor else palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
    }
}
