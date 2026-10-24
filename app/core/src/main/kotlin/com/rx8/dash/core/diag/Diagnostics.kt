package com.rx8.dash.core.diag

import com.rx8.dash.core.CanTarget
import com.rx8.dash.core.ElmErrorKind
import com.rx8.dash.core.ElmIoException
import com.rx8.dash.core.ElmResponse
import com.rx8.dash.core.ElmTimeoutException
import com.rx8.dash.core.Metric
import com.rx8.dash.core.elm.Elm327Session
import com.rx8.dash.core.poll.SessionAccess
import kotlin.coroutines.cancellation.CancellationException

sealed interface ClearResult {
    data object Cleared : ClearResult
    data object RefusedEngineRunning : ClearResult
    data class Failed(val reason: String) : ClearResult
}

/**
 * Read-only diagnostics plus the guarded Mode 04 clear. Every call runs exclusively through [SessionAccess.withSession],
 * which pauses polling.
 *
 * Requests go to the engine ECU (physical 7E0). When it does not answer, the request is retried once on the functional
 * address (7DF). Timeouts and IO errors from the session propagate from the read functions so the UI can tell
 * "no codes" apart from "no answer"; [clear] reports them as [ClearResult.Failed].
 */
class Diagnostics(private val access: SessionAccess) {

    suspend fun readStored(): List<Dtc> = readDtcs("03", 0x43)

    suspend fun readPending(): List<Dtc> = readDtcs("07", 0x47)

    /** Mode 0A. ECUs that predate permanent DTCs answer NO DATA or a negative response, which gives an empty list. */
    suspend fun readPermanent(): List<Dtc> = readDtcs("0A", 0x4A, true)

    suspend fun readiness(): ReadinessStatus = access.withSession<ReadinessStatus> { s ->
        val resp = query(s, "0101", DEFAULT_TIMEOUT_MS)
        if (resp !is ElmResponse.Data &&
            !(resp is ElmResponse.Error && resp.kind == ElmErrorKind.NEGATIVE_RESPONSE)
        ) {
            throw ElmIoException("No response from ECU")
        }
        val payload = payloadsOf(resp).firstOrNull { it.size >= 6 && it[0] == 0x41 && it[1] == 0x01 }
        payload?.let { ReadinessDecoder.decode(it) } ?: ReadinessStatus(false, 0, emptyMap())
    }

    /** Mode 09 PID 02. Null when the ECU does not report a VIN. */
    suspend fun vin(): String? = access.withSession<String?> { s ->
        val resp = query(s, "0902", VIN_TIMEOUT_MS)
        payloadsOf(resp)
            .filter { it.size >= 2 && it[0] == 0x49 && it[1] == 0x02 }
            .firstNotNullOfOrNull { VinDecoder.decode(it) }
    }

    /**
     * Mode 02, frame 00, for the key PIDs. Returns an empty map when no freeze frame is stored (the frame's DTC,
     * PID 02, is 0000 or unanswered).
     */
    suspend fun freezeFrame(): Map<Metric, Double> = access.withSession<Map<Metric, Double>> { s ->
        val result = LinkedHashMap<Metric, Double>()
        val trigger = query(s, "020200", DEFAULT_TIMEOUT_MS)
        val triggerData = payloadsOf(trigger).firstOrNull { it.size >= 5 && it[0] == 0x42 && it[1] == 0x02 }
        if (triggerData == null || (triggerData[3] == 0 && triggerData[4] == 0)) {
            return@withSession result
        }
        // query() may have fallen back to the functional address; keep using whichever answered.
        val target = s.currentTarget ?: CanTarget.ENGINE
        for (pid in FreezeFramePids.ALL) {
            val cmd = "02" + hex2(pid.pid) + "00"
            val resp: ElmResponse = try {
                s.setTarget(target)
                s.request(cmd, DEFAULT_TIMEOUT_MS)
            } catch (e: ElmTimeoutException) {
                continue
            }
            val data = payloadsOf(resp).firstOrNull { it.size >= 3 + pid.bytes && it[0] == 0x42 && it[1] == pid.pid }
                ?: continue
            val bytes = data.copyOfRange(3, 3 + pid.bytes)
            result[pid.metric] = pid.decode(bytes)
        }
        result
    }

