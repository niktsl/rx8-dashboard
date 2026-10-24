package com.rx8.dash.ui.vm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rx8.dash.AppGraph
import com.rx8.dash.data.AppSettings
import com.rx8.dash.data.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BondedDevice(val name: String, val address: String)

class SettingsViewModel(private val app: Application, private val graph: AppGraph) : ViewModel() {

    private val backup = BackupManager(app, graph.db)

    private val _devices = MutableStateFlow<List<BondedDevice>>(emptyList())
    val devices: StateFlow<List<BondedDevice>> = _devices.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            runCatchingUi { graph.settings.update(transform) }.onFailure { _message.value = it.userMessage() }
        }
    }

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Lists bonded (paired) devices. No discovery: the ELM327 must be paired in Android settings first. */
    @SuppressLint("MissingPermission")
    fun refreshDevices() {
        if (!hasConnectPermission()) {
            _devices.value = emptyList()
            _message.value = "Grant the Nearby devices (Bluetooth) permission to list paired adapters."
            return
        }
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val adapter = manager?.adapter
                    adapter?.bondedDevices
                        ?.map { d -> BondedDevice(d.name ?: "Unnamed device", d.address) }
                        ?.sortedBy { it.name.lowercase() }
                        ?: emptyList()
                } catch (e: SecurityException) {
                    emptyList()
                }
            }
            _devices.value = list
            if (list.isEmpty()) _message.value = "No paired Bluetooth devices. Pair the OBD adapter in Android Bluetooth settings."
        }
    }

    fun exportBackup(uri: Uri) {
        _busy.value = true
        viewModelScope.launch {
            runCatchingUi { backup.exportJson(uri) }
                .onSuccess { _message.value = "Backup exported" }
                .onFailure { _message.value = "Export failed: " + it.userMessage() }
            _busy.value = false
        }
    }

    fun importBackup(uri: Uri) {
        _busy.value = true
        viewModelScope.launch {
            runCatchingUi { backup.importJson(uri) }
                .onSuccess { r ->
                    _message.value = "Imported ${r.trips} trips, ${r.samples} samples, ${r.maintenance} service items, ${r.fillUps} fill-ups"
                }
                .onFailure { _message.value = "Import failed (nothing changed): " + it.userMessage() }
            _busy.value = false
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(app(), graph()) }
        }
    }
}
