package com.rx8.dash.service

import android.content.Context
import android.util.Log
import com.rx8.dash.bt.BluetoothDevices
import com.rx8.dash.bt.RfcommElmIo
import com.rx8.dash.core.Alert
import com.rx8.dash.core.AlertLevel
import com.rx8.dash.core.Clock
import com.rx8.dash.core.ConnectionState
import com.rx8.dash.core.ConnectionStatus
import com.rx8.dash.core.ElmIo
import com.rx8.dash.core.ElmIoException
import com.rx8.dash.core.Metric
import com.rx8.dash.core.SystemClock
import com.rx8.dash.core.TelemetrySnapshot
import com.rx8.dash.core.TelemetrySource
import com.rx8.dash.core.elm.BackoffSchedule
import com.rx8.dash.core.fuel.FuelConfig
import com.rx8.dash.core.fuel.FuelModel
import com.rx8.dash.core.health.RotaryHealthMonitor
import com.rx8.dash.core.health.StartLog
import com.rx8.dash.core.poll.LiveTelemetrySource
import com.rx8.dash.core.poll.PollerConfig
import com.rx8.dash.core.poll.SessionAccess
import com.rx8.dash.core.sim.Simulator
import com.rx8.dash.core.trip.TripAccumulator
import com.rx8.dash.data.AppSettings
import com.rx8.dash.data.SettingsRepository
import com.rx8.dash.data.StartLogEntity
import com.rx8.dash.data.TripRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-wide telemetry owner (one instance, held by AppGraph).
 *
 * Holds the active [TelemetrySource] (live ELM327 or [Simulator]), chosen from settings (demoMode / deviceAddress),
 * Bluetooth state and permission. Mirrors its flows into stable [telemetry]/[status] flows, evaluates
 * [RotaryHealthMonitor] into [alerts], and records trips through [TripRepository].
 *
 * Lifecycle: [start] when the foreground service (or the Activity, for demo without Bluetooth permission) starts;
 * [stop] when it ends. Both are idempotent.
 */
