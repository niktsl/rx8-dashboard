package com.rx8.dash.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rx8.dash.AppGraph
import com.rx8.dash.core.AlertLevel
import com.rx8.dash.core.health.StartLog
import com.rx8.dash.core.maint.DueState
import com.rx8.dash.core.maint.MaintenanceDefaults
import com.rx8.dash.core.maint.MaintenanceEvaluator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FloodRiskUi(val shortColdStreak: Int, val level: AlertLevel?)

data class OilReminderUi(val state: DueState, val text: String, val odometerKm: Double?)

class RotaryHealthViewModel(private val graph: AppGraph) : ViewModel() {

    private val startLog = StartLog()

    private val _floodRisk = MutableStateFlow(FloodRiskUi(0, null))
    val floodRisk: StateFlow<FloodRiskUi> = _floodRisk.asStateFlow()

    /** Oil-level check reminder, evaluated against the estimated odometer. */
    val oilReminder: StateFlow<OilReminderUi?> = combine(
        graph.maintenanceRepository.items,
        graph.tripRepository.trips,
        graph.tripRepository.fillUps,
    ) { items, trips, fills ->
        val odo = estimateOdometerKm(trips, fills, items)
        val item = items.firstOrNull { it.id == MaintenanceDefaults.OIL_LEVEL }
        if (item == null) {
            null
        } else if (odo == null) {
            OilReminderUi(DueState.DUE, "Odometer unknown: record a fill-up or a service", null)
        } else {
            val (state, text) = MaintenanceEvaluator.evaluate(item, odo, System.currentTimeMillis())
            OilReminderUi(state, text, odo)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Re-evaluate flood risk whenever the start log changes.
        viewModelScope.launch {
            graph.tripRepository.startLog.collect { refreshFloodRisk() }
        }
    }

    private suspend fun refreshFloodRisk() {
        val streak = runCatchingUi { graph.tripRepository.recentShortColdStreak() }.getOrDefault(0)
        _floodRisk.value = FloodRiskUi(streak, startLog.floodRisk(streak))
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { RotaryHealthViewModel(graph()) }
        }
    }
}
