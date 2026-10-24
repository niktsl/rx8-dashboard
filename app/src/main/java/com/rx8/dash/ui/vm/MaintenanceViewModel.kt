package com.rx8.dash.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rx8.dash.AppGraph
import com.rx8.dash.core.maint.DueState
import com.rx8.dash.core.maint.MaintenanceEvaluator
import com.rx8.dash.core.maint.MaintenanceItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MaintenanceRow(val item: MaintenanceItem, val state: DueState, val text: String)

data class MaintenanceUi(
    val rows: List<MaintenanceRow> = emptyList(),
    val estimatedOdometerKm: Double? = null,
    val odometerKm: Double? = null,
)

class MaintenanceViewModel(private val graph: AppGraph) : ViewModel() {

    /** Odometer typed by the user this session; overrides the estimate when set. */
    private val manualOdometer = MutableStateFlow<Double?>(null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val ui: StateFlow<MaintenanceUi> = combine(
        graph.maintenanceRepository.items,
        graph.tripRepository.trips,
        graph.tripRepository.fillUps,
        manualOdometer,
    ) { items, trips, fills, manual ->
        val estimate = estimateOdometerKm(trips, fills, items)
        val odo = manual ?: estimate
        val now = System.currentTimeMillis()
        val rows = items.map { item ->
            if (odo == null && item.intervalKm != null) {
                MaintenanceRow(item, DueState.DUE, "Enter the odometer")
            } else {
                val (state, text) = MaintenanceEvaluator.evaluate(item, odo ?: 0.0, now)
                MaintenanceRow(item, state, text)
            }
        }
        MaintenanceUi(rows, estimate, odo)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MaintenanceUi())

    fun setOdometer(km: Double?) {
        manualOdometer.value = km?.takeIf { it.isFinite() && it >= 0.0 }
    }

    fun markDone(id: String, odometerKm: Double) {
        if (!odometerKm.isFinite() || odometerKm < 0.0) {
            _message.value = "Enter a valid odometer reading"
            return
        }
        viewModelScope.launch {
            runCatchingUi { graph.maintenanceRepository.markDone(id, odometerKm) }
                .onSuccess { _message.value = "Marked done at ${odometerKm.toLong()} km" }
                .onFailure { _message.value = it.userMessage() }
        }
    }

    fun updateIntervals(item: MaintenanceItem, intervalKm: Int?, intervalDays: Int?) {
        viewModelScope.launch {
            runCatchingUi {
                graph.maintenanceRepository.update(
                    item.copy(
                        intervalKm = intervalKm?.takeIf { it > 0 },
                        intervalDays = intervalDays?.takeIf { it > 0 },
                    ),
                )
            }.onFailure { _message.value = it.userMessage() }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MaintenanceViewModel(graph()) }
        }
    }
}
