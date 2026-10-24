package com.rx8.dash.core

import kotlinx.coroutines.flow.StateFlow

/*
 * Shared contracts between :core and :app. Every module codes against these types.
 * Change only with a matching update to every implementer.
 */

/** Raw byte pipe to an ELM327. Android implements it over RFCOMM; tests use a fake. */
interface ElmIo {
    /** Writes [command] followed by '\r'. */
    suspend fun write(command: String)

    /** Reads until the '>' prompt and returns everything before it (raw, unparsed). Throws [ElmTimeoutException]. */
    suspend fun readUntilPrompt(timeoutMs: Long): String

    fun close()
}

class ElmTimeoutException(message: String) : Exception(message)
class ElmIoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Parsed reply to one OBD request. */
sealed interface ElmResponse {
    /** Payload bytes per responding ECU, with headers/PCI stripped and multi-frame reassembled. Key = rx address or "" when headers are off. */
    data class Data(val payloads: Map<String, IntArray>) : ElmResponse {
        val first: IntArray get() = payloads.values.first()
    }
    data object NoData : ElmResponse
    data object Unsupported : ElmResponse // "?" from the adapter
    data class Error(val kind: ElmErrorKind, val raw: String) : ElmResponse
}

enum class ElmErrorKind { BUS_ERROR, CAN_ERROR, DATA_ERROR, BUFFER_FULL, STOPPED, UNABLE_TO_CONNECT, NEGATIVE_RESPONSE, OTHER }

/** CAN transmit header and receive filter, always set together. */
data class CanTarget(val tx: String, val rx: String) {
    companion object {
        val ENGINE = CanTarget("7E0", "7E8")
        val FUNCTIONAL = CanTarget("7DF", "7E8")
        val TPMS = CanTarget("751", "759")
    }
}

data class AdapterInfo(
    val identity: String,        // ATI reply, e.g. "ELM327 v1.5"
    val stnIdentity: String?,    // STI reply when an STN chip answers, else null
    val isGenuineStn: Boolean,
    val multiPidAllowed: Boolean,
)

enum class ConnectionState { DISCONNECTED, CONNECTING, INITIALIZING, CONNECTED, RECONNECT_WAIT, ERROR }

data class ConnectionStatus(
    val state: ConnectionState,
    val message: String = "",
    val adapter: AdapterInfo? = null,
    val pollHz: Double = 0.0,
    val nextRetryInMs: Long? = null,
)

/** Keys of everything the dashboard can show. */
enum class Metric(val label: String, val unit: String, val min: Double, val max: Double) {
    RPM("RPM", "rpm", 0.0, 10000.0),
    SPEED("Speed", "km/h", 0.0, 260.0),
    THROTTLE("Throttle", "%", 0.0, 100.0),
    COOLANT("Coolant", "°C", -40.0, 130.0),
    INTAKE_AIR("Intake air", "°C", -40.0, 90.0),
    LOAD("Load", "%", 0.0, 100.0),
    STFT("Short trim", "%", -25.0, 25.0),
    LTFT("Long trim", "%", -25.0, 25.0),
    MAF("MAF", "g/s", 0.0, 300.0),
    VOLTAGE("Battery", "V", 10.0, 16.0),
    FUEL_SYSTEM_STATUS("Fuel loop", "", 0.0, 255.0),
    COMMANDED_LAMBDA("Cmd λ", "λ", 0.5, 1.5),
    WIDEBAND_LAMBDA("λ (O2)", "λ", 0.5, 1.5),
    TIMING_ADVANCE("Timing", "°", -20.0, 50.0),
    FUEL_FLOW("Fuel flow", "L/h", 0.0, 60.0),
    ECONOMY("Economy", "L/100km", 0.0, 40.0),
    OIL_PRESSURE_OK("Oil pressure", "", 0.0, 1.0),     // switch: 1 = OK, 0 = LOW (extended, unvalidated)
    OIL_TEMP_AUX("Oil temp (aux)", "°C", 0.0, 150.0),  // aftermarket sensor only
    TPMS_FL("Tyre FL", "psi", 0.0, 50.0),
    TPMS_FR("Tyre FR", "psi", 0.0, 50.0),
    TPMS_RL("Tyre RL", "psi", 0.0, 50.0),
    TPMS_RR("Tyre RR", "psi", 0.0, 50.0),
}

/** One poll cycle's view of the car. Missing key = not supported or not yet read. */
data class TelemetrySnapshot(
    val timestampMs: Long,
    val values: Map<Metric, Double>,
    val supported: Set<Metric>,
    val lowConfidence: Set<Metric> = emptySet(),
) {
    operator fun get(m: Metric): Double? = values[m]

    companion object {
        val EMPTY = TelemetrySnapshot(0L, emptyMap(), emptySet())
    }
}

/** Anything that produces live telemetry: the real poller or the simulator. */
interface TelemetrySource {
    val telemetry: StateFlow<TelemetrySnapshot>
    val status: StateFlow<ConnectionStatus>
    suspend fun start()
    suspend fun stop()
}

/** Monotonic + wall clock abstraction so schedulers and backoff are testable. */
interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** Default redlines; the user can override the value in Settings. */
enum class Rx8Series(val label: String, val redlineRpm: Int, val sixPort: Boolean) {
    S1_6PORT("Series 1 · 6-port high power (manual 6-speed)", 9000, true),
    S1_4PORT("Series 1 · 4-port standard power", 7500, false),
    S2_MANUAL("Series 2 · manual", 9000, true),
    S2_AUTO("Series 2 · automatic", 7500, false),
}

enum class AlertLevel { INFO, CAUTION, WARNING }

data class Alert(val id: String, val level: AlertLevel, val title: String, val message: String)
