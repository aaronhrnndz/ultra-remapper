package com.aaronhrndz.ultraremapper.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.aaronhrndz.ultraremapper.ButtonMode
import com.aaronhrndz.ultraremapper.HapticFeedbackHelper
import com.aaronhrndz.ultraremapper.ModeOverlayConfig
import com.aaronhrndz.ultraremapper.ModeOverlayManager
import com.aaronhrndz.ultraremapper.ModeStore
import com.aaronhrndz.ultraremapper.R
import kotlin.math.*

private val SlateCardBg = Color(0xFF1F232A)
private val AccentOrange = Color(0xFFFF5500)

/**
 * Samsung-style curved dot indicator along the top bezel:
 * [0] = Settings gear, [1..modes.size] = colored mode dots, [last] = '+' new mode.
 */
@Composable
fun CurvedModePageIndicator(
    modes: List<ButtonMode>,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    val totalPages = modes.size + 2
    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val arcR = min(size.width, size.height) / 2f - 10.dp.toPx()
        val spanRad = min(0.75f, max(0.32f, totalPages * 0.095f))
        val startA = -PI.toFloat() / 2f - spanRad / 2f

        val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            textAlign = AndroidPaint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        for (i in 0 until totalPages) {
            val frac = if (totalPages <= 1) 0.5f else i.toFloat() / (totalPages - 1).toFloat()
            val a = startA + frac * spanRad
            val x = cx + cos(a) * arcR
            val y = cy + sin(a) * arcR
            val isActive = i == currentPage

            when (i) {
                0 -> {
                    textPaint.color = if (isActive) AndroidColor.WHITE else AndroidColor.argb(115, 255, 255, 255)
                    textPaint.textSize = (if (isActive) 12.sp else 10.sp).toPx()
                    drawContext.canvas.nativeCanvas.drawText("⚙", x, y + textPaint.textSize * 0.35f, textPaint)
                }
                totalPages - 1 -> {
                    textPaint.color = if (isActive) AndroidColor.parseColor("#00E676") else AndroidColor.argb(115, 255, 255, 255)
                    textPaint.textSize = (if (isActive) 14.sp else 11.sp).toPx()
                    drawContext.canvas.nativeCanvas.drawText("+", x, y + textPaint.textSize * 0.35f, textPaint)
                }
                else -> {
                    val m = modes.getOrNull(i - 1)
                    val dotColor = if (isActive && m != null) Color(m.colorArgb) else Color.White.copy(alpha = 0.30f)
                    val dotR = if (isActive) 4.dp.toPx() else 2.5.dp.toPx()
                    drawCircle(color = dotColor, radius = dotR, center = Offset(x, y))
                }
            }
        }
    }
}

/**
 * Subtle colored perimeter ring around the watch screen indicating the current mode's color.
 */
@Composable
fun ModePerimeterRing(colorArgb: Int, modifier: Modifier = Modifier) {
    val c = Color(colorArgb)
    Canvas(modifier = modifier.fillMaxSize()) {
        val r = min(size.width, size.height) / 2f
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color.Transparent,
                0.84f to Color.Transparent,
                0.94f to c.copy(alpha = 0.28f),
                1.0f to c.copy(alpha = 0.62f),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = r
            ),
            radius = r,
            center = Offset(size.width / 2f, size.height / 2f)
        )
    }
}

/**
 * RGB Color Picker based on the RGB flashlight wheel:
 * - Touch & drag: dims wheel and previews live perimeter ring in the touched color.
 * - Release: reveals Confirm (✓) and Cancel (✕) buttons.
 */
