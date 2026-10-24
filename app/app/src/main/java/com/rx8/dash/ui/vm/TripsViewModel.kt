package com.rx8.dash.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rx8.dash.AppGraph
import com.rx8.dash.data.BackupManager
import com.rx8.dash.data.FillUpEntity
import com.rx8.dash.data.SampleEntity
import com.rx8.dash.data.TripEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Chart-ready samples (downsampled) for one trip. */
data class TripSeries(
    val tripId: Long,
    val timesMs: List<Long>,
    val rpm: List<Double?>,
    val speed: List<Double?>,
    val coolant: List<Double?>,
)

class TripsViewModel(app: Application, private val graph: AppGraph) : ViewModel() {

    private val backup = BackupManager(app, graph.db)

    val trips: StateFlow<List<TripEntity>> =
        graph.tripRepository.trips.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val fillUps: StateFlow<List<FillUpEntity>> =
        graph.tripRepository.fillUps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedTripId = MutableStateFlow<Long?>(null)
    val selectedTripId: StateFlow<Long?> = _selectedTripId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val series: StateFlow<TripSeries?> = _selectedTripId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else graph.tripRepository.samplesForTrip(id).map { downsample(id, it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** CSV export target chosen before the SAF picker opens. */
    var pendingExportTripId: Long? = null

    fun select(tripId: Long?) {
        _selectedTripId.value = tripId
    }

    fun deleteTrip(tripId: Long) {
        viewModelScope.launch {
            runCatchingUi { graph.tripRepository.deleteTrip(tripId) }
                .onSuccess { if (_selectedTripId.value == tripId) _selectedTripId.value = null }
                .onFailure { _message.value = it.userMessage() }
        }
    }

    fun addFillUp(odometerKm: Double, litres: Double, fullTank: Boolean, notes: String) {
        if (!odometerKm.isFinite() || odometerKm <= 0.0 || !litres.isFinite() || litres <= 0.0) {
            _message.value = "Enter the odometer and litres"
            return
        }
        viewModelScope.launch {
            runCatchingUi {
                graph.tripRepository.addFillUp(
                    FillUpEntity(
                        timestampMs = System.currentTimeMillis(),
                        odometerKm = odometerKm,
                        litres = litres,
                        fullTank = fullTank,
                        notes = notes,
                    ),
                )
                graph.tripRepository.recomputeCalibration(graph.settings)
            }.onSuccess { factor ->
                _message.value = "Fill-up saved. Fuel calibration now ${"%.3f".format(java.util.Locale.US, factor)}"
            }.onFailure { _message.value = it.userMessage() }
        }
    }

    fun deleteFillUp(id: Long) {
        viewModelScope.launch {
            runCatchingUi {
                graph.tripRepository.deleteFillUp(id)
                graph.tripRepository.recomputeCalibration(graph.settings)
            }.onFailure { _message.value = it.userMessage() }
        }
    }

    fun exportCsv(uri: Uri) {
        val tripId = pendingExportTripId ?: return
        pendingExportTripId = null
        viewModelScope.launch {
            runCatchingUi { backup.exportTripCsv(tripId, uri) }
                .onSuccess { rows -> _message.value = "Exported $rows samples" }
                .onFailure { _message.value = "Export failed: " + it.userMessage() }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun downsample(tripId: Long, samples: List<SampleEntity>): TripSeries {
        val step = if (samples.size <= MAX_POINTS) 1 else (samples.size + MAX_POINTS - 1) / MAX_POINTS
        val picked = samples.filterIndexed { i, _ -> i % step == 0 }
        return TripSeries(
            tripId = tripId,
            timesMs = picked.map { it.timestamp },
            rpm = picked.map { it.rpm },
            speed = picked.map { it.speed },
            coolant = picked.map { it.coolant },
        )
    }

    companion object {
        const val MAX_POINTS: Int = 600

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TripsViewModel(app(), graph()) }
        }
    }
}
