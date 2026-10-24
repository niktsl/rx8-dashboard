package com.rx8.dash.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rx8.dash.data.FillUpEntity
import com.rx8.dash.data.TripEntity
import com.rx8.dash.ui.components.BigButton
import com.rx8.dash.ui.components.DashCard
import com.rx8.dash.ui.components.Fmt
import com.rx8.dash.ui.components.KeyValueRow
import com.rx8.dash.ui.components.MessageBar
import com.rx8.dash.ui.components.NumberField
import com.rx8.dash.ui.components.ScreenColumn
import com.rx8.dash.ui.components.SectionLabel
import com.rx8.dash.ui.components.focusRing
import com.rx8.dash.ui.components.parseNum
import com.rx8.dash.ui.theme.LocalDashColors
import com.rx8.dash.ui.vm.SessionViewModel
import com.rx8.dash.ui.vm.TripSeries
import com.rx8.dash.ui.vm.TripsViewModel
import java.util.Locale

/** Trip list, per-trip details with charts and CSV export, acceleration timer results and fill-ups. */
@Composable
fun TripsScreen(session: SessionViewModel, modifier: Modifier = Modifier) {
    val vm: TripsViewModel = viewModel(factory = TripsViewModel.Factory)
    val trips by vm.trips.collectAsStateWithLifecycle()
    val fills by vm.fillUps.collectAsStateWithLifecycle()
    val selectedId by vm.selectedTripId.collectAsStateWithLifecycle()
    val series by vm.series.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val accel by session.accelRuns.collectAsStateWithLifecycle()
    val c = LocalDashColors.current

    var showFillDialog by remember { mutableStateOf(false) }
    var confirmDeleteTripId by remember { mutableStateOf<Long?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            vm.exportCsv(uri)
        } else {
            vm.pendingExportTripId = null
        }
    }

    val selected: TripEntity? = trips.firstOrNull { it.id == selectedId }

    ScreenColumn(
        title = "Trips",
        modifier = modifier,
        actions = {
            BigButton(text = "Add fill-up", icon = Icons.Filled.Add, onClick = { showFillDialog = true })
        },
    ) {
        MessageBar(message = message, onDismiss = { vm.clearMessage() })

        if (selected != null) {
            TripDetails(
                trip = selected,
                series = series?.takeIf { it.tripId == selected.id },
                onClose = { vm.select(null) },
                onExport = {
                    vm.pendingExportTripId = selected.id
                    exportLauncher.launch("rx8-trip-" + selected.id + ".csv")
                },
                onDelete = { confirmDeleteTripId = selected.id },
            )
        }

        SectionLabel("TRIPS")
        if (trips.isEmpty()) {
            DashCard { Text("No trips recorded yet. A trip is logged while the adapter is connected.", color = c.textDim, fontSize = 16.sp) }
        } else {
            for (t in trips) {
                TripRow(trip = t, selected = t.id == selectedId, onClick = { vm.select(t.id) })
            }
        }

        SectionLabel("ACCELERATION TIMERS")
        DashCard {
            Column {
                if (accel.isEmpty()) {
                    Text(
                        "No runs yet. 0-100 and 60-130 km/h are timed automatically from the speed PID.",
                        color = c.textDim,
                        fontSize = 16.sp,
                    )
                } else {
                    for (r in accel.sortedByDescending { it.atMs }) {
                        KeyValueRow(
                            label = r.kind + "  ·  " + Fmt.dateTime(r.atMs),
                            value = String.format(Locale.US, "%.2f s", r.seconds),
                        )
                    }
                }
            }
        }

        SectionLabel("FILL-UPS")
        if (fills.isEmpty()) {
            DashCard {
                Text(
                    "No fill-ups. Entering full-tank fill-ups calibrates the fuel economy estimate.",
                    color = c.textDim,
                    fontSize = 16.sp,
                )
            }
        } else {
            for (f in fills) {
                FillUpRow(fill = f, onDelete = { vm.deleteFillUp(f.id) })
            }
        }
    }

    if (showFillDialog) {
        FillUpDialog(
            onDismiss = { showFillDialog = false },
            onSave = { odo, litres, full, notes ->
                vm.addFillUp(odo, litres, full, notes)
                showFillDialog = false
            },
        )
    }

    val deleteId = confirmDeleteTripId
    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTripId = null },
            title = { Text("Delete this trip?") },
            text = { Text("The trip and its logged samples are removed. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteTrip(deleteId)
                        confirmDeleteTripId = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteTripId = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun TripRow(trip: TripEntity, selected: Boolean, onClick: () -> Unit) {
    val c = LocalDashColors.current
    val now = System.currentTimeMillis()
    val end = trip.endMs
    val duration = Fmt.duration((end ?: now) - trip.startMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) c.surfaceHigh else c.surface, RoundedCornerShape(16.dp))
            .focusRing()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1.4f)) {
            Text(Fmt.dateTime(trip.startMs), color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(
                if (end == null) "In progress · $duration" else duration,
                color = if (end == null) c.accent else c.textDim,
                fontSize = 14.sp,
            )
        }
        Text(Fmt.num(trip.distanceKm, 1) + " km", color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(
            text = if (trip.avgLPer100 != null) Fmt.num(trip.avgLPer100, 1) + " L/100" else "-- L/100",
            color = c.text,
            fontSize = 17.sp,
            modifier = Modifier.weight(1f),
        )
        Text("max " + Fmt.int(trip.maxRpm) + " rpm", color = c.textDim, fontSize = 15.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TripDetails(
    trip: TripEntity,
    series: TripSeries?,
    onClose: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalDashColors.current
    DashCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Trip " + Fmt.dateTime(trip.startMs), color = c.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            KeyValueRow("Distance", Fmt.num(trip.distanceKm, 1) + " km")
            KeyValueRow("Fuel", Fmt.num(trip.fuelLitres, 2) + " L")
            KeyValueRow("Average economy", if (trip.avgLPer100 != null) Fmt.num(trip.avgLPer100, 1) + " L/100 km" else "--")
            KeyValueRow("Average speed", Fmt.num(trip.avgSpeedKmh, 0) + " km/h")
            KeyValueRow("Max speed", Fmt.num(trip.maxSpeedKmh, 0) + " km/h")
            KeyValueRow("Max rpm", Fmt.num(trip.maxRpm, 0))
            KeyValueRow("Max coolant", if (trip.maxCoolantC != null) Fmt.num(trip.maxCoolantC, 0) + " °C" else "--")
            KeyValueRow("Cold start", if (trip.coldStart) "Yes" else "No")

            if (series == null || series.timesMs.size < 2) {
                Text("Not enough samples for a chart.", color = c.textDim, fontSize = 15.sp)
            } else {
                LineChart("RPM", "rpm", series.rpm, c.accent)
                LineChart("Speed", "km/h", series.speed, c.info)
                LineChart("Coolant", "°C", series.coolant, c.caution)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                BigButton(text = "Export CSV", icon = Icons.Filled.FileDownload, onClick = onExport, modifier = Modifier.weight(1f))
                BigButton(text = "Delete", icon = Icons.Filled.Delete, onClick = onDelete, outlined = true, danger = true, modifier = Modifier.weight(1f))
                BigButton(text = "Close", onClick = onClose, outlined = true, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Simple line chart; null samples break the line. Range labels are the series min and max. */
@Composable
private fun LineChart(title: String, unit: String, values: List<Double?>, color: Color) {
    val c = LocalDashColors.current
    val nums = values.filterNotNull().filter { !it.isNaN() && !it.isInfinite() }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (nums.isEmpty()) {
            Text("$title: no data", color = c.textDim, fontSize = 14.sp)
            return@Column
        }
        val lo = nums.min()
        val hi = nums.max()
        Text(
            text = title + "  " + Fmt.num(lo, 0) + " to " + Fmt.num(hi, 0) + " " + unit,
            color = c.textDim,
            fontSize = 14.sp,
        )
        val gridColor = c.gaugeTrack
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .background(c.surfaceHigh, RoundedCornerShape(8.dp)),
        ) {
            val w = size.width
            val h = size.height
            val span = if (hi - lo < 1e-9) 1.0 else hi - lo
            for (i in 1..3) {
                val y = h * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            val n = values.size
            val path = Path()
            var penDown = false
            for (i in 0 until n) {
                val v = values[i]
                if (v == null || v.isNaN() || v.isInfinite()) {
                    penDown = false
                    continue
                }
                val x = if (n <= 1) 0f else w * i / (n - 1).toFloat()
                val y = (h - ((v - lo) / span * (h - 8f) + 4f)).toFloat()
                if (penDown) {
                    path.lineTo(x, y)
                } else {
                    path.moveTo(x, y)
                    penDown = true
                }
            }
            drawPath(path, color, style = Stroke(width = 2.5f))
        }
    }
}

@Composable
private fun FillUpRow(fill: FillUpEntity, onDelete: () -> Unit) {
    val c = LocalDashColors.current
    DashCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    Fmt.date(fill.timestampMs) + "  ·  " + Fmt.num(fill.litres, 2) + " L" +
                        if (fill.fullTank) "  ·  full tank" else "  ·  partial",
                    color = c.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                Text(
                    "Odometer " + Fmt.num(fill.odometerKm, 0) + " km" +
                        if (fill.notes.isNotBlank()) "  ·  " + fill.notes else "",
                    color = c.textDim,
                    fontSize = 14.sp,
                )
            }
            BigButton(text = "Delete", icon = Icons.Filled.Delete, onClick = onDelete, outlined = true, danger = true)
        }
    }
}

@Composable
private fun FillUpDialog(onDismiss: () -> Unit, onSave: (Double, Double, Boolean, String) -> Unit) {
    var odo by remember { mutableStateOf("") }
    var litres by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(true) }
    var notes by remember { mutableStateOf("") }
    val odoValue = parseNum(odo)
    val litresValue = parseNum(litres)
    val valid = odoValue != null && odoValue > 0.0 && litresValue != null && litresValue > 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add fill-up") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Odometer", odo, { odo = it }, suffix = "km", modifier = Modifier.fillMaxWidth())
                NumberField("Fuel added", litres, { litres = it }, suffix = "L", modifier = Modifier.fillMaxWidth())
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { full = !full },
                ) {
                    Checkbox(checked = full, onCheckedChange = null)
                    Text("Filled to full tank", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { if (odoValue != null && litresValue != null) onSave(odoValue, litresValue, full, notes.trim()) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        },
    )
}
