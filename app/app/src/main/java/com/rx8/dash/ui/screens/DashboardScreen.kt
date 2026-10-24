package com.rx8.dash.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rx8.dash.AppGraph
import com.rx8.dash.core.Metric
import com.rx8.dash.core.health.RotaryHealthMonitor
import com.rx8.dash.core.health.WarmupStage
import com.rx8.dash.data.AppSettings
import com.rx8.dash.ui.components.AlertBanner
import com.rx8.dash.ui.components.ArcGauge
import com.rx8.dash.ui.components.ConnectionChip
import com.rx8.dash.ui.components.Fmt
import com.rx8.dash.ui.components.MediaMiniPanel
import com.rx8.dash.ui.components.TachometerGauge
import com.rx8.dash.ui.components.WarningLight
import com.rx8.dash.ui.components.focusRing
import com.rx8.dash.ui.theme.LocalDashColors
import com.rx8.dash.ui.vm.DashboardViewModel
import com.rx8.dash.ui.vm.SessionViewModel

private const val GRID_COLUMNS = 3

/**
 * Main dashboard: tachometer with warm-up strip on the left, customizable gauge slots and the media mini-panel on the
 * right. Tap or long-press a slot (or press SELECT on it with the knob) to pick another metric.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(session: SessionViewModel, graph: AppGraph, modifier: Modifier = Modifier) {
    val vm: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory)
    val settings by session.settings.collectAsStateWithLifecycle()
    val snap by session.telemetry.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val alerts by session.alerts.collectAsStateWithLifecycle()
    val s = settings ?: AppSettings()
    val c = LocalDashColors.current

    val monitor = remember(s.redlineRpm) { RotaryHealthMonitor(s.toHealthConfig()) }
    val coolant = snap[Metric.COOLANT]
    val stage = monitor.stage(coolant)
    val supportedKnown = snap.supported.isNotEmpty()
    val oil = snap[Metric.OIL_PRESSURE_OK]
    var pickerIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionChip(status = status, demo = s.demoMode)
            Spacer(Modifier.weight(1f))
            if (oil != null) WarningLight(label = "OIL PRESSURE", on = oil < 0.5)
        }
        AlertBanner(alerts = alerts)

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(0.9f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TachometerGauge(
                        rpm = snap[Metric.RPM],
                        redlineRpm = s.redlineRpm,
                        modifier = Modifier.fillMaxHeight(),
                        coldLimitRpm = if (stage == WarmupStage.READY) null else monitor.config.coldRpmLimit,
                    )
                }
                WarmupStrip(stage = stage, coolantC = coolant, coldLimitRpm = monitor.config.coldRpmLimit)
            }

            Column(
                modifier = Modifier.weight(1.1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val slots = s.gaugeSlots
                // Cell index == slots.size is the "add gauge" tile.
                val cellIndices: List<Int> =
                    if (slots.size < DashboardViewModel.MAX_SLOTS) (0..slots.size).toList() else slots.indices.toList()
                val rows = cellIndices.chunked(GRID_COLUMNS)
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rows.forEach { rowCells ->
                        Row(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowCells.forEach { index ->
                                val cellModifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(c.surface, RoundedCornerShape(16.dp))
                                    .focusRing()
                                    .combinedClickable(
                                        onClick = { pickerIndex = index },
                                        onLongClick = { pickerIndex = index },
                                    )
                                if (index < slots.size) {
                                    val metric = slots[index]
                                    Box(modifier = cellModifier) {
                                        ArcGauge(
                                            metric = metric,
                                            value = snap[metric],
                                            supported = !supportedKnown || metric in snap.supported,
                                            lowConfidence = metric in snap.lowConfidence,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                } else {
                                    Box(modifier = cellModifier, contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = "Add gauge",
                                            tint = c.textDim,
                                        )
                                    }
                                }
                            }
                            for (pad in rowCells.size until GRID_COLUMNS) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                MediaMiniPanel(controller = graph.mediaController)
            }
        }
    }

    val idx = pickerIndex
    if (idx != null) {
        val current: Metric? = s.gaugeSlots.getOrNull(idx)
        GaugePickerDialog(
            title = if (current != null) "Gauge ${idx + 1}: ${current.label}" else "Add gauge",
            supported = snap.supported,
            current = current,
            onPick = { metric ->
                if (current != null) vm.setSlot(idx, metric) else vm.addSlot(metric)
                pickerIndex = null
            },
            onRemove = if (current != null && s.gaugeSlots.size > 1) {
                {
                    vm.removeSlot(idx)
                    pickerIndex = null
                }
            } else {
                null
            },
            onDismiss = { pickerIndex = null },
        )
    }
}

@Composable
private fun WarmupStrip(stage: WarmupStage, coolantC: Double?, coldLimitRpm: Int, modifier: Modifier = Modifier) {
    val c = LocalDashColors.current
    val stages = listOf(
        Triple(WarmupStage.COLD, "COLD", c.info),
        Triple(WarmupStage.WARMING, "WARMING", c.caution),
        Triple(WarmupStage.READY, "READY", c.ok),
    )
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for ((st, label, color) in stages) {
                val active = st == stage
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .background(
                            if (active) color.copy(alpha = 0.85f) else c.surface,
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (active) androidx.compose.ui.graphics.Color.Black else c.textDim,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }
        }
        val coolantText = if (coolantC == null) "coolant --" else "coolant " + Fmt.int(coolantC) + " °C"
        val hint = when (stage) {
            WarmupStage.COLD -> "Idle and drive gently"
            WarmupStage.WARMING -> "Stay below $coldLimitRpm rpm"
            WarmupStage.READY -> "Fully warm"
        }
        Text("$coolantText · $hint", color = c.textDim, fontSize = 14.sp)
    }
}

@Composable
private fun GaugePickerDialog(
    title: String,
    supported: Set<Metric>,
    current: Metric?,
    onPick: (Metric) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val c = LocalDashColors.current
    // Before the first poll nothing is known to be supported, so every pickable metric is offered.
    val options = DashboardViewModel.PICKABLE.filter { supported.isEmpty() || it in supported || it == current }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                for (m in options) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .focusRing(RoundedCornerShape(10.dp))
                            .clickable { onPick(m) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = m.label,
                            color = if (m == current) c.accent else c.text,
                            fontWeight = if (m == current) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 18.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (m.unit.isNotEmpty()) Text(m.unit, color = c.textDim, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (onRemove != null) {
                TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove gauge") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        },
    )
}
