package com.rx8.dash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rx8.dash.AppGraph
import com.rx8.dash.core.Metric
import com.rx8.dash.core.health.RotaryHealthMonitor
import com.rx8.dash.core.health.WarmupStage
import com.rx8.dash.data.AppSettings
import com.rx8.dash.data.DashLayout
import com.rx8.dash.ui.components.AlertBanner
import com.rx8.dash.ui.components.BarReadout
import com.rx8.dash.ui.components.ConnectionChip
import com.rx8.dash.ui.components.Fmt
import com.rx8.dash.ui.components.LayoutSwitchChip
import com.rx8.dash.ui.components.SegmentedBar
import com.rx8.dash.ui.components.QuarterRpmGauge
import com.rx8.dash.ui.components.WarningLight
import com.rx8.dash.ui.components.fraction
import com.rx8.dash.ui.theme.LocalDashColors
import com.rx8.dash.ui.vm.SessionViewModel

/** Metrics the RX-8 can supply for the left column of the track layout, in order of preference. */
private val SIDE_METRICS = listOf(
    Metric.COOLANT, Metric.INTAKE_AIR, Metric.VOLTAGE, Metric.LOAD, Metric.THROTTLE, Metric.FUEL_FLOW,
)

/**
 * Track layout: a column of bar readouts on the left, a big speed number with a segmented rpm bar and engine-load bar
 * in the middle, and the tachometer with the warm-up strip on the right. Metrics the ECU does not report are hidden.
 */
@Composable
fun TrackScreen(session: SessionViewModel, graph: AppGraph, modifier: Modifier = Modifier) {
    val settings by session.settings.collectAsStateWithLifecycle()
    val snap by session.telemetry.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val alerts by session.alerts.collectAsStateWithLifecycle()
    val s = settings ?: AppSettings()
    val c = LocalDashColors.current

    val monitor = remember(s.redlineRpm) { RotaryHealthMonitor(s.toHealthConfig()) }
    val coolant = snap[Metric.COOLANT]
    val stage = monitor.stage(coolant)
    val oil = snap[Metric.OIL_PRESSURE_OK]
    val supportedKnown = snap.supported.isNotEmpty()
    val maxRpm = ((s.redlineRpm + 1000) / 1000) * 1000
    val rpm = snap[Metric.RPM]
    val speed = snap[Metric.SPEED]
    val load = snap[Metric.LOAD]
    val sideMetrics = SIDE_METRICS.filter { !supportedKnown || it in snap.supported }.take(4)
    val flow = snap[Metric.FUEL_FLOW]
    val economy = snap[Metric.ECONOMY]

    Column(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "TRACK",
                color = c.accent,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                fontStyle = FontStyle.Italic,
                letterSpacing = 4.sp,
            )
            ConnectionChip(status = status, demo = s.demoMode)
            Spacer(Modifier.weight(1f))
            if (oil != null) WarningLight(label = "OIL PRESSURE", on = oil < 0.5)
            LayoutSwitchChip(label = "GAUGES", onClick = { session.update { it.copy(dashLayout = DashLayout.GAUGES) } })
            Column(horizontalAlignment = Alignment.End) {
                Text("MAZDA", color = c.text, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 5.sp)
                Text("RX-8", color = c.accent, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = 2.sp)
            }
        }
        AlertBanner(alerts = alerts)

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Left: bar readouts
            Column(
                modifier = Modifier.weight(0.8f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (m in sideMetrics) {
                    BarReadout(metric = m, value = snap[m], modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }

            // Middle: speed, rpm bar, load bar
            Column(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxHeight()
                    .background(c.surface, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(
                        Fmt.int(rpm),
                        color = c.textDim,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(" RPM", color = c.textDim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Fmt.int(speed),
                        color = c.text,
                        fontSize = 96.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                    Text("km/h", color = c.textDim, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp, bottom = 16.dp))
                }
                SegmentedBar(
                    fraction = if (rpm == null) 0f else fraction(rpm, 0.0, maxRpm.toDouble()),
                    segments = 40,
                    redFrom = s.redlineRpm.toFloat() / maxRpm,
                )
                Text("SPEED & RPM", color = c.textDim, fontSize = 13.sp, letterSpacing = 4.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(14.dp))
                SegmentedBar(
                    fraction = if (load == null) 0f else fraction(load, 0.0, 100.0),
                    segments = 32,
                    redFrom = 0.9f,
                    barHeight = 12.dp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("ENGINE LOAD  " + Fmt.value(Metric.LOAD, load) + " %", color = c.textDim, fontSize = 13.sp, letterSpacing = 2.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Text(
                        Fmt.value(Metric.FUEL_FLOW, flow) + " L/h",
                        color = c.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                    )
                    Text(
                        Fmt.value(Metric.ECONOMY, economy) + (if (Metric.ECONOMY in snap.lowConfidence) "* " else " ") + "L/100km",
                        color = c.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }

            // Right: tachometer and warm-up
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    QuarterRpmGauge(
                        rpm = rpm,
                        redlineRpm = s.redlineRpm,
                        modifier = Modifier.fillMaxSize(),
                        coldLimitRpm = if (stage == WarmupStage.READY) null else monitor.config.coldRpmLimit,
                    )
                }
                WarmupStrip(stage = stage, coolantC = coolant, coldLimitRpm = monitor.config.coldRpmLimit)
            }
        }
    }
}
