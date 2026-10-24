package com.rx8.dash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rx8.dash.core.CanTarget
import com.rx8.dash.core.Metric
import com.rx8.dash.core.diag.Dtc
import com.rx8.dash.core.diag.DtcDescriptions
import com.rx8.dash.core.pid.PidRegistry
import com.rx8.dash.ui.components.BigButton
import com.rx8.dash.ui.components.DashCard
import com.rx8.dash.ui.components.Fmt
import com.rx8.dash.ui.components.KeyValueRow
import com.rx8.dash.ui.components.MessageBar
import com.rx8.dash.ui.components.ScreenColumn
import com.rx8.dash.ui.components.SectionLabel
import com.rx8.dash.ui.components.StatusPill
import com.rx8.dash.ui.components.SwitchRow
import com.rx8.dash.ui.theme.LocalDashColors
import com.rx8.dash.ui.vm.DiagnosticsViewModel
import com.rx8.dash.ui.vm.SessionViewModel

/** Read DTCs, readiness, VIN and freeze frame; guarded Clear DTCs; optional read-only extended PID scanner. */
@Composable
fun DiagnosticsScreen(session: SessionViewModel, modifier: Modifier = Modifier) {
    val vm: DiagnosticsViewModel = viewModel(factory = DiagnosticsViewModel.Factory)
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snap by session.telemetry.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val c = LocalDashColors.current
    // Re-evaluated whenever the connection state changes.
    val available = remember(status.state) { vm.isAvailable() }

    // 0 = no dialog, 1 = first confirmation, 2 = final confirmation.
    var clearStep by remember { mutableStateOf(0) }
    var scannerOn by remember { mutableStateOf(false) }
    var customDid by remember { mutableStateOf("") }

    ScreenColumn(title = "Diagnostics", modifier = modifier) {
        MessageBar(message = ui.message, onDismiss = { vm.clearMessage() })

        if (!available) {
            DashCard {
                Text(
                    "Diagnostics need a live adapter connection. They are not available in demo mode or while " +
                        "the adapter is disconnected.",
                    color = c.caution,
                    fontSize = 16.sp,
                )
            }
        }
        if (ui.busy) {
            Text(ui.busyLabel + "...", color = c.accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            BigButton("Read codes", onClick = { vm.readCodes() }, enabled = !ui.busy, modifier = Modifier.weight(1f))
            BigButton("Read VIN", onClick = { vm.readVin() }, enabled = !ui.busy, outlined = true, modifier = Modifier.weight(1f))
            BigButton("Freeze frame", onClick = { vm.readFreezeFrame() }, enabled = !ui.busy, outlined = true, modifier = Modifier.weight(1f))
        }

        SectionLabel("TROUBLE CODES")
        DtcCard("Stored", ui.stored)
        DtcCard("Pending", ui.pending)
        DtcCard("Permanent", ui.permanent)

        SectionLabel("EMISSIONS READINESS")
        DashCard {
            val r = ui.readiness
            if (r == null) {
                Text("Not read yet.", color = c.textDim, fontSize = 16.sp)
            } else {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(text = if (r.milOn) "MIL ON" else "MIL OFF", color = if (r.milOn) c.warning else c.ok)
                        Text(r.dtcCount.toString() + " code(s) reported by the ECU", color = c.text, fontSize = 16.sp)
                    }
                    for ((name, state) in r.monitors) {
                        val supported = state.first
                        val complete = state.second
                        KeyValueRow(
                            label = name,
                            value = if (!supported) "Not supported" else if (complete) "Complete" else "Incomplete",
                            valueColor = if (!supported) c.textDim else if (complete) c.ok else c.caution,
                        )
                    }
                }
            }
        }

        SectionLabel("VEHICLE")
        DashCard {
            val vin = ui.vin
            KeyValueRow(
                "VIN",
                when {
                    vin != null -> vin
                    ui.vinRead -> "Not reported by the ECU"
                    else -> "Not read yet"
                },
            )
        }

        SectionLabel("FREEZE FRAME")
        DashCard {
            val ff = ui.freezeFrame
            if (ff == null) {
                Text("Not read yet.", color = c.textDim, fontSize = 16.sp)
            } else if (ff.isEmpty()) {
                Text("No freeze frame data stored.", color = c.textDim, fontSize = 16.sp)
            } else {
                Column {
                    for ((metric, value) in ff) {
                        KeyValueRow(metric.label, Fmt.value(metric, value) + if (metric.unit.isNotEmpty()) " " + metric.unit else "")
                    }
                }
            }
        }

        SectionLabel("CLEAR CODES")
        DashCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Clearing erases stored codes and freeze frame data and resets all readiness monitors. " +
                        "The engine must be OFF with the ignition ON.",
                    color = c.textDim,
                    fontSize = 15.sp,
                )
                BigButton(
                    text = "Clear codes...",
                    onClick = { clearStep = 1 },
                    enabled = !ui.busy,
                    danger = true,
                    outlined = true,
                )
            }
        }

        SectionLabel("EXTENDED PID SCANNER")
        DashCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow(
                    title = "Extended PID scanner",
                    subtitle = "Read-only Mode 22 queries. Nothing is ever written to the car.",
                    checked = scannerOn,
                    onCheckedChange = { scannerOn = it },
                )
                if (scannerOn) {
                    for (d in PidRegistry.scannerDids) {
                        KeyValueRow(
                            label = "0x" + Fmt.hex(d.did, 4) + "  " + d.label + if (d.validated) "" else " (unvalidated)",
                            value = d.target.tx + " > " + d.target.rx,
                        )
                    }
                    BigButton(
                        text = "Scan known DIDs",
                        onClick = {
                            val dids = PidRegistry.scannerDids.filter { it.target == CanTarget.ENGINE }.map { it.did }
                            vm.scan(dids, CanTarget.ENGINE)
                        },
                        enabled = !ui.busy,
                        outlined = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customDid,
                            onValueChange = { customDid = it.filter { ch -> ch.isLetterOrDigit() }.take(4).uppercase() },
                            label = { Text("Custom DID (hex, 4 digits)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        BigButton(
                            text = "Scan",
                            onClick = {
                                val did = customDid.toIntOrNull(16)
                                if (did != null && did in 0..0xFFFF) vm.scan(listOf(did), CanTarget.ENGINE)
                            },
                            enabled = !ui.busy && customDid.toIntOrNull(16) != null,
                            outlined = true,
                        )
                    }
                    if (ui.scan.isNotEmpty()) {
                        for (row in ui.scan) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "0x" + Fmt.hex(row.did, 4),
                                    color = c.text,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(0.4f),
                                )
                                Text(
                                    Fmt.bytes(row.bytes),
                                    color = if (row.bytes == null) c.textDim else c.text,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (clearStep == 1) {
        AlertDialog(
            onDismissRequest = { clearStep = 0 },
            title = { Text("Clear trouble codes?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This sends the OBD clear command to the ECU.")
                    Text("Switch the engine OFF and leave the ignition ON before you continue.")
                    Text(
                        "All readiness monitors reset to incomplete and need several drive cycles to finish, " +
                            "so an emissions inspection may fail until they do. Freeze frame data is erased.",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { clearStep = 2 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { clearStep = 0 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            },
        )
    }
    if (clearStep == 2) {
        var engineOffConfirmed by remember { mutableStateOf(false) }
        val rpm = snap[Metric.RPM]
        AlertDialog(
            onDismissRequest = { clearStep = 0 },
            title = { Text("Final confirmation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Live engine speed: " + (if (rpm == null) "unknown" else Fmt.int(rpm) + " rpm") +
                            ". The app refuses to clear while the engine is running.",
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { engineOffConfirmed = !engineOffConfirmed },
                    ) {
                        Checkbox(checked = engineOffConfirmed, onCheckedChange = null)
                        Text(
                            "The engine is OFF and the ignition is ON",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = engineOffConfirmed,
                    onClick = {
                        clearStep = 0
                        vm.clearCodes(rpm)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Clear codes now") }
            },
            dismissButton = {
                TextButton(onClick = { clearStep = 0 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DtcCard(title: String, codes: List<Dtc>?) {
    val c = LocalDashColors.current
    DashCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = c.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (codes == null) {
                Text("Not read yet.", color = c.textDim, fontSize = 16.sp)
            } else if (codes.isEmpty()) {
                Text("None.", color = c.ok, fontSize = 16.sp)
            } else {
                for (d in codes) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            d.code,
                            color = c.warning,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier.weight(0.25f),
                        )
                        Text(
                            d.description ?: DtcDescriptions.describe(d.code) ?: "No description available",
                            color = c.text,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
