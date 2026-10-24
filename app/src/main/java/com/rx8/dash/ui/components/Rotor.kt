package com.rx8.dash.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import com.rx8.dash.ui.theme.LocalDashColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Diagnostic switch. Set to false to remove every rotor drawing from the app without touching the call sites. */
private const val ROTOR_ENABLED = false

/**
 * A Wankel rotor turning inside its epitrochoid housing, the Renesis emblem.
 *
 * The geometry is the real one: the housing is the epitrochoid (R cos t + e cos 3t, R sin t + e sin 3t) and the three
 * rotor tips follow that curve exactly while the rotor centre orbits at 3x the rotor angle. The spin speed is a
 * stylised function of [rpm] (true speed would be an unreadable blur) and the rotor stands still when [rpm] is null or 0.
 */
@Composable
fun RotorEmblem(rpm: Double?, modifier: Modifier = Modifier, tint: Color = LocalDashColors.current.accent) {
    if (!ROTOR_ENABLED) return
    val rpmNow by rememberUpdatedState(rpm ?: 0.0)
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) {
                    last = now
                } else {
                    val dt = (now - last) / 1_000_000_000f
                    // Update at about 30 fps: the rotor does not need every vsync, and redraws are costly on slow GPUs.
                    if (dt >= 0.033f) {
                        val r = rpmNow.toFloat()
                        val degPerSec = if (r <= 0f) 0f else 30f + (r / 9000f).coerceAtMost(1.2f) * 240f
                        angle = (angle + degPerSec * dt) % 360f
                        last = now
                    }
                }
            }
        }
    }
    // Own graphics layer so the per-frame redraw does not invalidate the rest of the screen.
    Canvas(modifier = modifier.graphicsLayer { }) { drawRotorEngine(angle, tint) }
}

private fun DrawScope.drawRotorEngine(angleDeg: Float, tint: Color) {
    val s = min(size.width, size.height)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val apex = s * 0.30f          // R: distance from the shaft centre to a rotor tip
    val e = apex * 0.143f         // eccentricity, as on the 13B (about 15 mm over 105 mm)

    // Housing
    val housing = Path()
    val steps = 180
    for (i in 0..steps) {
        val t = i.toDouble() / steps * 2.0 * PI
        val x = cx + (apex * cos(t) + e * cos(3.0 * t)).toFloat()
        val y = cy + (apex * sin(t) + e * sin(3.0 * t)).toFloat()
        if (i == 0) housing.moveTo(x, y) else housing.lineTo(x, y)
    }
    housing.close()
    drawPath(housing, tint.copy(alpha = 0.07f), style = Fill)
    drawPath(housing, tint.copy(alpha = 0.55f), style = Stroke(width = s * 0.014f, join = StrokeJoin.Round))

    // Rotor, with its centre orbiting at 3x the rotor angle
    val a = Math.toRadians(angleDeg.toDouble())
    val rcx = cx + (e * cos(3.0 * a)).toFloat()
    val rcy = cy + (e * sin(3.0 * a)).toFloat()
    val tipX = FloatArray(3)
    val tipY = FloatArray(3)
    for (k in 0..2) {
        val ang = a + k * 2.0 * PI / 3.0
        tipX[k] = rcx + (apex * cos(ang)).toFloat()
        tipY[k] = rcy + (apex * sin(ang)).toFloat()
    }
    val rotor = Path()
    rotor.moveTo(tipX[0], tipY[0])
    for (k in 0..2) {
        val next = (k + 1) % 3
        val mid = a + k * 2.0 * PI / 3.0 + PI / 3.0
        val qx = rcx + (apex * 0.8 * cos(mid)).toFloat()      // quadratic control point, bulging outwards
        val qy = rcy + (apex * 0.8 * sin(mid)).toFloat()
        // Quadratic to cubic: control points sit 2/3 of the way from each end to the quadratic control point.
        rotor.cubicTo(
            tipX[k] + (qx - tipX[k]) * 2f / 3f, tipY[k] + (qy - tipY[k]) * 2f / 3f,
            tipX[next] + (qx - tipX[next]) * 2f / 3f, tipY[next] + (qy - tipY[next]) * 2f / 3f,
            tipX[next], tipY[next],
        )
    }
    rotor.close()
    drawPath(rotor, tint.copy(alpha = 0.22f), style = Fill)
    drawPath(rotor, tint, style = Stroke(width = s * 0.016f, join = StrokeJoin.Round, cap = StrokeCap.Round))

    // Internal gear on the rotor, apex seals and the fixed output shaft
    drawCircle(tint.copy(alpha = 0.6f), radius = apex * 0.36f, center = Offset(rcx, rcy), style = Stroke(width = s * 0.008f))
    for (k in 0..2) drawCircle(tint, radius = s * 0.016f, center = Offset(tipX[k], tipY[k]))
    drawCircle(tint, radius = s * 0.03f, center = Offset(cx, cy))
}
