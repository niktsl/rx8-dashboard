package com.rx8.dash.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rx8.dash.core.Alert
import com.rx8.dash.core.ConnectionState
import com.rx8.dash.core.ConnectionStatus
import com.rx8.dash.core.Metric
import com.rx8.dash.ui.theme.LocalDashColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val START_ANGLE = 135f
private const val SWEEP = 270f

private fun fraction(v: Double, min: Double, max: Double): Float {
    if (max <= min || v.isNaN()) return 0f
    return ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

/**
 * Canvas tachometer: 270° arc, major ticks every 1000 rpm, redline zone from [redlineRpm] to [maxRpm], and an
 * optional cold-limit marker (warm-up guidance). [rpm] null shows "--" with the needle parked.
 */
@Composable
fun TachometerGauge(
    rpm: Double?,
    redlineRpm: Int,
    modifier: Modifier = Modifier,
    maxRpm: Int = ((redlineRpm + 1000) / 1000) * 1000,
    coldLimitRpm: Int? = null,
    caption: String? = null,
) {
    val c = LocalDashColors.current
    val target = fraction(rpm ?: 0.0, 0.0, maxRpm.toDouble())
    val animated by animateFloatAsState(targetValue = target, animationSpec = tween(120), label = "tach")
    val overRed = rpm != null && rpm >= redlineRpm

    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        RotorEmblem(rpm = rpm, modifier = Modifier.fillMaxSize(0.56f))
        Canvas(modifier = Modifier.fillMaxSize()) {
            val d = min(size.width, size.height)
            val stroke = d * 0.06f
            val inset = stroke / 2f + d * 0.02f
            val arcSize = Size(d - inset * 2f, d - inset * 2f)
            val topLeft = Offset((size.width - arcSize.width) / 2f, (size.height - arcSize.height) / 2f)
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = arcSize.width / 2f

            drawArc(c.gaugeTrack, START_ANGLE, SWEEP, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            val redFrac = fraction(redlineRpm.toDouble(), 0.0, maxRpm.toDouble())
            drawArc(
                c.redline, START_ANGLE + SWEEP * redFrac, SWEEP * (1f - redFrac), false, topLeft, arcSize,
                style = Stroke(stroke, cap = StrokeCap.Butt),
            )
            drawArc(
                if (overRed) c.redline else c.gaugeFill, START_ANGLE, SWEEP * animated, false, topLeft, arcSize,
                style = Stroke(stroke * 0.55f, cap = StrokeCap.Butt),
            )

            val paint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = d * 0.06f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val steps = maxRpm / 500
            for (i in 0..steps) {
                val value = i * 500
                val major = value % 1000 == 0
                val a = Math.toRadians((START_ANGLE + SWEEP * value / maxRpm.toFloat()).toDouble())
                val outer = radius - stroke * 0.7f
                val inner = outer - if (major) d * 0.06f else d * 0.03f
                val tickColor = if (value >= redlineRpm) c.redline else c.tick
                drawLine(
                    tickColor,
                    Offset(center.x + (outer * cos(a)).toFloat(), center.y + (outer * sin(a)).toFloat()),
                    Offset(center.x + (inner * cos(a)).toFloat(), center.y + (inner * sin(a)).toFloat()),
                    strokeWidth = if (major) d * 0.012f else d * 0.006f,
                )
                if (major) {
                    val lr = inner - d * 0.06f
                    paint.color = tickColor.toArgb()
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                            (value / 1000).toString(),
                            center.x + (lr * cos(a)).toFloat(),
                            center.y + (lr * sin(a)).toFloat() + paint.textSize / 3f,
                            paint,
                        )
                    }
                }
            }

            if (coldLimitRpm != null && coldLimitRpm in 1 until maxRpm) {
                val a = Math.toRadians((START_ANGLE + SWEEP * coldLimitRpm / maxRpm.toFloat()).toDouble())
                val r1 = radius + stroke * 0.5f
                val r2 = radius - stroke * 0.5f
                drawLine(
                    c.info,
                    Offset(center.x + (r1 * cos(a)).toFloat(), center.y + (r1 * sin(a)).toFloat()),
                    Offset(center.x + (r2 * cos(a)).toFloat(), center.y + (r2 * sin(a)).toFloat()),
                    strokeWidth = d * 0.014f,
                )
            }

            val na = Math.toRadians((START_ANGLE + SWEEP * animated).toDouble())
            val nl = radius - stroke * 1.2f
            drawLine(
                c.needle,
                center,
                Offset(center.x + (nl * cos(na)).toFloat(), center.y + (nl * sin(na)).toFloat()),
                strokeWidth = d * 0.018f,
                cap = StrokeCap.Round,
            )
            drawCircle(c.needle, radius = d * 0.035f, center = center)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
        ) {
            Text(
                text = Fmt.int(rpm),
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 34.sp),
                color = if (overRed) c.redline else c.text,
            )
            Text(text = caption ?: "rpm", color = c.textDim, fontSize = 16.sp)
        }
    }
}

