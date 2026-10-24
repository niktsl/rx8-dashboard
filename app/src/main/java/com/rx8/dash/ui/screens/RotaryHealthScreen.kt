package com.rx8.dash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rx8.dash.core.AlertLevel
import com.rx8.dash.core.Metric
import com.rx8.dash.core.health.HealthConfig
import com.rx8.dash.core.health.Premix
import com.rx8.dash.core.health.RotaryHealthMonitor
import com.rx8.dash.core.health.WarmupStage
import com.rx8.dash.core.maint.DueState
import com.rx8.dash.data.AppSettings
import com.rx8.dash.ui.components.BigButton
import com.rx8.dash.ui.components.DashCard
import com.rx8.dash.ui.components.Fmt
import com.rx8.dash.ui.components.KeyValueRow
import com.rx8.dash.ui.components.NumberField
import com.rx8.dash.ui.components.ScreenColumn
import com.rx8.dash.ui.components.SectionLabel
import com.rx8.dash.ui.components.StatusPill
import com.rx8.dash.ui.components.parseNum
import com.rx8.dash.ui.theme.LocalDashColors
import com.rx8.dash.ui.vm.RotaryHealthViewModel
import com.rx8.dash.ui.vm.SessionViewModel

/** Rotary-specific care: warm-up guide, flood risk, premix calculator, oil reminder and the alert list. */
@Composable
fun RotaryHealthScreen(session: SessionViewModel, modifier: Modifier = Modifier) {
    val vm: RotaryHealthViewModel = viewModel(factory = RotaryHealthViewModel.Factory)
    val settings by session.settings.collectAsStateWithLifecycle()
    val snap by session.telemetry.collectAsStateWithLifecycle()
    val alerts by session.alerts.collectAsStateWithLifecycle()
    val flood by vm.floodRisk.collectAsStateWithLifecycle()
    val oil by vm.oilReminder.collectAsStateWithLifecycle()
    val s = settings ?: AppSettings()
    val c = LocalDashColors.current

    val config = remember(s.redlineRpm) { HealthConfig(redlineRpm = s.redlineRpm) }
    val monitor = remember(config) { RotaryHealthMonitor(config) }
    val coolant = snap[Metric.COOLANT]
    val stage = monitor.stage(coolant)

    ScreenColumn(title = "Rotary health", modifier = modifier) {
        // Warm-up guide
        DashCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Warm-up guide",
                        color = c.text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        modifier = Modifier.weight(1f),
                    )
                    val (label, color) = when (stage) {
                        WarmupStage.COLD -> Pair("COLD", c.info)
                        WarmupStage.WARMING -> Pair("WARMING", c.caution)
                        WarmupStage.READY -> Pair("READY", c.ok)
                    }
                    StatusPill(text = label, color = color)
                }
                KeyValueRow("Coolant", if (coolant == null) "--" else Fmt.int(coolant) + " °C")
                GuideLine(
                    active = stage == WarmupStage.COLD,
                    text = "Below ${Fmt.int(config.minDriveCoolantC)} °C: idle and drive gently. Keep load and revs low.",
                )
                GuideLine(
                    active = stage == WarmupStage.WARMING,
                    text = "${Fmt.int(config.minDriveCoolantC)} to ${Fmt.int(config.readyCoolantC)} °C: " +
                        "keep below ${config.coldRpmLimit} rpm until the coolant is up to temperature.",
                )
                GuideLine(
                    active = stage == WarmupStage.READY,
                    text = "${Fmt.int(config.readyCoolantC)} °C and above: fully warm. " +
                        "Revs up to the ${s.redlineRpm} rpm redline are fine.",
                )
            }
        }

        // Cold-start / flood risk
        DashCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Cold starts and flood risk", color = c.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                KeyValueRow("Recent short cold trips in a row", flood.shortColdStreak.toString())
                val level = flood.level
                if (level == null) {
                    Text("No flood risk detected.", color = c.ok, fontSize = 16.sp)
                } else {
                    val color = c.forLevel(level)
                    val text = when (level) {
                        AlertLevel.INFO -> "Several short cold trips recently."
                        AlertLevel.CAUTION ->
                            "Repeated short cold trips. Take a longer drive so the engine reaches full temperature."
                        AlertLevel.WARNING ->
                            "High flood risk after many short cold trips. Drive until fully warm; " +
                                "see the owner's manual if the engine is hard to start."
                    }
                    StatusPill(text = level.name, color = color)
                    Text(text, color = c.text, fontSize = 16.sp)
                }
                Text(
                    "A trip counts as a short cold trip when it is shorter than ${config.shortTripMinutes} minutes " +
                        "and the engine never reached operating temperature.",
                    color = c.textDim,
                    fontSize = 14.sp,
                )
            }
        }

        // Premix calculator
        PremixCard()

        // Oil top-up reminder
        DashCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Oil top-up reminder", color = c.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Rotary engines consume oil by design. Check the level regularly and top up as needed.",
                    color = c.textDim,
                    fontSize = 14.sp,
                )
                val reminder = oil
                if (reminder == null) {
                    Text("No oil-level check item in the maintenance list.", color = c.textDim, fontSize = 16.sp)
                } else {
                    val color = when (reminder.state) {
                        DueState.OK -> c.ok
                        DueState.SOON -> c.caution
                        DueState.DUE, DueState.OVERDUE -> c.warning
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatusPill(text = reminder.state.name, color = color)
                        Text(reminder.text, color = c.text, fontSize = 16.sp)
                    }
                }
            }
        }

        // Alerts
        SectionLabel("ACTIVE ALERTS")
        if (alerts.isEmpty()) {
            DashCard { Text("No active alerts.", color = c.textDim, fontSize = 16.sp) }
        } else {
            for (a in alerts.sortedByDescending { it.level.ordinal }) {
                DashCard {
                    Column {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            StatusPill(text = a.level.name, color = c.forLevel(a.level))
                            Text(a.title, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        }
                        Text(a.message, color = c.textDim, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideLine(active: Boolean, text: String) {
    val c = LocalDashColors.current
    Text(
        text = (if (active) "> " else "  ") + text,
        color = if (active) c.text else c.textDim,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        fontSize = 16.sp,
    )
}

@Composable
private fun PremixCard() {
    val c = LocalDashColors.current
    var litresText by remember { mutableStateOf("") }
    var ratio by remember { mutableStateOf(Premix.STREET_ML_PER_LITRE) }
    val litres = parseNum(litresText)
    val ml = if (litres == null) 0.0 else Premix.mlFor(litres, ratio)
    DashCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Premix calculator", color = c.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "Engine oil to add to the fuel tank for the fuel you are about to put in.",
                color = c.textDim,
                fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                BigButton(
                    text = "Street 2 mL/L",
                    onClick = { ratio = Premix.STREET_ML_PER_LITRE },
                    outlined = ratio != Premix.STREET_ML_PER_LITRE,
                    modifier = Modifier.weight(1f),
                )
                BigButton(
                    text = "Sport 4 mL/L",
                    onClick = { ratio = Premix.SPORT_ML_PER_LITRE },
                    outlined = ratio != Premix.SPORT_ML_PER_LITRE,
                    modifier = Modifier.weight(1f),
                )
            }
            NumberField(
                label = "Fuel to add",
                value = litresText,
                onValueChange = { litresText = it },
                suffix = "L",
                modifier = Modifier.fillMaxWidth(),
            )
            KeyValueRow("Oil to add", Fmt.num(ml, 0) + " mL")
        }
    }
}
