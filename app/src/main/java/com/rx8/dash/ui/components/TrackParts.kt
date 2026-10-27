package com.rx8.dash.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rx8.dash.core.Metric
import com.rx8.dash.ui.theme.LocalDashColors
import kotlin.math.ceil

/**
 * Horizontal bar made of separate segments, as on a race-style head unit. Lit segments are blue; segments at or past
 * [redFrom] (0..1 of the bar) are red. [redFrom] above 1 gives an all-blue bar; 0 gives an all-red bar.
 */
@Composable
fun SegmentedBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    segments: Int = 36,
    redFrom: Float = 2f,
    barHeight: Dp = 16.dp,
) {
    val c = LocalDashColors.current
    val f = fraction.coerceIn(0f, 1f)
    Canvas(modifier = modifier.fillMaxWidth().height(barHeight)) {
        val gap = size.width * 0.008f
        val segW = (size.width - gap * (segments - 1)) / segments
        val lit = ceil(f * segments).toInt()
        for (i in 0 until segments) {
            val color = when {
                i >= lit -> c.gaugeTrack
                i.toFloat() / segments >= redFrom -> c.redline
                else -> c.gaugeFill
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (segW + gap), 0f),
                size = Size(segW, size.height),
                cornerRadius = CornerRadius(size.height * 0.15f, size.height * 0.15f),
            )
        }
    }
}

/** Small readout tile: segmented bar on top, then the value with its unit, then the label. */
@Composable
fun BarReadout(metric: Metric, value: Double?, modifier: Modifier = Modifier) {
    val c = LocalDashColors.current
    val frac = if (value == null) 0f else fraction(value, metric.min, metric.max)
    val danger = value != null && isDanger(metric, value, frac)
    Column(
        modifier = modifier
            .background(c.surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        SegmentedBar(fraction = frac, segments = 18, redFrom = if (danger) 0f else 2f, barHeight = 8.dp)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = Fmt.value(metric, value),
                color = if (danger) c.redline else c.text,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            if (metric.unit.isNotEmpty()) {
                Text(metric.unit, color = c.textDim, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        Text(
            text = metric.label.uppercase(),
            color = c.textDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            maxLines = 1,
        )
    }
}

/** Pill that switches between the two dashboard layouts. [label] names the layout it switches to. */
@Composable
fun LayoutSwitchChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalDashColors.current
    Box(
        modifier = modifier
            .focusRing(RoundedCornerShape(50))
            .border(1.dp, c.accent, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, maxLines = 1)
    }
}
