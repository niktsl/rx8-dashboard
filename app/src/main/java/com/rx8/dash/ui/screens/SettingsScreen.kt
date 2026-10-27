package com.rx8.dash.ui.screens

import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rx8.dash.core.Rx8Series
import com.rx8.dash.data.AppSettings
import com.rx8.dash.data.DashLayout
import com.rx8.dash.data.DashboardPalette
import com.rx8.dash.data.KnobAction
import com.rx8.dash.ui.components.BigButton
import com.rx8.dash.ui.components.DashCard
import com.rx8.dash.ui.components.Fmt
import com.rx8.dash.ui.components.KeyValueRow
import com.rx8.dash.ui.components.MessageBar
import com.rx8.dash.ui.components.NumberField
import com.rx8.dash.ui.components.ScreenColumn
import com.rx8.dash.ui.components.SectionLabel
import com.rx8.dash.ui.components.SwitchRow
import com.rx8.dash.ui.components.focusRing
import com.rx8.dash.ui.components.parseNum
import com.rx8.dash.ui.theme.LocalDashColors
import com.rx8.dash.ui.theme.dashColorsFor
import com.rx8.dash.ui.vm.SessionViewModel
import com.rx8.dash.ui.vm.SettingsViewModel
import java.util.Locale

private const val REDLINE_MIN = 5000
private const val REDLINE_MAX = 10000
private const val REDLINE_STEP = 100
private const val DELAY_MAX_S = 30

