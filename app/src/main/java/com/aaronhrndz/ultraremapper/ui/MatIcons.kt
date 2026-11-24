package com.aaronhrndz.ultraremapper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun MatCheckIcon(tint: Color = Color.White, size: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.20f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.74f)
            lineTo(w * 0.82f, h * 0.28f)
        }
        drawPath(path, color = tint, style = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun MatCloseIcon(tint: Color = Color.White, size: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.12f
        drawLine(tint, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.75f, h * 0.75f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.75f, h * 0.25f), Offset(w * 0.25f, h * 0.75f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun MatTrashIcon(tint: Color = Color.White, size: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.09f
        // Top handle
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.38f, h * 0.14f),
            size = Size(w * 0.24f, h * 0.10f),
            cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
            style = Stroke(width = stroke)
        )
        // Lid bar
        drawLine(tint, Offset(w * 0.18f, h * 0.26f), Offset(w * 0.82f, h * 0.26f), strokeWidth = stroke, cap = StrokeCap.Round)
        // Bin body
        val body = Path().apply {
            moveTo(w * 0.26f, h * 0.28f)
            lineTo(w * 0.31f, h * 0.84f)
            lineTo(w * 0.69f, h * 0.84f)
            lineTo(w * 0.74f, h * 0.28f)
        }
        drawPath(body, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Inner vertical ribs
        drawLine(tint, Offset(w * 0.43f, h * 0.40f), Offset(w * 0.44f, h * 0.72f), strokeWidth = stroke * 0.85f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.57f, h * 0.40f), Offset(w * 0.56f, h * 0.72f), strokeWidth = stroke * 0.85f, cap = StrokeCap.Round)
    }
}

@Composable
fun MatChevronIcon(expanded: Boolean, tint: Color = Color.White, size: Dp = 20.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            if (expanded) {
                moveTo(w * 0.25f, h * 0.62f)
                lineTo(w * 0.50f, h * 0.36f)
                lineTo(w * 0.75f, h * 0.62f)
            } else {
                moveTo(w * 0.25f, h * 0.38f)
                lineTo(w * 0.50f, h * 0.64f)
                lineTo(w * 0.75f, h * 0.38f)
            }
        }
        drawPath(path, color = tint, style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
