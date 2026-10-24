package com.rx8.dash.core.elm

import com.rx8.dash.core.AdapterInfo
import com.rx8.dash.core.CanTarget
import com.rx8.dash.core.Clock
import com.rx8.dash.core.ElmErrorKind
import com.rx8.dash.core.ElmIo
import com.rx8.dash.core.ElmIoException
import com.rx8.dash.core.ElmResponse
import com.rx8.dash.core.ElmTimeoutException
import com.rx8.dash.core.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Strict request/response wrapper around an ELM327. Not thread-safe: callers must serialise access
 * (LiveTelemetrySource runs everything on one single-threaded dispatcher behind a Mutex).
 *
 * Caches the CAN header/filter pair so ATSH/ATCRA are only sent when the target changes. The cache is
 * dropped on anything that may have reset or confused the adapter (timeouts, IO errors, '?', bus errors,
 * STOPPED, ATZ, reconnect).
 *
 * Safety: [request] only accepts read-only diagnostic services. Mode 04 is reachable solely through
 * [clearDtcsSafely]. There is no API for write, coding, security-access or flash services.
 */
class Elm327Session(
    private val io: ElmIo,
    private val clock: Clock = SystemClock,
) {
    private var cachedTarget: CanTarget? = null

    /** Last confirmed header/filter pair; null when unknown. */
    val currentTarget: CanTarget?
        get() = cachedTarget

    /** Result of the last [initialize]; [AdapterInfo.multiPidAllowed] is updated by the poller's self-test. */
    var adapterInfo: AdapterInfo? = null
        private set

    /** Number of OBD requests sent through [request] (used for poll-rate measurement). */
    var requestCount: Long = 0L
        private set

    /** Wall time of the last successful exchange, from [clock]. */
    var lastExchangeMs: Long = 0L
        private set

    suspend fun initialize(): AdapterInfo {
        invalidateState()
        // ATZ reboots the chip; echo is back on until ATE0, which the raw reads tolerate.
        command("ATZ", RESET_TIMEOUT_MS)
        for (cmd in INIT_COMMANDS) {
            val reply = command(cmd, AT_TIMEOUT_MS)
            if (!reply.contains("OK")) {
                invalidateState()
                throw ElmIoException("Adapter rejected $cmd: ${reply.take(40)}")
            }
        }
        val identity = cleanText(command("ATI", AT_TIMEOUT_MS)).ifEmpty { "unknown" }
        val stiRaw = try {
            cleanText(command("STI", AT_TIMEOUT_MS))
        } catch (e: ElmTimeoutException) {
            // Some clones never answer unknown commands; the cache is already invalidated.
            ""
        }
        val stn = stiRaw.takeIf { it.uppercase().startsWith("STN") }
        val info = AdapterInfo(
            identity = identity,
            stnIdentity = stn,
            isGenuineStn = stn != null,
            multiPidAllowed = false, // only after the poller's self-test passes
        )
        adapterInfo = info
        return info
    }

    /** Records the multi-PID self-test outcome. Ignored unless the adapter is a genuine STN. */
    fun setMultiPidAllowed(allowed: Boolean) {
        val info = adapterInfo ?: return
        adapterInfo = info.copy(multiPidAllowed = allowed && info.isGenuineStn)
    }

    suspend fun setTarget(target: CanTarget) {
        if (cachedTarget == target) return
        cachedTarget = null
        val sh = command("ATSH${target.tx}", AT_TIMEOUT_MS)
        if (!sh.contains("OK")) {
            invalidateState()
            throw ElmIoException("ATSH${target.tx} rejected")
        }
        val cra = command("ATCRA${target.rx}", AT_TIMEOUT_MS)
        if (!cra.contains("OK")) {
            invalidateState()
            throw ElmIoException("ATCRA${target.rx} rejected")
        }
        cachedTarget = target
    }

    /**
     * Sends a read-only OBD/UDS request (e.g. "010C", "221177") and parses the reply.
     * @throws ElmTimeoutException / ElmIoException after invalidating the cached state.
     * @throws IllegalArgumentException for anything that is not an allowed read service.
     */
    suspend fun request(command: String, timeoutMs: Long = 1000): ElmResponse {
        val cmd = command.replace(" ", "").uppercase()
        require(isAllowedReadRequest(cmd)) { "Blocked non-read request: $cmd" }
        return exchange(cmd, timeoutMs)
    }

    fun invalidateState() {
        cachedTarget = null
    }

    /**
     * Mode 04 with maximum adapter timeout. Runs on the current target, forcing ENGINE (7E0/7E8) first if the
     * target is unknown or not the engine/functional address. Adapter timing is restored even on failure.
     */
    suspend fun clearDtcsSafely(): ElmResponse {
        val t = cachedTarget
        if (t != CanTarget.ENGINE && t != CanTarget.FUNCTIONAL) setTarget(CanTarget.ENGINE)
        try {
            command("ATAT0", AT_TIMEOUT_MS)
            command("ATSTFF", AT_TIMEOUT_MS)
            return exchange("04", CLEAR_TIMEOUT_MS)
        } finally {
            withContext(NonCancellable) {
                try {
                    command("ATST32", AT_TIMEOUT_MS)
                    command("ATAT1", AT_TIMEOUT_MS)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Adapter state unknown now; force full re-setup before the next request.
                    invalidateState()
                }
            }
        }
    }

    private suspend fun exchange(cmd: String, timeoutMs: Long): ElmResponse {
        val raw = command(cmd, timeoutMs)
        requestCount++
        val response = ElmParser.parse(raw, cmd)
        when (response) {
            is ElmResponse.Unsupported -> invalidateState()
            is ElmResponse.Error -> if (response.kind != ElmErrorKind.NEGATIVE_RESPONSE &&
                response.kind != ElmErrorKind.DATA_ERROR
            ) {
                invalidateState()
            }
            else -> Unit
        }
        return response
    }

    /** Raw write + read with uniform error handling. */
    private suspend fun command(cmd: String, timeoutMs: Long): String {
        try {
            io.write(cmd)
            val raw = io.readUntilPrompt(timeoutMs)
            lastExchangeMs = clock.nowMs()
            if (cmd.startsWith("AT") && raw.contains("?") && !cmd.startsWith("ATZ") && cmd != "ATI") {
                // An AT command the chip did not understand: state is suspect.
                invalidateState()
            }
            return raw
        } catch (e: CancellationException) {
            invalidateState()
            throw e
        } catch (e: ElmTimeoutException) {
            invalidateState()
            throw e
        } catch (e: ElmIoException) {
            invalidateState()
            throw e
        } catch (e: Exception) {
            invalidateState()
            throw ElmIoException("IO failure on $cmd: ${e.message}", e)
        }
    }

    private fun cleanText(raw: String): String =
        raw.replace(">", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("ATI", true) && !it.equals("STI", true) && it != "?" }
            .lastOrNull() ?: ""

    companion object {
        const val RESET_TIMEOUT_MS = 5000L
        const val AT_TIMEOUT_MS = 1000L
        const val CLEAR_TIMEOUT_MS = 10_000L

        val INIT_COMMANDS = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP6")

        /** Read-only services: OBD 01/02/03/06/07/09/0A, UDS/KWP reads 19/1A/21/22. Everything else is blocked. */
        private val READ_SERVICES = setOf("01", "02", "03", "06", "07", "09", "0A", "19", "1A", "21", "22")

        fun isAllowedReadRequest(cmd: String): Boolean {
            if (cmd.length < 2 || cmd.length % 2 != 0) return false
            if (!cmd.all { (it in '0'..'9') || (it in 'A'..'F') }) return false
            return cmd.substring(0, 2) in READ_SERVICES
        }
    }
}
