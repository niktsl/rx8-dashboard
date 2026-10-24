package com.rx8.dash.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rx8.dash.AppGraph
import com.rx8.dash.core.Alert
import com.rx8.dash.core.ConnectionStatus
import com.rx8.dash.core.TelemetrySnapshot
import com.rx8.dash.core.trip.AccelRun
import com.rx8.dash.core.trip.AccelTimer
import com.rx8.dash.data.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Activity-scoped state shared by every screen: settings, live telemetry, connection status, alerts, and the
 * acceleration timer (fed from telemetry while the app is open).
 */
class SessionViewModel(val graph: AppGraph) : ViewModel() {

    /** Null until DataStore has delivered the first value. */
    val settings: StateFlow<AppSettings?> =
        graph.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val telemetry: StateFlow<TelemetrySnapshot> = graph.telemetryHub.telemetry
    val status: StateFlow<ConnectionStatus> = graph.telemetryHub.status
    val alerts: StateFlow<List<Alert>> = graph.telemetryHub.alerts

    private val accelTimer = AccelTimer()
    val accelRuns: StateFlow<List<AccelRun>> = accelTimer.results

    init {
        viewModelScope.launch {
            telemetry.collect { s -> if (s.timestampMs > 0L) accelTimer.add(s) }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { graph.settings.update(transform) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { SessionViewModel(graph()) }
        }
    }
}