/** Connection, car, fuel, display, knob mapping and backup settings. */
@Composable
fun SettingsScreen(session: SessionViewModel, modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    val settings by session.settings.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val s = settings ?: AppSettings()
    val c = LocalDashColors.current

    var showDevices by remember { mutableStateOf(false) }
    var showSeries by remember { mutableStateOf(false) }
    var captureFor by remember { mutableStateOf<KnobAction?>(null) }
    var confirmImport by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }

    ScreenColumn(title = "Settings", modifier = modifier) {
        MessageBar(message = message, onDismiss = { vm.clearMessage() })

        // ---- Connection ----
        SectionLabel("CONNECTION")
        DashCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow(
                    title = "Demo mode",
                    subtitle = "Use simulated data instead of an OBD adapter.",
                    checked = s.demoMode,
                    onCheckedChange = { on -> vm.update { it.copy(demoMode = on) } },
                )
                KeyValueRow("Adapter", s.deviceAddress ?: "None selected")
                BigButton(
                    text = "Choose paired adapter",
                    onClick = { showDevices = true },
                    outlined = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    title = "Extended PIDs",
                    subtitle = "Read-only Mazda Mode 22 queries. Unvalidated entries stay hidden unless supported.",
                    checked = s.extendedPids,
                    onCheckedChange = { on -> vm.update { it.copy(extendedPids = on) } },
                )
                SwitchRow(
                    title = "Tyre pressure (TPMS)",
                    subtitle = "Read-only TPMS queries on a separate CAN address. Experimental.",
                    checked = s.tpms,
                    onCheckedChange = { on -> vm.update { it.copy(tpms = on) } },
                )
                StepperRow(
                    title = "Initial connect delay",
                    valueText = (s.initialConnectDelayMs / 1000L).toString() + " s",
                    onMinus = {
                        vm.update { it.copy(initialConnectDelayMs = (it.initialConnectDelayMs - 1000L).coerceAtLeast(0L)) }
                    },
                    onPlus = {
                        vm.update {
                            it.copy(initialConnectDelayMs = (it.initialConnectDelayMs + 1000L).coerceAtMost(DELAY_MAX_S * 1000L))
                        }
                    },
                )
                KeyValueRow("Connection", status.state.name.replace('_', ' '))
                KeyValueRow("Poll rate", Fmt.num(status.pollHz, 1) + " Hz")
                val adapter = status.adapter
                if (adapter != null) KeyValueRow("Adapter identity", adapter.identity)
                if (status.message.isNotBlank()) Text(status.message, color = c.textDim, fontSize = 14.sp)
            }
        }

        // ---- Car ----
        SectionLabel("CAR")
        DashCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyValueRow("Model", s.series.label)
                BigButton(text = "Change series", onClick = { showSeries = true }, outlined = true, modifier = Modifier.fillMaxWidth())
                StepperRow(
                    title = "Redline",
                    valueText = s.redlineRpm.toString() + " rpm",
                    onMinus = {
                        vm.update { it.copy(redlineRpm = (it.redlineRpm - REDLINE_STEP).coerceAtLeast(REDLINE_MIN)) }
                    },
                    onPlus = {
                        vm.update { it.copy(redlineRpm = (it.redlineRpm + REDLINE_STEP).coerceAtMost(REDLINE_MAX)) }
                    },
                )
            }
        }

        // ---- Fuel ----
        SectionLabel("FUEL")
        DashCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ApplyNumberRow(
                    label = "Fuel density",
                    suffix = "g/L",
                    current = s.fuelDensity,
                    decimals = 0,
                    min = AppSettings.MIN_DENSITY,
                    max = AppSettings.MAX_DENSITY,
                    onApply = { v -> vm.update { it.copy(fuelDensity = v) } },
                )
                ApplyNumberRow(
                    label = "Fuel calibration factor",
                    suffix = "x",
                    current = s.fuelCalibration,
                    decimals = 3,
                    min = AppSettings.MIN_CALIBRATION,
                    max = AppSettings.MAX_CALIBRATION,
                    onApply = { v -> vm.update { it.copy(fuelCalibration = v) } },
                )
                Text(
                    "The calibration factor is also updated automatically from full-tank fill-ups on the Trips screen.",
                    color = c.textDim,
                    fontSize = 14.sp,
                )
            }
        }

        // ---- Display ----
        SectionLabel("DISPLAY")
        DashCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dashboard layout", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                for (l in DashLayout.values()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .focusRing(RoundedCornerShape(12.dp))
                            .clickable { vm.update { it.copy(dashLayout = l) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = l == s.dashLayout, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(l.label, color = c.text, fontSize = 18.sp)
                    }
                }
                Text("Palette", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                for (p in DashboardPalette.values()) {
                    PaletteRow(palette = p, selected = p == s.palette, onClick = { vm.update { it.copy(palette = p) } })
                }
            }
        }

        // ---- Knob ----
        SectionLabel("KNOB AND KEYS")
        DashCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Rotating the knob as a scroll wheel always moves the selection. For key-based knobs and " +
                        "steering-wheel buttons, record which key does what. Head units differ, so press the key to learn it.",
                    color = c.textDim,
                    fontSize = 14.sp,
                )
                val actions = listOf(KnobAction.NEXT, KnobAction.PREVIOUS, KnobAction.SELECT, KnobAction.BACK)
                for (a in actions) {
                    val codes = s.keyMap.filter { it.value == a }.keys.sorted()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(actionLabel(a), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (codes.isEmpty()) "No key assigned" else codes.joinToString(", ") { keyName(it) },
                                    color = c.textDim,
                                    fontSize = 14.sp,
                                )
                            }
                            BigButton(text = "Press a key", onClick = { captureFor = a }, outlined = true)
                            Spacer(Modifier.width(8.dp))
                            BigButton(
                                text = "Clear",
                                onClick = { vm.update { cur -> cur.copy(keyMap = cur.keyMap.filterValues { v -> v != a }) } },
                                outlined = true,
                                enabled = codes.isNotEmpty(),
                            )
                        }
                    }
                }
                BigButton(
                    text = "Reset keys to defaults",
                    onClick = { vm.update { it.copy(keyMap = AppSettings.DEFAULT_KEY_MAP) } },
                    outlined = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- Backup ----
        SectionLabel("BACKUP")
        DashCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Export trips, samples, service history and fill-ups to a JSON file, or restore from one.",
                    color = c.textDim,
                    fontSize = 14.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    BigButton(
                        text = "Export backup",
                        onClick = { exportLauncher.launch("rx8-dash-backup.json") },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    BigButton(
                        text = "Import backup",
                        onClick = { confirmImport = true },
                        enabled = !busy,
                        outlined = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (busy) Text("Working...", color = c.accent, fontSize = 15.sp)
            }
        }
    }

    if (showDevices) {
        DevicePickerDialog(
            devices = devices,
            selectedAddress = s.deviceAddress,
            onRefresh = { vm.refreshDevices() },
            onPick = { d ->
                vm.update { it.copy(deviceAddress = d.address) }
                showDevices = false
            },
            onDismiss = { showDevices = false },
        )
    }

    if (showSeries) {
        AlertDialog(
            onDismissRequest = { showSeries = false },
            title = { Text("Car series") },
            text = {
                Column {
                    for (series in Rx8Series.values()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .focusRing(RoundedCornerShape(10.dp))
                                .clickable {
                                    vm.update { it.copy(series = series, redlineRpm = series.redlineRpm) }
                                    showSeries = false
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = series == s.series, onClick = null)
                            Text(
                                series.label + " (" + series.redlineRpm + " rpm)",
                                modifier = Modifier.padding(start = 8.dp),
                                fontSize = 16.sp,
                            )
                        }
                    }
                    Text(
                        "Choosing a series resets the redline to that series' default.",
                        color = c.textDim,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSeries = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            },
        )
    }

    val capture = captureFor
    if (capture != null) {
        KeyCaptureDialog(
            action = capture,
            onCaptured = { keyCode ->
                vm.update { cur -> cur.copy(keyMap = cur.keyMap + (keyCode to capture)) }
                captureFor = null
            },
            onDismiss = { captureFor = null },
        )
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Import backup?") },
            text = { Text("Records from the selected backup file are read into the app's database. If the file is invalid, nothing is changed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmImport = false
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Choose file") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            },
        )
    }
}

private fun actionLabel(a: KnobAction): String = when (a) {
    KnobAction.NEXT -> "Next / down"
    KnobAction.PREVIOUS -> "Previous / up"
    KnobAction.SELECT -> "Select / press"
    KnobAction.BACK -> "Back"
    KnobAction.NONE -> "None"
}

private fun keyName(code: Int): String {
    val name = KeyEvent.keyCodeToString(code)
    return name.removePrefix("KEYCODE_") + " (" + code + ")"
}

@Composable
private fun StepperRow(title: String, valueText: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val c = LocalDashColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        BigButton(text = "", icon = Icons.Filled.Remove, onClick = onMinus, outlined = true)
        Text(
            valueText,
            color = c.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(120.dp).padding(horizontal = 8.dp),
            maxLines = 1,
        )
        BigButton(text = "", icon = Icons.Filled.Add, onClick = onPlus, outlined = true)
    }
}

