package com.rx8.dash.core.pid

/**
 * Decodes Mode 01 supported-PID bitmaps (PIDs 0x00, 0x20, 0x40, 0x60).
 * Map key = bitmap PID; value = the four data bytes A..D. A full response payload ("41 xx A B C D") is
 * also accepted.
 */
object SupportedPids {
    val BITMAP_PIDS: List<Int> = listOf(0x00, 0x20, 0x40, 0x60)

    fun decode(bitmapResponses: Map<Int, IntArray>): Set<Int> {
        val result = HashSet<Int>()
        for ((base, raw) in bitmapResponses) {
            val bytes = if (raw.size >= 6 && raw[0] == 0x41 && raw[1] == base) raw.copyOfRange(2, raw.size) else raw
            val n = minOf(4, bytes.size)
            for (i in 0 until n) {
                for (bit in 0 until 8) {
                    if ((bytes[i] and (0x80 shr bit)) != 0) {
                        result.add(base + i * 8 + bit + 1)
                    }
                }
            }
        }
        return result
    }
}
