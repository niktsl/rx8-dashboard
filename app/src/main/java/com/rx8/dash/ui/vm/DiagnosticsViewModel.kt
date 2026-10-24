package com.rx8.dash.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rx8.dash.AppGraph
import com.rx8.dash.core.CanTarget
import com.rx8.dash.core.Metric
import com.rx8.dash.core.diag.ClearResult
import com.rx8.dash.core.diag.Diagnostics
import com.rx8.dash.core.diag.Dtc
import com.rx8.dash.core.diag.ReadinessStatus
import com.rx8.dash.data.DtcHistoryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScanRow(val did: Int, val target: CanTarget, val bytes: IntArray?)

data class DiagUi(
    val busy: Boolean = false,
    val busyLabel: String = "",
    val stored: List<Dtc>? = null,
    val pending: List<Dtc>? = null,
    val permanent: List<Dtc>? = null,
    val readiness: ReadinessStatus? = null,
    val vin: String? = null,
    val vinRead: Boolean = false,
    val freezeFrame: Map<Metric, Double>? = null,
    val scan: List<ScanRow> = emptyList(),
    val message: String? = null,
)

/**
 * Read-only diagnostics plus the guarded clear. Every call goes through [AppGraph.diagnostics], which is non-null only
 * while a live session exists (never in demo mode).
 */
class DiagnosticsViewModel(private val graph: AppGraph) : ViewModel() {

    private val _ui = MutableStateFlow(DiagUi())
    val ui: StateFlow<DiagUi> = _ui.asStateFlow()

    fun isAvailable(): Boolean = graph.diagnostics() != null

    private fun launchDiag(label: String, block: suspend (Diagnostics) -> Unit) {
        if (_ui.value.busy) return
        val diag = graph.diagnostics()
        if (diag == null) {
            _ui.update { it.copy(message = "Diagnostics need a live adapter connection (not available in demo mode).") }
            return
        }
        _ui.update { it.copy(busy = true, busyLabel = label, message = null) }
        viewModelScope.launch {
            runCatchingUi { block(diag) }
                .onFailure { e -> _ui.update { it.copy(message = "$label failed: " + e.userMessage()) } }
            _ui.update { it.copy(busy = false, busyLabel = "") }
        }
    }

    fun readCodes() = launchDiag("Reading codes") { d ->
        val stored = d.readStored()
        val pending = d.readPending()
        val permanent = d.readPermanent()
        val readiness = d.readiness()
        _ui.update { it.copy(stored = stored, pending = pending, permanent = permanent, readiness = readiness) }
        val now = System.currentTimeMillis()
        val history = stored.map { DtcHistoryEntity(timestampMs = now, code = it.code, description = it.description, kind = DtcHistoryEntity.KIND_STORED) } +
            pending.map { DtcHistoryEntity(timestampMs = now, code = it.code, description = it.description, kind = DtcHistoryEntity.KIND_PENDING) } +
            permanent.map { DtcHistoryEntity(timestampMs = now, code = it.code, description = it.description, kind = DtcHistoryEntity.KIND_PERMANENT) }
        if (history.isNotEmpty()) graph.db.dtcHistoryDao().insertAll(history)
    }

    fun readVin() = launchDiag("Reading VIN") { d ->
        val vin = d.vin()
        _ui.update { it.copy(vin = vin, vinRead = true) }
    }

    fun readFreezeFrame() = launchDiag("Reading freeze frame") { d ->
        val ff = d.freezeFrame()
        _ui.update { it.copy(freezeFrame = ff) }
    }

    /** Called only after the UI's two-step confirmation. */
    fun clearCodes(rpmNow: Double?) = launchDiag("Clearing codes") { d ->
        when (val r = d.clear(rpmNow)) {
            ClearResult.Cleared -> {
                graph.db.dtcHistoryDao().markAllCleared()
                _ui.update {
                    it.copy(
                        stored = emptyList(),
                        pending = emptyList(),
                        message = "Codes cleared. Readiness monitors are reset and need a few drive cycles to complete.",
                    )
                }
            }
            ClearResult.RefusedEngineRunning -> _ui.update {
                it.copy(message = "Refused: the engine is running. Switch the engine OFF with the ignition ON, then try again.")
            }
            is ClearResult.Failed -> _ui.update { it.copy(message = "Clear failed: " + r.reason) }
        }
    }

    fun scan(dids: List<Int>, target: CanTarget) = launchDiag("Scanning") { d ->
        val result = d.scanExtended(dids, target)
        _ui.update { it.copy(scan = result.map { (did, bytes) -> ScanRow(did, target, bytes) }) }
    }

    fun clearMessage() {
        _ui.update { it.copy(message = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DiagnosticsViewModel(graph()) }
        }
    }
}
