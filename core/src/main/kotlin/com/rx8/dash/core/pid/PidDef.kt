package com.rx8.dash.core.pid

import com.rx8.dash.core.CanTarget
import com.rx8.dash.core.Metric

enum class Tier { FAST, MEDIUM, SLOW }

/**
 * One pollable value. [pid] is the 8-bit PID for Mode 01 or the 16-bit DID for Mode 22.
 * [decode] receives the data bytes after the SID + PID/DID echo, and is only called with at least
 * [responseBytes] bytes.
 */
data class PidDef(
    val metric: Metric,
    val mode: Int,
    val pid: Int,
    val target: CanTarget,
    val tier: Tier,
    val responseBytes: Int,
    val validated: Boolean,
    val decode: (IntArray) -> Double,
) {
    /** Length of the PID/DID echo in the response. */
    val pidLength: Int
        get() = if (mode == 0x22 || pid > 0xFF) 2 else 1

    fun command(): String =
        if (pidLength == 2) "%02X%04X".format(mode, pid) else "%02X%02X".format(mode, pid)

    /**
     * Extracts and decodes this PID from a full response payload (SID first). Returns null if the payload
     * is not a positive response to this exact PID or is too short.
     */
    fun decodeResponse(payload: IntArray): Double? {
        val header = 1 + pidLength
        if (payload.size < header + responseBytes) return null
        if (payload[0] != mode + 0x40) return null
        val echo = if (pidLength == 2) payload[1] * 256 + payload[2] else payload[1]
        if (echo != pid) return null
        val data = payload.copyOfRange(header, payload.size)
        val value = decode(data)
        return if (value.isNaN() || value.isInfinite()) null else value
    }
}