/** Number field with an explicit Apply button, so settings are written once instead of on every keystroke. */
@Composable
private fun ApplyNumberRow(
    label: String,
    suffix: String,
    current: Double,
    decimals: Int,
    min: Double,
    max: Double,
    onApply: (Double) -> Unit,
) {
    var text by remember(current) { mutableStateOf(String.format(Locale.US, "%." + decimals + "f", current)) }
    val parsed = parseNum(text)
    val valid = parsed != null && parsed >= min && parsed <= max
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            label = label + " (" + Fmt.num(min, decimals) + " to " + Fmt.num(max, decimals) + ")",
            value = text,
            onValueChange = { text = it },
            suffix = suffix,
            modifier = Modifier.weight(1f),
        )
        BigButton(
            text = "Apply",
            onClick = { if (parsed != null && valid) onApply(parsed) },
            enabled = valid,
            outlined = true,
        )
    }
}

@Composable
private fun PaletteRow(palette: DashboardPalette, selected: Boolean, onClick: () -> Unit) {
    val c = LocalDashColors.current
    val colors = dashColorsFor(palette)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRing(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.size(28.dp).background(colors.accent, CircleShape))
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.size(28.dp).background(colors.needle, CircleShape))
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.size(28.dp).background(colors.surfaceHigh, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(palette.label, color = c.text, fontSize = 18.sp)
    }
}

/** Records the keyCode of the next key pressed. BACK can be captured because dismiss-on-back is disabled. */
@Composable
private fun KeyCaptureDialog(action: KnobAction, onCaptured: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = LocalDashColors.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            requester.requestFocus()
        } catch (e: IllegalStateException) {
            // Not attached yet; the user can still tap Cancel.
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = c.surfaceHigh) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .focusRequester(requester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            onCaptured(event.nativeKeyEvent.keyCode)
                            true
                        } else {
                            // Swallow the matching KeyUp so it does not reach the screen below.
                            event.type == KeyEventType.KeyUp
                        }
                    }
                    .focusable(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Press a key", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Press the knob direction or button that should mean \"" + actionLabel(action) + "\".",
                    color = c.textDim,
                    fontSize = 16.sp,
                )
                BigButton(text = "Cancel", onClick = onDismiss, outlined = true)
            }
        }
    }
}