/** True when [value] is in a range worth flagging in red (high speed, hot coolant, bad battery voltage). */
private fun isDanger(metric: Metric, value: Double, frac: Float): Boolean = when (metric) {
    Metric.COOLANT -> value >= 105.0
    Metric.INTAKE_AIR -> value >= 60.0
    Metric.VOLTAGE -> value < 12.0 || value > 15.0
    Metric.SPEED, Metric.RPM, Metric.THROTTLE, Metric.LOAD, Metric.FUEL_FLOW -> frac >= 0.9f
    else -> false
}

/**
 * Small 270° ring gauge for one metric: value and unit inside the ring, label underneath. The fill is blue and turns
 * red in the danger range. Shows "NO ECU PID" when the ECU does not report the metric.
 */
@Composable
fun ArcGauge(
    metric: Metric,
    value: Double?,
    supported: Boolean,
    modifier: Modifier = Modifier,
    lowConfidence: Boolean = false,
) {
    val c = LocalDashColors.current
    val target = if (value == null) 0f else fraction(value, metric.min, metric.max)
    val animated by animateFloatAsState(targetValue = target, animationSpec = tween(200), label = "arc")
    val danger = supported && value != null && isDanger(metric, value, target)
    val fill = if (danger) c.redline else c.gaugeFill
    Column(
        modifier = modifier.padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val d = min(size.width, size.height)
                val stroke = d * 0.09f
                val arcSize = Size(d - stroke, d - stroke)
                val topLeft = Offset((size.width - arcSize.width) / 2f, (size.height - arcSize.height) / 2f)
                drawArc(c.gaugeTrack, START_ANGLE, SWEEP, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                if (supported && value != null && animated > 0.005f) {
                    drawArc(fill.copy(alpha = 0.25f), START_ANGLE, SWEEP * animated, false, topLeft, arcSize, style = Stroke(stroke * 1.7f, cap = StrokeCap.Round))
                    drawArc(fill, START_ANGLE, SWEEP * animated, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            if (!supported) {
                Text("NO ECU PID", color = c.textDim, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = Fmt.value(metric, value) + if (lowConfidence) "*" else "",
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 26.sp),
                        color = if (danger) c.redline else c.text,
                        maxLines = 1,
                    )
                    if (metric.unit.isNotEmpty()) Text(metric.unit, color = c.textDim, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Text(
            text = metric.label.uppercase(),
            color = c.textDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Large numeric tile. */
@Composable
fun DigitalReadout(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    val c = LocalDashColors.current
    Column(
        modifier = modifier
            .background(c.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = c.textDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.displaySmall, color = valueColor ?: c.text, maxLines = 1)
        if (unit.isNotEmpty()) Text(unit, color = c.textDim, fontSize = 13.sp)
    }
}

/** Telltale lamp: lit in the warning colour when [on]. */
@Composable
fun WarningLight(label: String, on: Boolean, modifier: Modifier = Modifier) {
    val c = LocalDashColors.current
    Row(
        modifier = modifier
            .background(if (on) c.warning.copy(alpha = 0.25f) else c.surface, RoundedCornerShape(12.dp))
            .border(2.dp, if (on) c.warning else c.gaugeTrack, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(if (on) c.warning else c.gaugeTrack, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (on) c.warning else c.textDim, fontWeight = FontWeight.Bold)
    }
}

/** Shows the most severe alert, plus a count of the rest. Renders nothing when [alerts] is empty. */
@Composable
fun AlertBanner(alerts: List<Alert>, modifier: Modifier = Modifier) {
    if (alerts.isEmpty()) return
    val c = LocalDashColors.current
    val top = alerts.maxByOrNull { it.level.ordinal } ?: return
    val color = c.forLevel(top.level)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .border(2.dp, color, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(top.title, color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(top.message, color = c.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (alerts.size > 1) {
            Text("+${alerts.size - 1}", color = c.textDim, fontSize = 16.sp, textAlign = TextAlign.End)
        }
    }
}

/** Connection state plus measured poll rate. */
@Composable
fun ConnectionChip(status: ConnectionStatus, demo: Boolean, modifier: Modifier = Modifier) {
    val c = LocalDashColors.current
    val (color, icon) = when (status.state) {
        ConnectionState.CONNECTED -> Pair(c.ok, Icons.Filled.BluetoothConnected)
        ConnectionState.CONNECTING, ConnectionState.INITIALIZING -> Pair(c.caution, Icons.Filled.BluetoothSearching)
        ConnectionState.RECONNECT_WAIT -> Pair(c.caution, Icons.Filled.Bluetooth)
        ConnectionState.ERROR -> Pair(c.warning, Icons.Filled.ErrorOutline)
        ConnectionState.DISCONNECTED -> Pair(c.textDim, Icons.Filled.Bluetooth)
    }
    val label = buildString {
        if (demo) append("DEMO · ")
        append(status.state.name.replace('_', ' '))
        if (status.state == ConnectionState.CONNECTED) {
            append(" · ")
            append(Fmt.num(status.pollHz, 1))
            append(" Hz")
        }
        val retry = status.nextRetryInMs
        if (status.state == ConnectionState.RECONNECT_WAIT && retry != null) {
            append(" · retry ")
            append((retry + 999L) / 1000L)
            append(" s")
        }
    }
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Card container used by the list screens. */
@Composable
fun DashCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalDashColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) { content() }
}
