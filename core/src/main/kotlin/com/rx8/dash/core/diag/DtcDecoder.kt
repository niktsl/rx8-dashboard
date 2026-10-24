package com.rx8.dash.core.diag

data class Dtc(val code: String, val description: String?)

/**
 * Decodes Mode 03 / 07 / 0A responses, including the response SID (0x43 / 0x47 / 0x4A).
 *
 * On CAN (ISO 15765) the byte after the SID is the DTC count, so the payload length is even (SID + count + 2n).
 * Legacy protocols have no count byte and pad to three DTCs per frame (SID + 2n, odd length). Pairs equal to 0000 are
 * padding and are ignored.
 */
object DtcDecoder {
    val RESPONSE_SIDS: Set<Int> = setOf(0x43, 0x47, 0x4A)

    private val LETTERS = charArrayOf('P', 'C', 'B', 'U')
    private const val HEX = "0123456789ABCDEF"

    fun decode(payload: IntArray): List<Dtc> {
        if (payload.isEmpty()) return emptyList()
        var start = 0
        var maxCodes = Int.MAX_VALUE
        if (payload[0] in RESPONSE_SIDS) {
            start = 1
            val rest = payload.size - 1
            if (rest % 2 == 1) {
                // CAN: count byte, then pairs.
                maxCodes = payload[1] and 0xFF
                start = 2
            }
        }

        val out = ArrayList<Dtc>()
        val seen = HashSet<String>()
        var i = start
        while (i + 1 < payload.size && out.size < maxCodes) {
            val a = payload[i] and 0xFF
            val b = payload[i + 1] and 0xFF
            i += 2
            if (a == 0 && b == 0) continue
            val code = codeFor(a, b)
            if (seen.add(code)) out.add(Dtc(code, DtcDescriptions.describe(code)))
        }
        return out
    }

    /** Two raw bytes to a code like "P0133". */
    fun codeFor(a: Int, b: Int): String {
        val sb = StringBuilder(5)
        sb.append(LETTERS[(a shr 6) and 0x03])
        sb.append(HEX[(a shr 4) and 0x03])
        sb.append(HEX[a and 0x0F])
        sb.append(HEX[(b shr 4) and 0x0F])
        sb.append(HEX[b and 0x0F])
        return sb.toString()
    }
}
