package com.rx8.dash.service

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rx8.dash.App
import com.rx8.dash.R
import com.rx8.dash.bt.BluetoothDevices
import com.rx8.dash.core.ConnectionState
import com.rx8.dash.core.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the OBD session (owned by [TelemetryHub]) alive while the screen is off, as a connectedDevice foreground
 * service. Also listens for head-unit wake (SCREEN_ON / USER_PRESENT) and Bluetooth on/off to trigger reconnects.
 * The UI does not bind; it observes the hub through AppGraph.
 */
class ObdForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeReceiver: WakeReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var statusJob: Job? = null
    private var inForeground = false

    private val graph get() = (application as App).graph
    private val hub: TelemetryHub get() = graph.telemetryHub

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutDown()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            // No Bluetooth permission (or foreground refused): run the hub in-process for demo mode / the
            // permission message, but do not keep a service around.
            hub.start()
            stopSelf()
            return START_NOT_STICKY
        }
        registerReceivers()
        hub.start()
        hub.reconnect()
        observeStatus()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceivers()
        statusJob?.cancel()
        scope.cancel()
        if (inForeground) hub.stopAsync()
        inForeground = false
        super.onDestroy()
    }

    /**
     * API 34+ throws SecurityException from startForeground for a connectedDevice service unless BLUETOOTH_CONNECT
     * is granted, so check it first. Background-start refusals (API 31+) are caught and turned into the
     * "tap to connect" notification.
     */
    private fun goForeground(): Boolean {
        if (inForeground) return true
        if (!BluetoothDevices.hasConnectPermission(this)) {
            Log.w(TAG, "BLUETOOTH_CONNECT not granted; not entering foreground")
            return false
        }
        return try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_SERVICE,
                Notifications.serviceNotification(this, getString(R.string.status_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            inForeground = true
            Notifications.cancelTapToConnect(this)
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is an IllegalStateException subclass.
            Log.w(TAG, "startForeground not allowed", e)
            Notifications.postTapToConnect(this)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground refused", e)
            false
        }
    }

    private fun shutDown() {
        hub.stopAsync()
        if (inForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    private fun registerReceivers() {
        if (wakeReceiver == null) {
            val r = WakeReceiver {
                hub.reconnect()
                try {
                    graph.mediaController.onWake()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Media watchdog on wake failed", e)
                }
            }
            ContextCompat.registerReceiver(this, r, WakeReceiver.filter(), ContextCompat.RECEIVER_NOT_EXPORTED)
            wakeReceiver = r
        }
        if (bluetoothReceiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_ON -> hub.setBluetoothEnabled(true)
                        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> hub.setBluetoothEnabled(false)
                    }
                }
            }
            ContextCompat.registerReceiver(
                this,
                r,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            bluetoothReceiver = r
        }
        hub.setBluetoothEnabled(BluetoothDevices(this).isEnabled())
    }

    private fun unregisterReceivers() {
        wakeReceiver?.let { safeUnregister(it) }
        wakeReceiver = null
        bluetoothReceiver?.let { safeUnregister(it) }
        bluetoothReceiver = null
    }

    private fun safeUnregister(r: BroadcastReceiver) {
        try {
            unregisterReceiver(r)
        } catch (e: IllegalArgumentException) {
            // Not registered.
        }
    }

    private fun observeStatus() {
        if (statusJob?.isActive == true) return
        statusJob = scope.launch {
            combine(hub.status, hub.demo) { status, demo -> statusText(status, demo) }
                .distinctUntilChanged()
                .collect { text ->
                    if (inForeground) {
                        Notifications.notifySafely(
                            this@ObdForegroundService,
                            Notifications.ID_SERVICE,
                            Notifications.serviceNotification(this@ObdForegroundService, text),
                        )
                    }
                }
        }
    }

    private fun statusText(status: ConnectionStatus, demo: Boolean): String {
        if (demo) return getString(R.string.status_demo)
        val label = when (status.state) {
            ConnectionState.DISCONNECTED -> getString(R.string.status_disconnected)
            ConnectionState.CONNECTING -> getString(R.string.status_connecting)
            ConnectionState.INITIALIZING -> getString(R.string.status_initializing)
            ConnectionState.CONNECTED -> getString(R.string.status_connected)
            ConnectionState.RECONNECT_WAIT -> getString(R.string.status_reconnecting)
            ConnectionState.ERROR -> getString(R.string.status_error)
        }
        return if (status.message.isBlank() || status.state == ConnectionState.CONNECTED) {
            label
        } else {
            "$label · ${status.message}"
        }
    }

    companion object {
        private const val TAG = "ObdService"
        const val ACTION_STOP = "com.rx8.dash.action.STOP_OBD"
    }
}
