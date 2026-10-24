package com.rx8.dash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
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
import com.rx8.dash.core.maint.DueState
import com.rx8.dash.core.maint.MaintenanceItem
import com.rx8.dash.ui.components.BigButton
import com.rx8.dash.ui.components.DashCard
import com.rx8.dash.ui.components.Fmt
import com.rx8.dash.ui.components.MessageBar
import com.rx8.dash.ui.components.NumberField
import com.rx8.dash.ui.components.ScreenColumn
import com.rx8.dash.ui.components.SectionLabel
import com.rx8.dash.ui.components.StatusPill
import com.rx8.dash.ui.components.parseNum
import com.rx8.dash.ui.theme.LocalDashColors
import com.rx8.dash.ui.vm.MaintenanceRow
import com.rx8.dash.ui.vm.MaintenanceViewModel

/** Service reminders with due states, mark-done at the typed odometer, and editable intervals. */
@Composable
fun MaintenanceScreen(modifier: Modifier = Modifier) {
    val vm: MaintenanceViewModel = viewModel(factory = MaintenanceViewModel.Factory)
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val c = LocalDashColors.current

    var odoText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MaintenanceItem?>(null) }
    val typedOdo = parseNum(odoText)
    val currentOdo: Double? = typedOdo ?: ui.odometerKm

    ScreenColumn(title = "Maintenance", modifier = modifier) {
        MessageBar(message = message, onDismiss = { vm.clearMessage() })

        DashCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Current odometer", color = c.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                val estimate = ui.estimatedOdometerKm
                Text(
                    if (estimate != null) {
                        "Estimated from fill-ups and trips: " + Fmt.num(estimate, 0) + " km. Type the real value to override."
                    } else {
                        "The car's odometer is not readable over OBD. Type it in to evaluate the service items."
                    },
                    color = c.textDim,
                    fontSize = 14.sp,
                )
                NumberField(
                    label = "Odometer",
                    value = odoText,
                    onValueChange = {
                        odoText = it
                        vm.setOdometer(parseNum(it))
                    },
                    suffix = "km",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionLabel("SERVICE ITEMS")
        if (ui.rows.isEmpty()) {
            DashCard { Text("No maintenance items.", color = c.textDim, fontSize = 16.sp) }
        }
        for (row in ui.rows) {
            MaintenanceCard(
                row = row,
                canMarkDone = currentOdo != null,
                onMarkDone = { if (currentOdo != null) vm.markDone(row.item.id, currentOdo) },
                onEdit = { editing = row.item },
            )
        }
    }

    val item = editing
    if (item != null) {
        IntervalDialog(
            item = item,
            onDismiss = { editing = null },
            onSave = { km, days ->
                vm.updateIntervals(item, km, days)
                editing = null
            },
        )
    }
}

@Composable
private fun MaintenanceCard(row: MaintenanceRow, canMarkDone: Boolean, onMarkDone: () -> Unit, onEdit: () -> Unit) {
    val c = LocalDashColors.current
    val item = row.item
    val color = when (row.state) {
        DueState.OK -> c.ok
        DueState.SOON -> c.caution
        DueState.DUE -> c.warning
        DueState.OVERDUE -> c.warning
    }
    DashCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, color = c.text, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(intervalText(item), color = c.textDim, fontSize = 14.sp)
                }
                StatusPill(text = row.state.name, color = color)
            }
            Text(row.text, color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(lastDoneText(item), color = c.textDim, fontSize = 14.sp)
            if (item.notes.isNotBlank()) Text(item.notes, color = c.textDim, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                BigButton(
                    text = "Mark done now",
                    icon = Icons.Filled.Check,
                    onClick = onMarkDone,
                    enabled = canMarkDone,
                    modifier = Modifier.weight(1f),
                )
                BigButton(
                    text = "Edit intervals",
                    icon = Icons.Filled.Edit,
                    onClick = onEdit,
                    outlined = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun intervalText(item: MaintenanceItem): String {
    val parts = ArrayList<String>(2)
    val km = item.intervalKm
    val days = item.intervalDays
    if (km != null && km > 0) parts.add("every $km km")
    if (days != null && days > 0) parts.add("every $days days")
    return if (parts.isEmpty()) "No interval set" else parts.joinToString(" or ")
}

private fun lastDoneText(item: MaintenanceItem): String {
    val km = item.lastKm
    val date = item.lastDateMs
    if (km == null && date == null) return "Never recorded"
    val parts = ArrayList<String>(2)
    if (km != null) parts.add("at " + Fmt.num(km, 0) + " km")
    if (date != null) parts.add("on " + Fmt.date(date))
    return "Last done " + parts.joinToString(" ")
}

@Composable
private fun IntervalDialog(item: MaintenanceItem, onDismiss: () -> Unit, onSave: (Int?, Int?) -> Unit) {
    var kmText by remember { mutableStateOf(item.intervalKm?.toString() ?: "") }
    var daysText by remember { mutableStateOf(item.intervalDays?.toString() ?: "") }
    val kmValue = parseNum(kmText)?.toInt()
    val daysValue = parseNum(daysText)?.toInt()
    val blankOrNumber = (kmText.isBlank() || kmValue != null) && (daysText.isBlank() || daysValue != null)
    val hasInterval = (kmValue != null && kmValue > 0) || (daysValue != null && daysValue > 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Leave a field empty to ignore that dimension.")
                NumberField("Interval distance", kmText, { kmText = it }, suffix = "km", decimal = false, modifier = Modifier.fillMaxWidth())
                NumberField("Interval time", daysText, { daysText = it }, suffix = "days", decimal = false, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = blankOrNumber && hasInterval,
                onClick = { onSave(kmValue, daysValue) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        },
    )
}