    /**
     * Mode 04 via [Elm327Session.clearDtcsSafely]. Refuses when [rpmNow] is above 0, or when a fresh RPM read inside the
     * exclusive session shows the engine running. The UI must already have taken the two-step confirmation.
     */
    suspend fun clear(rpmNow: Double?): ClearResult {
        if (rpmNow != null && rpmNow > 0.0) return ClearResult.RefusedEngineRunning
        return try {
            access.withSession<ClearResult> { s ->
                val rpmResp = query(s, "010C", DEFAULT_TIMEOUT_MS)
                val rpmData = payloadsOf(rpmResp).firstOrNull { it.size >= 4 && it[0] == 0x41 && it[1] == 0x0C }
                if (rpmData != null) {
                    val rpm = ((rpmData[2] and 0xFF) * 256 + (rpmData[3] and 0xFF)) / 4.0
                    if (rpm > 0.0) return@withSession ClearResult.RefusedEngineRunning
                }
                s.setTarget(CanTarget.ENGINE)
                when (val resp = s.clearDtcsSafely()) {
                    is ElmResponse.Data ->
                        if (resp.payloads.values.any { it.isNotEmpty() && it[0] == 0x44 }) {
                            ClearResult.Cleared
                        } else {
                            ClearResult.Failed("Unexpected response to clear request")
                        }
                    is ElmResponse.Error ->
                        if (resp.kind == ElmErrorKind.NEGATIVE_RESPONSE) {
                            ClearResult.Failed("ECU refused the clear request (conditions not correct). Ignition ON, engine OFF.")
                        } else {
                            ClearResult.Failed("Adapter error: " + resp.kind.name)
                        }
                    ElmResponse.NoData -> ClearResult.Failed("No response from the ECU")
                    ElmResponse.Unsupported -> ClearResult.Failed("Adapter rejected the clear command")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ClearResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Read-only Mode 22 scan. Each value holds the data bytes after `62 DIDhi DIDlo`, or null when the DID is not
     * answered or the request times out. IO errors (connection lost) propagate.
     */
    suspend fun scanExtended(dids: List<Int>, target: CanTarget): Map<Int, IntArray?> = access.withSession<Map<Int, IntArray?>> { s ->
        val out = LinkedHashMap<Int, IntArray?>()
        s.setTarget(target)
        for (did in dids) {
            if (did < 0 || did > 0xFFFF) {
                out[did] = null
                continue
            }
            val cmd = "22" + hex2(did shr 8) + hex2(did and 0xFF)
            val resp: ElmResponse? = try {
                s.setTarget(target)
                s.request(cmd, DEFAULT_TIMEOUT_MS)
            } catch (e: ElmTimeoutException) {
                null
            }
            val data = if (resp == null) {
                null
            } else {
                payloadsOf(resp).firstOrNull {
                    it.size >= 3 && it[0] == 0x62 && it[1] == (did shr 8) && it[2] == (did and 0xFF)
                }
            }
            out[did] = data?.copyOfRange(3, data.size)
        }
        out
    }

    private suspend fun readDtcs(command: String, sid: Int, allowNoData: Boolean = false): List<Dtc> = access.withSession<List<Dtc>> { s ->
        val resp = query(s, command, DEFAULT_TIMEOUT_MS)
        if (resp !is ElmResponse.Data) {
            if (allowNoData && (resp is ElmResponse.NoData ||
                    (resp is ElmResponse.Error && resp.kind == ElmErrorKind.NEGATIVE_RESPONSE))
            ) {
                return@withSession emptyList<Dtc>()
            }
            throw ElmIoException("No response from ECU")
        }
        val seen = LinkedHashMap<String, Dtc>()
        for (p in payloadsOf(resp)) {
            if (p.isEmpty() || p[0] != sid) continue
            for (dtc in DtcDecoder.decode(p)) {
                if (!seen.containsKey(dtc.code)) seen[dtc.code] = dtc
            }
        }
        seen.values.toList()
    }

    /** Physical request to the engine ECU, falling back once to the functional address when nothing answers. */
    private suspend fun query(s: Elm327Session, command: String, timeoutMs: Long): ElmResponse {
        s.setTarget(CanTarget.ENGINE)
        val first = s.request(command, timeoutMs)
        if (first !is ElmResponse.NoData) return first
        s.setTarget(CanTarget.FUNCTIONAL)
        return s.request(command, timeoutMs)
    }

    private fun payloadsOf(resp: ElmResponse): List<IntArray> = when (resp) {
        is ElmResponse.Data -> resp.payloads.values.map { arr -> IntArray(arr.size) { i -> arr[i] and 0xFF } }
        else -> emptyList()
    }

    private fun hex2(v: Int): String {
        val digits = "0123456789ABCDEF"
        val b = v and 0xFF
        return "" + digits[b shr 4] + digits[b and 0x0F]
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 1000L
        const val VIN_TIMEOUT_MS: Long = 3000L
    }
}

/** Mode 02 freeze-frame PIDs and their SAE J1979 decoding. */
internal object FreezeFramePids {
    class Entry(val pid: Int, val metric: Metric, val bytes: Int, val decode: (IntArray) -> Double)

    val ALL: List<Entry> = listOf(
        Entry(0x03, Metric.FUEL_SYSTEM_STATUS, 1) { b -> b[0].toDouble() },
        Entry(0x04, Metric.LOAD, 1) { b -> b[0] * 100.0 / 255.0 },
        Entry(0x05, Metric.COOLANT, 1) { b -> b[0] - 40.0 },
        Entry(0x06, Metric.STFT, 1) { b -> (b[0] - 128) * 100.0 / 128.0 },
        Entry(0x07, Metric.LTFT, 1) { b -> (b[0] - 128) * 100.0 / 128.0 },
        Entry(0x0C, Metric.RPM, 2) { b -> (b[0] * 256 + b[1]) / 4.0 },
        Entry(0x0D, Metric.SPEED, 1) { b -> b[0].toDouble() },
        Entry(0x0E, Metric.TIMING_ADVANCE, 1) { b -> b[0] / 2.0 - 64.0 },
        Entry(0x0F, Metric.INTAKE_AIR, 1) { b -> b[0] - 40.0 },
        Entry(0x10, Metric.MAF, 2) { b -> (b[0] * 256 + b[1]) / 100.0 },
        Entry(0x11, Metric.THROTTLE, 1) { b -> b[0] * 100.0 / 255.0 },
    )
}