@Composable
fun RgbColorPickerScreen(
    initialColorArgb: Int,
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var pickedColor by remember { mutableStateOf(initialColorArgb) }
    var awaitingConfirm by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF080A0F))) {
        AndroidView(
            factory = { ctx ->
                RgbWheelPickerView(
                    context = ctx,
                    initialColor = initialColorArgb,
                    onDraggingColor = { color ->
                        pickedColor = color
                        awaitingConfirm = false
                    },
                    onReleasedColor = { color ->
                        pickedColor = color
                        awaitingConfirm = true
                        HapticFeedbackHelper.playUiTap(ctx)
                    }
                )
            },
            modifier = Modifier.fillMaxSize()
        )

        if (awaitingConfirm) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 22.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2B313D))
                        .clickable {
                            HapticFeedbackHelper.playUiTap(context)
                            onCancel()
                        }
                ) {
                    MatCloseIcon(tint = Color.White, size = 22.dp)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2E7D32))
                        .clickable {
                            HapticFeedbackHelper.playButtonHaptic(context)
                            onConfirm(pickedColor)
                        }
                ) {
                    MatCheckIcon(tint = Color.White, size = 22.dp)
                }
            }
        }
    }
}

private class RgbWheelPickerView(
    context: android.content.Context,
    initialColor: Int,
    private val onDraggingColor: (Int) -> Unit,
    private val onReleasedColor: (Int) -> Unit
) : View(context) {

    private var currentHue = 20f
    private var currentSat = 1f
    private var currentColor = initialColor
    private var isDragging = false
    private var awaitingConfirm = false

    private var cx = 0f
    private var cy = 0f
    private var wheelRadius = 0f
    private var wheelBitmap: Bitmap? = null

    private val wheelPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    private val ringPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    private val dotPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    private val strokePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        style = AndroidPaint.Style.STROKE
        strokeWidth = 4f
        color = AndroidColor.WHITE
    }
    private val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textAlign = AndroidPaint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    init {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(initialColor, hsv)
        currentHue = hsv[0]
        currentSat = hsv[1].coerceAtLeast(0.15f)
        currentColor = AndroidColor.HSVToColor(floatArrayOf(currentHue, currentSat, 1f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        wheelRadius = min(w, h) * 0.40f
        generateWheel((wheelRadius * 2f).toInt())
    }

    private fun generateWheel(size: Int) {
        if (size <= 0) return
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(bmp)
        val r = size / 2f
        val p = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(
            AndroidColor.RED, AndroidColor.MAGENTA, AndroidColor.BLUE,
            AndroidColor.CYAN, AndroidColor.GREEN, AndroidColor.YELLOW, AndroidColor.RED
        )
        p.shader = SweepGradient(r, r, colors, null)
        c.drawCircle(r, r, r, p)
        val rad = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(r, r, r, AndroidColor.WHITE, AndroidColor.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawCircle(r, r, r, rad)
        wheelBitmap = bmp
    }

    private fun updateFromTouch(x: Float, y: Float) {
        val dx = x - cx
        val dy = y - cy
        val dist = min(wheelRadius, hypot(dx, dy))
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0f) angle += 360f
        // SweepGradient goes clockwise Red->Magenta->Blue->Cyan->Green->Yellow->Red, invert hue
        currentHue = (360f - angle) % 360f
        currentSat = (dist / max(1f, wheelRadius)).coerceIn(0f, 1f)
        currentColor = AndroidColor.HSVToColor(floatArrayOf(currentHue, currentSat, 1f))
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isDragging = true
                awaitingConfirm = false
                updateFromTouch(event.x, event.y)
                onDraggingColor(currentColor)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    updateFromTouch(event.x, event.y)
                    onDraggingColor(currentColor)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    awaitingConfirm = true
                    invalidate()
                    onReleasedColor(currentColor)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: AndroidCanvas) {
        super.onDraw(canvas)
        val r = min(width, height) / 2f
        val ringAlpha = when {
            isDragging -> 255
            awaitingConfirm -> 230
            else -> 95
        }
        val red = AndroidColor.red(currentColor)
        val grn = AndroidColor.green(currentColor)
        val blu = AndroidColor.blue(currentColor)
        ringPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
                AndroidColor.argb((ringAlpha * 0.65f).toInt(), red, grn, blu),
                AndroidColor.argb(ringAlpha, red, grn, blu)
            ),
            floatArrayOf(0f, 0.76f, 0.90f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, ringPaint)
        ringPaint.shader = null

        wheelBitmap?.let { bmp ->
            wheelPaint.alpha = when {
                isDragging -> 55
                awaitingConfirm -> 40
                else -> 240
            }
            canvas.drawBitmap(bmp, cx - wheelRadius, cy - wheelRadius, wheelPaint)
        }

        if (isDragging || awaitingConfirm) {
            dotPaint.color = currentColor
            dotPaint.setShadowLayer(20f, 0f, 0f, currentColor)
            canvas.drawCircle(cx, cy - r * 0.10f, r * 0.17f, dotPaint)
            dotPaint.clearShadowLayer()
            textPaint.textSize = r * 0.085f
            val label = if (isDragging) {
                context.getString(R.string.color_picker_preview)
            } else {
                context.getString(R.string.color_picker_confirm_prompt)
            }
            canvas.drawText(label, cx, cy + r * 0.19f, textPaint)
        } else {
            val angleRad = Math.toRadians((360.0 - currentHue))
            val dist = currentSat * wheelRadius
            val tx = (cx + dist * cos(angleRad)).toFloat()
            val ty = (cy + dist * sin(angleRad)).toFloat()
            dotPaint.color = currentColor
            canvas.drawCircle(tx, ty, 12f, dotPaint)
            canvas.drawCircle(tx, ty, 12f, strokePaint)
        }
    }
}

