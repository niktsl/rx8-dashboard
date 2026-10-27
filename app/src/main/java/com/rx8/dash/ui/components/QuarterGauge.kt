package com.rx8.dash.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rx8.dash.ui.theme.LocalDashColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Quarter-ring rpm gauge, as on race-style head units: a 90° ring around a pivot in the bottom-right corner, sweeping
 * from the left edge up to the top edge. The fill is blue and turns red past [redlineRpm], with a bright leading tip.
 * Ticks and numbers run along the outer edge, and the rpm value is printed in the corner.
 */
@Composable
fun QuarterRpmGauge(
    rpm: Double?,
    redlineRpm: Int,
    modifier: Modifier = Modifier,
    maxRpm: Int = ((redlineRpm + 1000) / 1000) * 1000,
    coldLimitRpm: Int? = null,
) {
    val c = LocalDashColors.current
    val target = fraction(rpm ?: 0.0, 0.0, maxRpm.toDouble())
    val animated by animateFloatAsState(targetValue = target, animationSpec = tween(120), label = "quarter")
    val overRed = rpm != null && rpm >= redlineRpm

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) * 0.94f
            val cx = size.width * 0.97f
            val cy = size.height * 0.97f
            val stroke = r * 0.16f
            val ringR = r * 0.80f - stroke / 2f
            val topLeft = Offset(cx - ringR, cy - ringR)
            val arcSize = Size(ringR * 2f, ringR * 2f)
            val redFrac = fraction(redlineRpm.toDouble(), 0.0, maxRpm.toDouble())

            // Track, with the redline zone tinted
            drawArc(c.gaugeTrack, 180f, 90f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(c.redline.copy(alpha = 0.35f), 180f + 90f * redFrac, 90f * (1f - redFrac), false, topLeft, arcSize, style = Stroke(stroke))

            // Fill: blue up to the redline, red beyond it, then a bright tip at the current value
            val fillSweep = 90f * animated
            if (fillSweep > 0.2f) {
                val blueSweep = min(fillSweep, 90f * redFrac)
                if (blueSweep > 0f) drawArc(c.gaugeFill, 180f, blueSweep, false, topLeft, arcSize, style = Stroke(stroke))
                if (fillSweep > 90f * redFrac) {
                    drawArc(c.redline, 180f + 90f * redFrac, fillSweep - 90f * redFrac, false, topLeft, arcSize, style = Stroke(stroke))
                }
                val tip = min(fillSweep, 3f)
                drawArc(if (overRed) c.redline else c.needle, 180f + fillSweep - tip, tip, false, topLeft, arcSize, style = Stroke(stroke))
            }

            // Warm-up limit marker across the ring
            if (coldLimitRpm != null && coldLimitRpm in 1 until maxRpm) {
                val a = Math.toRadians((180f + 90f * coldLimitRpm / maxRpm.toFloat()).toDouble())
                val inner = ringR - stroke / 2f
                val outer = ringR + stroke / 2f
                drawLine(
                    c.info,
                    Offset(cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat()),
                    Offset(cx + (outer * cos(a)).toFloat(), cy + (outer * sin(a)).toFloat()),
                    strokeWidth = r * 0.014f,
                )
            }

            // Ticks and numbers along the outer edge
            val paint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = r * 0.07f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val steps = maxRpm / 500
            for (i in 0..steps) {
                val value = i * 500
                val major = value % 1000 == 0
                val a = Math.toRadians((180f + 90f * value / maxRpm.toFloat()).toDouble())
                val from = r * 0.84f
                val to = from + if (major) r * 0.06f else r * 0.03f
                val tickColor = if (value >= redlineRpm) c.redline else c.tick
                drawLine(
                    tickColor,
                    Offset(cx + (from * cos(a)).toFloat(), cy + (from * sin(a)).toFloat()),
                    Offset(cx + (to * cos(a)).toFloat(), cy + (to * sin(a)).toFloat()),
                    strokeWidth = if (major) r * 0.010f else r * 0.005f,
                )
                if (major) {
                    val lr = r * 0.97f
                    paint.color = tickColor.toArgb()
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                            (value / 1000).toString(),
                            cx + (lr * cos(a)).toFloat(),
                            cy + (lr * sin(a)).toFloat() + paint.textSize / 3f,
                            paint,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = Fmt.int(rpm),
                color = if (overRed) c.redline else c.text,
                fontSize = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Monospace,
            )
            Text("RPM", color = c.textDim, fontSize = 14.sp, letterSpacing = 3.sp)
        }
    }
}