class TelemetryHub(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val clock: Clock = SystemClock,
) {
    private val appContext: Context = context.applicationContext
    private val bluetooth = BluetoothDevices(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _telemetry = MutableStateFlow(TelemetrySnapshot.EMPTY)
    val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    private val _status = MutableStateFlow(ConnectionStatus(ConnectionState.DISCONNECTED, "Not started"))
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    private val _running = MutableStateFlow(false)
    /** True between [start] and [stop]. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _demo = MutableStateFlow(false)
    /** The active source is the simulator. */
    val demo: StateFlow<Boolean> = _demo.asStateFlow()

    private val _activeTripId = MutableStateFlow<Long?>(null)
    /** Id of the trip being recorded, or null. */
    val activeTripId: StateFlow<Long?> = _activeTripId.asStateFlow()

    @Volatile
    private var healthMonitor: RotaryHealthMonitor = RotaryHealthMonitor()

    /** Current rotary health rules (warm-up stage etc.), following the redline setting. */
    val health: RotaryHealthMonitor get() = healthMonitor

    @Volatile
    private var fuelModel: FuelModel = FuelModel()

    @Volatile
    private var source: TelemetrySource? = null

    /**
     * The live adapter session for Diagnostics, or null unless a live adapter is CONNECTED.
     * Callers still have to handle IllegalStateException if the link drops mid-call.
     */
    val sessionAccess: SessionAccess?
        get() {
            val s = source
            return if (s is LiveTelemetrySource && _status.value.state == ConnectionState.CONNECTED) s else null
        }

    private val bluetoothOn = MutableStateFlow(bluetooth.isEnabled())
    private val reconnectGeneration = MutableStateFlow(0)

    private val switchLock = Mutex()
    private var controlJob: Job? = null
    private var mirrorJobs: List<Job> = emptyList()
    private var currentKey: SourceKey? = null

    // ---- Trip recording state (guarded by tripLock) ----
    private val tripLock = Mutex()
    private val snapshots = Channel<TelemetrySnapshot>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var trip: ActiveTrip? = null
    private var floodAlert: Alert? = null

    private class ActiveTrip(
        val id: Long,
        val accumulator: TripAccumulator,
        val calibration: Double,
        var lastEngineMs: Long,
        var lastSampleMs: Long = 0L,
        var engineWasRunning: Boolean = true,
        var startCoolantC: Double? = null,
    )

    /** Everything that requires rebuilding the source when it changes. */
    private data class SourceKey(
        val demo: Boolean,
        val address: String?,
        val bluetoothOn: Boolean,
        val permission: Boolean,
        val fuel: FuelConfig,
        val poller: PollerConfig,
        val initialDelayMs: Long,
        val generation: Int,
    )

    init {
        scope.launch { processSnapshots() }
        scope.launch { idleWatchdog() }
        scope.launch { refreshFloodRisk() }
    }

    /** Starts following settings and producing telemetry. Safe to call repeatedly. */
    fun start() {
        synchronized(this) {
            if (controlJob?.isActive == true) return
            _running.value = true
            bluetoothOn.value = bluetooth.isEnabled()
            controlJob = scope.launch {
                combine(settingsRepository.settings, bluetoothOn, reconnectGeneration) { s, bt, gen ->
                    Triple(s, bt, gen)
                }.collect { (s, bt, gen) -> applySettings(s, bt, gen) }
            }
        }
    }

    /** Stops the source and closes any open trip. */
    suspend fun stop() {
        val job = synchronized(this) {
            val j = controlJob
            controlJob = null
            _running.value = false
            j
        }
        job?.cancel()
        withContext(NonCancellable) {
            switchLock.withLock {
                stopSourceLocked()
                currentKey = null
            }
            finishTrip()
            _status.value = ConnectionStatus(ConnectionState.DISCONNECTED, "Stopped")
        }
    }

    /** Fire-and-forget [stop], for Service.onDestroy. */
    fun stopAsync() {
        scope.launch { stop() }
    }

    /** Called by the service's Bluetooth state receiver. */
    fun setBluetoothEnabled(enabled: Boolean) {
        bluetoothOn.value = enabled
    }

    /**
     * Head-unit wake / user request: if a live source is not connected (or could not be created, e.g. permission
     * was just granted) rebuild it. The new source waits [AppSettings.initialConnectDelayMs] before connecting.
     */
    fun reconnect() {
        bluetoothOn.value = bluetooth.isEnabled()
        if (!_running.value || _demo.value) return
        val state = _status.value.state
        if (state == ConnectionState.CONNECTED || state == ConnectionState.INITIALIZING) return
        reconnectGeneration.value = reconnectGeneration.value + 1
    }

    // ---- Source switching ----

    private suspend fun applySettings(s: AppSettings, bt: Boolean, gen: Int) {
        healthMonitor = RotaryHealthMonitor(s.toHealthConfig())
        val demo = s.demoMode
        // In demo mode Bluetooth state, permission and reconnects are irrelevant: keep them constant in the key so
        // they do not restart the simulator.
        val key = SourceKey(
            demo = demo,
            address = s.deviceAddress,
            bluetoothOn = demo || bt,
            permission = demo || bluetooth.hasConnectPermission(),
            fuel = s.toFuelConfig(),
            poller = if (demo) PollerConfig() else s.toPollerConfig(),
            initialDelayMs = if (demo) 0L else s.initialConnectDelayMs.coerceIn(0L, MAX_INITIAL_DELAY_MS),
            generation = if (demo) 0 else gen,
        )
        switchLock.withLock {
            val old = currentKey
            if (old == key) return
            // Leaving demo or changing adapter ends the current trip; a plain reconnect keeps it open.
            val endsTrip = old != null && (old.demo != key.demo || old.address != key.address)
            stopSourceLocked()
            currentKey = key
            if (endsTrip) finishTrip()
            startSourceLocked(key)
        }
    }

    private suspend fun startSourceLocked(key: SourceKey) {
        val fuel = FuelModel(key.fuel)
        fuelModel = fuel
        _demo.value = key.demo
        val newSource: TelemetrySource = when {
            key.demo -> Simulator(clock, scope, fuel)
            key.address.isNullOrBlank() -> {
                _status.value = ConnectionStatus(ConnectionState.DISCONNECTED, "No adapter selected")
                return
            }
            !key.permission -> {
                _status.value = ConnectionStatus(ConnectionState.ERROR, "Bluetooth permission not granted")
                return
            }
            !key.bluetoothOn -> {
                _status.value = ConnectionStatus(ConnectionState.DISCONNECTED, "Waiting for Bluetooth to turn on")
                return
            }
            else -> {
                val address: String = key.address ?: return
                LiveTelemetrySource(
                    ioFactory = { openIo(address) },
                    clock = clock,
                    config = key.poller,
                    backoff = BackoffSchedule(initialDelayMs = key.initialDelayMs),
                    fuel = fuel,
                    scope = scope,
                )
            }
        }
        source = newSource
        mirrorJobs = listOf(
            scope.launch {
                newSource.status.collect { _status.value = it }
            },
            scope.launch {
                newSource.telemetry.collect { snap -> onSnapshot(snap) }
            },
        )
        newSource.start()
    }

    private suspend fun stopSourceLocked() {
        val old = source
        source = null
        mirrorJobs.forEach { it.cancel() }
        mirrorJobs = emptyList()
        if (old != null) {
            try {
                old.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Source stop failed", e)
            }
        }
        _demo.value = false
        _telemetry.value = TelemetrySnapshot.EMPTY
        _alerts.value = listOfNotNull(floodAlert)
        _status.value = ConnectionStatus(ConnectionState.DISCONNECTED)
    }

    private suspend fun openIo(address: String): ElmIo {
        val adapter = bluetooth.adapter() ?: throw ElmIoException("Bluetooth not available")
        if (!bluetooth.isEnabled()) throw ElmIoException("Bluetooth is off")
        if (!bluetooth.hasConnectPermission()) throw ElmIoException("Bluetooth permission not granted")
        val device = bluetooth.remoteDevice(address) ?: throw ElmIoException("Invalid adapter address")
        val io = RfcommElmIo(device, adapter)
        io.connect()
        return io
    }

    private fun onSnapshot(snap: TelemetrySnapshot) {
        _telemetry.value = snap
        val health = if (snap.timestampMs > 0L) healthMonitor.evaluate(snap) else emptyList()
        val flood = floodAlert
        _alerts.value = if (flood != null) health + flood else health
        if (snap.timestampMs > 0L) snapshots.trySend(snap)
    }

    // ---- Trip recording ----

    private suspend fun processSnapshots() {
        for (snap in snapshots) {
            try {
                handleTripSnapshot(snap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Trip recording failed", e)
            }
        }
    }

    private suspend fun handleTripSnapshot(snap: TelemetrySnapshot) {
        val rpm = snap[Metric.RPM]
        val connected = _status.value.state == ConnectionState.CONNECTED
        val engineRunning = connected && rpm != null && rpm.isFinite() && rpm > 0.0
        tripLock.withLock {
            var t = trip
            if (t == null) {
                if (!engineRunning) return
                val calibration = fuelModel.config.calibration
                val id = tripRepository.startTrip(snap.timestampMs, calibration)
                t = ActiveTrip(
                    id = id,
                    accumulator = TripAccumulator(fuelModel),
                    calibration = calibration,
                    lastEngineMs = clock.nowMs(),
                    startCoolantC = snap[Metric.COOLANT]?.takeIf { it.isFinite() },
                )
                trip = t
                _activeTripId.value = id
            }
            // Integrate while running plus the first engine-off snapshot, so idle-off time does not stretch the trip.
            if (engineRunning || t.engineWasRunning) t.accumulator.add(snap)
            if (engineRunning) {
                t.lastEngineMs = clock.nowMs()
                if (snap.timestampMs - t.lastSampleMs >= SAMPLE_INTERVAL_MS) {
                    t.lastSampleMs = snap.timestampMs
                    tripRepository.recordSample(t.id, snap)
                }
            }
            t.engineWasRunning = engineRunning
        }
    }

    private suspend fun idleWatchdog() {
        while (currentCoroutineContext().isActive) {
            delay(IDLE_CHECK_MS)
            try {
                val t = tripLock.withLock { trip }
                if (t != null) {
                    val connected = _status.value.state == ConnectionState.CONNECTED
                    val rpm = _telemetry.value[Metric.RPM]
                    val running = connected && rpm != null && rpm > 0.0
                    if (!running && clock.nowMs() - t.lastEngineMs >= ENGINE_OFF_FINISH_MS) finishTrip()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Idle check failed", e)
            }
        }
    }

    /** Closes the active trip (if any): flushes samples, writes the summary and the start-log entry. */
    private suspend fun finishTrip() {
        tripLock.withLock {
            val t = trip ?: return
            trip = null
            _activeTripId.value = null
            try {
                val summary = t.accumulator.summary()
                tripRepository.finishTrip(t.id, summary)
                val durationMin = (summary.endMs - summary.startMs).coerceAtLeast(0L) / 60_000.0
                val shortCold = StartLog(healthMonitor.config).isShortColdTrip(durationMin, summary.maxCoolantC)
                tripRepository.logStart(
                    StartLogEntity(
                        timestampMs = summary.startMs,
                        tripId = t.id,
                        startCoolantC = t.startCoolantC,
                        maxCoolantC = summary.maxCoolantC,
                        durationMin = durationMin,
                        shortColdTrip = shortCold,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Finishing trip ${t.id} failed", e)
            }
        }
        refreshFloodRisk()
    }

    private suspend fun refreshFloodRisk() {
        val alert = try {
            val streak = tripRepository.recentShortColdStreak()
            when (StartLog(healthMonitor.config).floodRisk(streak)) {
                AlertLevel.WARNING -> Alert(
                    FLOOD_ALERT_ID, AlertLevel.WARNING, "Flood risk",
                    "$streak short cold trips in a row. Drive until fully warm (82 °C) before the next shutdown.",
                )
                AlertLevel.CAUTION -> Alert(
                    FLOOD_ALERT_ID, AlertLevel.CAUTION, "Short cold trips",
                    "$streak short cold trips in a row. Let the engine reach 82 °C before switching off.",
                )
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Flood risk check failed", e)
            null
        }
        floodAlert = alert
        val others = _alerts.value.filter { it.id != FLOOD_ALERT_ID }
        _alerts.value = if (alert != null) others + alert else others
    }

    companion object {
        private const val TAG = "TelemetryHub"
        const val FLOOD_ALERT_ID = "flood_risk"

        /** Engine off (or adapter disconnected) this long ends the trip. */
        const val ENGINE_OFF_FINISH_MS: Long = 3 * 60_000L
        private const val IDLE_CHECK_MS: Long = 5_000L
        /** Sample rows are stored at most at 1 Hz; the trip integrator sees every snapshot. */
        const val SAMPLE_INTERVAL_MS: Long = 1_000L
        private const val MAX_INITIAL_DELAY_MS: Long = 60_000L
    }
}