/**
 * Watch-side Animation Editor with live preview overlay directly on the watch screen.
 */
@Composable
fun AnimationEditorScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    var cfg by remember { mutableStateOf(ModeStore.getOverlayConfig(context)) }
    val activeMode = remember { ModeStore.getState(context).activeMode }
    var styleExpanded by remember { mutableStateOf(false) }
    var posExpanded by remember { mutableStateOf(false) }

    fun saveOnly(next: ModeOverlayConfig) {
        cfg = next
        ModeStore.setOverlayConfig(context, next)
    }

    val styleLabels = mapOf(
        ModeOverlayConfig.STYLE_BREATHING to context.getString(R.string.anim_style_breathing),
        ModeOverlayConfig.STYLE_ORBITAL to context.getString(R.string.anim_style_orbital),
        ModeOverlayConfig.STYLE_DUALWAVE to context.getString(R.string.anim_style_dualwave),
        ModeOverlayConfig.STYLE_TWINARC to context.getString(R.string.anim_style_twinarc)
    )
    val posLabels = mapOf(
        ModeOverlayConfig.POS_TOP to context.getString(R.string.anim_pill_pos_top),
        ModeOverlayConfig.POS_CENTER to context.getString(R.string.anim_pill_pos_center),
        ModeOverlayConfig.POS_BOTTOM to context.getString(R.string.anim_pill_pos_bottom)
    )

    ScreenScaffold(scrollState = columnState) { _ ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = PaddingValues(top = 36.dp, bottom = 52.dp, start = 10.dp, end = 10.dp)
        ) {
            item(key = "anim_hdr") {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) {
                    Text(
                        text = context.getString(R.string.anim_editor_title),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().basicMarquee()
                    )
                }
            }

            // Centered orange pill "Probar" button sized to its text
            item(key = "anim_preview_btn") {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(AccentOrange)
                            .clickable {
                                HapticFeedbackHelper.playUiTap(context)
                                ModeOverlayManager.show(context, activeMode, cfg)
                            }
                            .padding(horizontal = 22.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = context.getString(R.string.anim_preview_btn),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Section: Ring
            item(key = "anim_ring_section_hdr") {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) {
                    Text(
                        text = context.getString(R.string.anim_section_ring),
                        color = AccentOrange,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Style Dropdown
            item(key = "anim_style_dd") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(SlateCardBg)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().clickable { styleExpanded = !styleExpanded }
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(context.getString(R.string.anim_style_label), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(styleLabels[cfg.style] ?: cfg.style, color = AccentOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        MatChevronIcon(expanded = styleExpanded, tint = Color.White, size = 20.dp)
                    }
                    if (styleExpanded) {
                        Spacer(modifier = Modifier.height(6.dp))
                        ModeOverlayConfig.ALL_STYLES.forEach { st ->
                            val sel = cfg.style == st
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) AccentOrange.copy(alpha = 0.2f) else Color(0xFF282E38))
                                    .clickable {
                                        saveOnly(cfg.copy(style = st))
                                        styleExpanded = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(styleLabels[st] ?: st, color = if (sel) AccentOrange else Color.White, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                if (sel) MatCheckIcon(tint = AccentOrange, size = 16.dp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }

            item(key = "anim_thick") {
                StepRowCard(
                    title = context.getString(R.string.anim_thickness_label),
                    valueText = "${cfg.thicknessPct}%",
                    onMinus = { saveOnly(cfg.copy(thicknessPct = (cfg.thicknessPct - 2).coerceAtLeast(6))) },
                    onPlus = { saveOnly(cfg.copy(thicknessPct = (cfg.thicknessPct + 2).coerceAtMost(26))) }
                )
            }

            item(key = "anim_dur") {
                StepRowCard(
                    title = context.getString(R.string.anim_duration_label),
                    valueText = "${cfg.durationMs} ms",
                    onMinus = { saveOnly(cfg.copy(durationMs = (cfg.durationMs - 100).coerceAtLeast(400))) },
                    onPlus = { saveOnly(cfg.copy(durationMs = (cfg.durationMs + 100).coerceAtMost(2500))) }
                )
            }

            item(key = "anim_speed") {
                StepRowCard(
                    title = context.getString(R.string.anim_speed_label),
                    valueText = "${cfg.speedPct}%",
                    onMinus = { saveOnly(cfg.copy(speedPct = (cfg.speedPct - 10).coerceAtLeast(30))) },
                    onPlus = { saveOnly(cfg.copy(speedPct = (cfg.speedPct + 10).coerceAtMost(200))) }
                )
            }

            item(key = "anim_int") {
                StepRowCard(
                    title = context.getString(R.string.anim_intensity_label),
                    valueText = "${cfg.intensityPct}%",
                    onMinus = { saveOnly(cfg.copy(intensityPct = (cfg.intensityPct - 10).coerceAtLeast(40))) },
                    onPlus = { saveOnly(cfg.copy(intensityPct = (cfg.intensityPct + 10).coerceAtMost(100))) }
                )
            }

            // Section: Pill
            item(key = "anim_pill_section_hdr") {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) {
                    Text(
                        text = context.getString(R.string.anim_section_pill),
                        color = AccentOrange,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Pill Switch
            item(key = "anim_pill_toggle") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(SlateCardBg)
                        .clickable { saveOnly(cfg.copy(showPill = !cfg.showPill)) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = context.getString(R.string.anim_pill_toggle),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).basicMarquee()
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .width(34.dp)
                            .height(19.dp)
                            .clip(CircleShape)
                            .background(if (cfg.showPill) AccentOrange else Color(0xFF434954))
                            .padding(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(15.dp)
                                .align(if (cfg.showPill) Alignment.CenterEnd else Alignment.CenterStart)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }
                }
            }

            if (cfg.showPill) {
                // Pill Position Dropdown
                item(key = "anim_pill_pos_dd") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(SlateCardBg)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth().clickable { posExpanded = !posExpanded }
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(context.getString(R.string.anim_pill_position), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(posLabels[cfg.pillPosition] ?: cfg.pillPosition, color = AccentOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            MatChevronIcon(expanded = posExpanded, tint = Color.White, size = 20.dp)
                        }
                        if (posExpanded) {
                            Spacer(modifier = Modifier.height(6.dp))
                            ModeOverlayConfig.ALL_POSITIONS.forEach { pos ->
                                val sel = cfg.pillPosition == pos
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (sel) AccentOrange.copy(alpha = 0.2f) else Color(0xFF282E38))
                                        .clickable {
                                            saveOnly(cfg.copy(pillPosition = pos))
                                            posExpanded = false
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Text(posLabels[pos] ?: pos, color = if (sel) AccentOrange else Color.White, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                    if (sel) MatCheckIcon(tint = AccentOrange, size = 16.dp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }

                item(key = "anim_pill_size") {
                    StepRowCard(
                        title = context.getString(R.string.anim_pill_size),
                        valueText = "${cfg.pillSizePct}%",
                        onMinus = { saveOnly(cfg.copy(pillSizePct = (cfg.pillSizePct - 10).coerceAtLeast(50))) },
                        onPlus = { saveOnly(cfg.copy(pillSizePct = (cfg.pillSizePct + 10).coerceAtMost(300))) }
                    )
                }

                item(key = "anim_pill_alpha") {
                    StepRowCard(
                        title = context.getString(R.string.anim_pill_opacity),
                        valueText = "${cfg.pillAlphaPct}%",
                        onMinus = { saveOnly(cfg.copy(pillAlphaPct = (cfg.pillAlphaPct - 10).coerceAtLeast(20))) },
                        onPlus = { saveOnly(cfg.copy(pillAlphaPct = (cfg.pillAlphaPct + 10).coerceAtMost(100))) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRowCard(
    title: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SlateCardBg)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF2D333F)).clickable(onClick = onMinus)
        ) {
            Text("−", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.basicMarquee())
            Text(valueText, color = AccentOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF2D333F)).clickable(onClick = onPlus)
        ) {
            Text("+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

/**
 * Explanatory screen shown when the user taps a globally reserved mode-switch gesture in another mode.
 */
@Composable
fun ReservedGestureInfoScreen(ownerModeName: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val columnState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = columnState) { _ ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = PaddingValues(top = 36.dp, bottom = 46.dp, start = 14.dp, end = 14.dp)
        ) {
            item(key = "res_title") {
                Text(
                    text = context.getString(R.string.mode_reserved_title),
                    color = Color(0xFFFFB74D),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item(key = "res_body") {
                Text(
                    text = context.getString(R.string.mode_reserved_body, ownerModeName),
                    color = Color(0xFFD5DCE8),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
            item(key = "res_ok") {
                Button(
                    onClick = onDone,
                    label = { Text(context.getString(R.string.btn_understood), fontWeight = FontWeight.Bold) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentOrange, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Watch-native mode rename screen using Compose BasicTextField (works with Wear OS keyboard & voice).
 */
@Composable
fun ModeRenameScreen(
    currentName: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var text by remember(currentName) { mutableStateOf(currentName) }
    val columnState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = columnState) { _ ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = PaddingValues(top = 34.dp, bottom = 44.dp, start = 14.dp, end = 14.dp)
        ) {
            item(key = "ren_title") {
                Text(
                    text = context.getString(R.string.mode_rename_title),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item(key = "ren_field") {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SlateCardBg)
                        .border(1.dp, AccentOrange.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { if (it.length <= 24) text = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(AccentOrange),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item(key = "ren_actions") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2B313D))
                            .clickable {
                                HapticFeedbackHelper.playUiTap(context)
                                onCancel()
                            }
                    ) {
                        MatCloseIcon(tint = Color.White, size = 22.dp)
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E7D32))
                            .clickable {
                                HapticFeedbackHelper.playButtonHaptic(context)
                                val trimmed = text.trim()
                                if (trimmed.isNotEmpty()) onSave(trimmed) else onCancel()
                            }
                    ) {
                        MatCheckIcon(tint = Color.White, size = 22.dp)
                    }
                }
            }
        }
    }
}

/**
 * Delete mode confirmation screen.
 */
@Composable
fun DeleteModeConfirmScreen(
    modeName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val columnState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = columnState) { _ ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = PaddingValues(top = 36.dp, bottom = 46.dp, start = 14.dp, end = 14.dp)
        ) {
            item(key = "del_title") {
                Text(
                    text = context.getString(R.string.mode_delete_confirm_title, modeName),
                    color = Color(0xFFFF6E6E),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item(key = "del_body") {
                Text(
                    text = context.getString(R.string.mode_delete_confirm_body),
                    color = Color(0xFFC5CDD9),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
            item(key = "del_actions") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2B313D))
                            .clickable {
                                HapticFeedbackHelper.playUiTap(context)
                                onCancel()
                            }
                    ) {
                        MatCloseIcon(tint = Color.White, size = 22.dp)
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFC62828))
                            .clickable {
                                HapticFeedbackHelper.playButtonHaptic(context)
                                onConfirm()
                            }
                    ) {
                        MatTrashIcon(tint = Color.White, size = 22.dp)
                    }
                }
            }
        }
    }
}
