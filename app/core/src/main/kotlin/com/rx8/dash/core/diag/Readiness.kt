package com.rx8.dash.core.diag

/** Monitor name to (supported, complete). Unsupported monitors are reported as (false, false). */
data class ReadinessStatus(
    val milOn: Boolean,
    val dtcCount: Int,
    val monitors: Map<String, Pair<Boolean, Boolean>>,
)

/** Decodes Mode 01 PID 01 (monitor status since DTCs cleared). */
object ReadinessDecoder {
    const val MISFIRE = "Misfire"
    const val FUEL_SYSTEM = "Fuel system"
    const val COMPONENTS = "Components"

    /** Spark-ignition monitors, in bit order of bytes C (supported) / D (incomplete). */
    val SPARK_MONITORS: List<String> = listOf(
        "Catalyst",
        "Heated catalyst",
        "Evaporative system",
        "Secondary air",
        "A/C refrigerant",
        "Oxygen sensor",
        "Oxygen sensor heater",
        "EGR/VVT",
    )

    /** Compression-ignition monitors (bit 4 and bit 2 reserved). Not expected on an RX-8; kept for completeness. */
    private val DIESEL_MONITORS: List<String?> = listOf(
        "NMHC catalyst",
        "NOx/SCR aftertreatment",
        null,
        "Boost pressure",
        null,
        "Exhaust gas sensor",
        "PM filter",
        "EGR/VVT",
    )

    /**
     * @param payload either the full response `41 01 A B C D` or just the four data bytes `A B C D`.
     * @return null when the payload is too short.
     */
    fun decode(payload: IntArray): ReadinessStatus? {
        val data: IntArray = if (payload.size >= 6 && (payload[0] and 0xFF) == 0x41 && (payload[1] and 0xFF) == 0x01) {
            payload.copyOfRange(2, 6)
        } else if (payload.size >= 4 && (payload[0] and 0xFF) != 0x41) {
            payload.copyOfRange(0, 4)
        } else {
            return null
        }
        val a = data[0] and 0xFF
        val b = data[1] and 0xFF
        val c = data[2] and 0xFF
        val d = data[3] and 0xFF

        val monitors = LinkedHashMap<String, Pair<Boolean, Boolean>>()
        putMonitor(monitors, MISFIRE, (b and 0x01) != 0, (b and 0x10) != 0)
        putMonitor(monitors, FUEL_SYSTEM, (b and 0x02) != 0, (b and 0x20) != 0)
        putMonitor(monitors, COMPONENTS, (b and 0x04) != 0, (b and 0x40) != 0)

        val compressionIgnition = (b and 0x08) != 0
        for (bit in 0 until 8) {
            val name: String = if (compressionIgnition) DIESEL_MONITORS[bit] ?: continue else SPARK_MONITORS[bit]
            val mask = 1 shl bit
            putMonitor(monitors, name, (c and mask) != 0, (d and mask) != 0)
        }

        return ReadinessStatus(
            milOn = (a and 0x80) != 0,
            dtcCount = a and 0x7F,
            monitors = monitors,
        )
    }

    private fun putMonitor(
        target: MutableMap<String, Pair<Boolean, Boolean>>,
        name: String,
        supported: Boolean,
        incomplete: Boolean,
    ) {
        target[name] = Pair(supported, supported && !incomplete)
    }
}

/** Decodes Mode 09 PID 02 (VIN). */
object VinDecoder {
    /**
     * @param payload reassembled response, e.g. `49 02 01 'J' 'M' ...`. Non-alphanumeric bytes (SID, PID, the CAN
     * item count and any padding) are dropped; the last 17 characters are the VIN.
     */
    fun decode(payload: IntArray): String? {
        if (payload.size < 2) return null
        val start = if ((payload[0] and 0xFF) == 0x49 && (payload[1] and 0xFF) == 0x02) 2 else 0
        val sb = StringBuilder()
        for (i in start until payload.size) {
            val v = payload[i] and 0xFF
            val ch = v.toChar()
            if ((ch in '0'..'9') || (ch in 'A'..'Z') || (ch in 'a'..'z')) sb.append(ch.uppercaseChar())
        }
        if (sb.length < VIN_LENGTH) return null
        return sb.substring(sb.length - VIN_LENGTH)
    }

    const val VIN_LENGTH: Int = 17
}
