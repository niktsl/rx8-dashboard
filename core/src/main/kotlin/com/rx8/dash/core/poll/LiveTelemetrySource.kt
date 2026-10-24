package com.rx8.dash.core.poll

import com.rx8.dash.core.Clock
import com.rx8.dash.core.ConnectionState
import com.rx8.dash.core.ConnectionStatus
import com.rx8.dash.core.ElmIo
import com.rx8.dash.core.TelemetrySnapshot
import com.rx8.dash.core.TelemetrySource
import com.rx8.dash.core.elm.BackoffSchedule
import com.rx8.dash.core.elm.Elm327Session
import com.rx8.dash.core.fuel.FuelModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Real adapter telemetry. Connect -> initialize -> prepare -> poll loop, all on one single-threaded
 * dispatcher so the ELM327 stays strictly request/response and nothing touches the main thread.
 * Reconnects with [backoff] after any failure. [withSession] borrows the session between poll cycles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTelemetrySource(
    private val ioFactory: suspend () -> ElmIo,
    private val clock: Clock,
    private val config: PollerConfig,
    private val backoff: BackoffSchedule,
    private val fuel: FuelModel,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : TelemetrySource, SessionAccess {

    private val _telemetry = MutableStateFlow(TelemetrySnapshot.EMPTY)
    override val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    private val _status = MutableStateFlow(ConnectionStatus(ConnectionState.DISCONNECTED))
    override val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val sessionLock = Mutex()
    private var job: Job? = null

    // Only touched on [dispatcher] (or under [sessionLock]).
    private var io: ElmIo? = null
    private var session: Elm327Session? = null

    override suspend fun start() {
        if (job?.isActive == true) return
        job = scope.launch(dispatcher) { runLoop() }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        withContext(dispatcher + NonCancellable) { closeIo() }
        _status.value = ConnectionStatus(ConnectionState.DISCONNECTED)
        _telemetry.value = TelemetrySnapshot.EMPTY
    }

    override suspend fun <T> withSession(block: suspend (Elm327Session) -> T): T =
        withContext(dispatcher) {
            sessionLock.withLock {
                val s = session ?: throw IllegalStateException("Adapter not connected")
                block(s)
            }
        }

    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val wait = backoff.delayForAttempt(attempt)
            _status.value = ConnectionStatus(
                state = if (attempt == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECT_WAIT,
                message = if (attempt == 0) "Waiting for adapter" else "Reconnecting (attempt $attempt)",
                nextRetryInMs = wait,
            )
            delay(wait)
            var connected = false
            try {
                _status.value = ConnectionStatus(ConnectionState.CONNECTING, "Connecting")
                val newIo = ioFactory()
                io = newIo
                _status.value = ConnectionStatus(ConnectionState.INITIALIZING, "Initialising adapter")
                val newSession = Elm327Session(newIo, clock)
                val poller = Poller(newSession, clock, config, fuel)
                sessionLock.withLock {
                    newSession.initialize()
                    poller.prepare()
                    session = newSession
                }
                connected = true
                pollLoop(newSession, poller)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _telemetry.value = TelemetrySnapshot.EMPTY
                _status.value = ConnectionStatus(ConnectionState.ERROR, e.message ?: e.javaClass.simpleName)
                // A drop after a good session restarts at the first backoff step; connect failures keep growing.
                attempt = if (connected) 1 else attempt + 1
            } finally {
                withContext(NonCancellable) {
                    sessionLock.withLock { session = null }
                    closeIo()
                }
            }
        }
    }

    private suspend fun pollLoop(s: Elm327Session, poller: Poller) {
        var windowStartMs = clock.nowMs()
        var windowStartCount = s.requestCount
        var hz = 0.0
        _status.value = ConnectionStatus(ConnectionState.CONNECTED, "Connected", s.adapterInfo, 0.0)
        while (currentCoroutineContext().isActive) {
            val snapshot = sessionLock.withLock { poller.cycle() }
            _telemetry.value = snapshot
            val now = clock.nowMs()
            val elapsed = now - windowStartMs
            if (elapsed >= HZ_WINDOW_MS) {
                hz = (s.requestCount - windowStartCount) * 1000.0 / elapsed
                windowStartMs = now
                windowStartCount = s.requestCount
            }
            val message = if (snapshot.values.isEmpty()) "Connected - no ECU data (ignition off?)" else "Connected"
            _status.value = ConnectionStatus(ConnectionState.CONNECTED, message, s.adapterInfo, hz)
            yield() // let withSession() callers queue on the mutex
        }
    }

    private fun closeIo() {
        val current = io ?: return
        io = null
        try {
            current.close()
        } catch (e: Exception) {
            // Already closed or broken; nothing else to do.
        }
    }

    companion object {
        const val HZ_WINDOW_MS = 2000L
    }
}
