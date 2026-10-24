package com.rx8.dash.core.elm

import com.rx8.dash.core.ElmErrorKind
import com.rx8.dash.core.ElmResponse

/**
 * Turns the raw text an ELM327 prints before its '>' prompt into an [ElmResponse].
 *
 * Assumes the session configuration ATH0 / CAF1 (headers off, CAN auto-formatting on), so single-frame
 * replies arrive with the PCI byte already removed, and ISO-TP multi-frame replies look like:
 * ```
 * 014
 * 0: 49 02 01 31 44 34
 * 1: 47 50 30 30 52 35 35
 * 2: 42 31 32 33 34 35 36
 * ```
 * Spaces are optional (ATS0). The returned payload starts with the response SID. With headers off the
 * payload key is always "".
 */
object ElmParser {

    private val NOISE_PREFIXES = listOf("SEARCHING...", "SEARCHING", "BUS INIT:", "BUS INIT", "...OK", "OK")

    private val ERROR_KEYWORDS: List<Pair<String, ElmErrorKind>> = listOf(
        "UNABLE TO CONNECT" to ElmErrorKind.UNABLE_TO_CONNECT,
        "BUS ERROR" to ElmErrorKind.BUS_ERROR,
        "BUS BUSY" to ElmErrorKind.BUS_ERROR,
        "CAN ERROR" to ElmErrorKind.CAN_ERROR,
        "DATA ERROR" to ElmErrorKind.DATA_ERROR,
        "RX ERROR" to ElmErrorKind.DATA_ERROR,
        "BUFFER FULL" to ElmErrorKind.BUFFER_FULL,
        "STOPPED" to ElmErrorKind.STOPPED,
        "FB ERROR" to ElmErrorKind.OTHER,
        "LV RESET" to ElmErrorKind.OTHER,
        "ACT ALERT" to ElmErrorKind.OTHER,
        "ERR" to ElmErrorKind.OTHER,
    )

    fun parse(raw: String): ElmResponse = parse(raw, null)

    /**
     * Same as [parse], but drops a line that equals [command] (adapter echo) when the command is known.
     * Lines starting with "AT"/"ST" are always treated as echo.
     */
    fun parse(raw: String, command: String?): ElmResponse {
        val echo = command?.let { normalizeHex(it) }
        val lines = ArrayList<String>()
        for (piece in raw.replace(">", "").split('\r', '\n')) {
            var line = piece.trim()
            if (line.isEmpty()) continue
            // Strip protocol-search noise that can share a line with data on some clones.
            var stripped = true
            while (stripped) {
                stripped = false
                for (p in NOISE_PREFIXES) {
                    if (line.uppercase().startsWith(p)) {
                        line = line.substring(p.length).trim()
                        stripped = true
                    }
                }
            }
            if (line.isEmpty()) continue
            val upper = line.uppercase()
            if (upper.startsWith("AT") || upper.startsWith("ST")) {
                // Echo of an adapter command. "STOPPED" is handled as an error, not echo.
                if (!upper.startsWith("STOPPED")) continue
            }
            if (echo != null && normalizeHex(upper) == echo) continue
            lines.add(upper)
        }

        if (lines.isEmpty()) return ElmResponse.NoData

        // Error keywords win over data: a line with <DATA ERROR means the bytes are untrustworthy.
        for (line in lines) {
            if (line.contains("NO DATA")) continue
            for ((keyword, kind) in ERROR_KEYWORDS) {
                if (line.contains(keyword)) return ElmResponse.Error(kind, raw.trim())
            }
        }

        val dataLines = lines.filter { it != "?" && !it.contains("NO DATA") }
        if (dataLines.isEmpty()) {
            return if (lines.any { it == "?" }) ElmResponse.Unsupported else ElmResponse.NoData
        }

        val payload = assemble(dataLines, echo) ?: return ElmResponse.Error(ElmErrorKind.OTHER, raw.trim())
        if (payload.isEmpty()) return ElmResponse.NoData
        if (payload[0] == 0x7F) return ElmResponse.Error(ElmErrorKind.NEGATIVE_RESPONSE, raw.trim())
        return ElmResponse.Data(mapOf("" to payload))
    }

    /** Builds one payload from the data lines; null when a line is not valid hex. */
    private fun assemble(lines: List<String>, echo: String?): IntArray? {
        val frameLines = lines.filter { FRAME_PREFIX.matches(it) }
        if (frameLines.isNotEmpty()) {
            return assembleMultiFrame(lines)
        }
        // Single-frame replies. Drop "response pending" (7F xx 78) and any line that still looks like an
        // echoed request (request SIDs are < 0x40, responses are >= 0x40) when real data follows.
        val parsed = ArrayList<IntArray>()
        for (line in lines) {
            val bytes = hexBytes(line) ?: return null
            parsed.add(bytes)
        }
        val useful = parsed.filter { bytes ->
            !(bytes.size >= 3 && bytes[0] == 0x7F && bytes[2] == 0x78)
        }.filter { bytes ->
            !(bytes.isNotEmpty() && bytes[0] < 0x40 && echo == null && parsed.size > 1)
        }
        if (useful.isEmpty()) {
            // Only a "response pending" with nothing after it: treat as no answer.
            return IntArray(0)
        }
        // With CRA filtering one ECU answers; if several single-frame lines arrive, the first response wins.
        val firstPositive = useful.firstOrNull { it.isNotEmpty() && it[0] >= 0x40 } ?: useful.first()
        return firstPositive
    }

    private fun assembleMultiFrame(lines: List<String>): IntArray? {
        var declaredLength = -1
        val bytes = ArrayList<Int>()
        var started = false
        for (line in lines) {
            val m = FRAME_PREFIX.matchEntire(line)
            if (m != null) {
                started = true
                val frameBytes = hexBytes(m.groupValues[2]) ?: return null
                for (b in frameBytes) bytes.add(b)
            } else if (!started && BYTE_COUNT.matches(line.replace(" ", ""))) {
                declaredLength = line.replace(" ", "").toInt(16)
            } else if (!started) {
                // Echo or other preamble before the first frame; ignore if it is hex, fail otherwise.
                if (hexBytes(line) == null) return null
            } else {
                return null
            }
        }
        val count = if (declaredLength in 1..bytes.size) declaredLength else bytes.size
        return IntArray(count) { bytes[it] }
    }

    private val FRAME_PREFIX = Regex("^([0-9A-F])\\s*:\\s*(.*)$")
    private val BYTE_COUNT = Regex("^[0-9A-F]{3}$")

    /** Parses "41 0C 1A F8" or "410C1AF8" to bytes; null if not hex or odd length. */
    internal fun hexBytes(text: String): IntArray? {
        val compact = normalizeHex(text)
        if (compact.isEmpty() || compact.length % 2 != 0) return null
        for (c in compact) {
            if (!((c in '0'..'9') || (c in 'A'..'F'))) return null
        }
        return IntArray(compact.length / 2) { i -> compact.substring(i * 2, i * 2 + 2).toInt(16) }
    }

    private fun normalizeHex(text: String): String = text.replace(" ", "").uppercase()
}
