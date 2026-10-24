package com.rx8.dash.core.pid

import com.rx8.dash.core.CanTarget
import com.rx8.dash.core.Metric

/** Read-only Mode 22 DID offered in the extended scanner but not mapped to a dashboard [Metric]. */
data class ScannerDid(
    val did: Int,
    val target: CanTarget,
    val label: String,
    val unit: String,
    val validated: Boolean,
    val decode: (IntArray) -> Double,
)

object PidRegistry {

    /** Placeholder DID for definitions whose real DID is not known yet; the poller never sends it. */
    const val UNKNOWN_DID: Int = 0x0000

    private fun ab(d: IntArray): Int = d[0] * 256 + d[1]

    /** Mode 01 PIDs. The ECU target is resolved at runtime (physical 7E0, or functional 7DF fallback). */
    val standard: List<PidDef> = listOf(
        PidDef(Metric.RPM, 0x01, 0x0C, CanTarget.ENGINE, Tier.FAST, 2, true) { d -> ab(d) / 4.0 },
        PidDef(Metric.SPEED, 0x01, 0x0D, CanTarget.ENGINE, Tier.FAST, 1, true) { d -> d[0].toDouble() },
        PidDef(Metric.THROTTLE, 0x01, 0x11, CanTarget.ENGINE, Tier.FAST, 1, true) { d -> d[0] * 100.0 / 255.0 },
        PidDef(Metric.COOLANT, 0x01, 0x05, CanTarget.ENGINE, Tier.MEDIUM, 1, true) { d -> d[0] - 40.0 },
        PidDef(Metric.INTAKE_AIR, 0x01, 0x0F, CanTarget.ENGINE, Tier.MEDIUM, 1, true) { d -> d[0] - 40.0 },
        PidDef(Metric.LOAD, 0x01, 0x04, CanTarget.ENGINE, Tier.MEDIUM, 1, true) { d -> d[0] * 100.0 / 255.0 },
        PidDef(Metric.STFT, 0x01, 0x06, CanTarget.ENGINE, Tier.MEDIUM, 1, true) { d -> (d[0] - 128) * 100.0 / 128.0 },
        PidDef(Metric.LTFT, 0x01, 0x07, CanTarget.ENGINE, Tier.MEDIUM, 1, true) { d -> (d[0] - 128) * 100.0 / 128.0 },
        PidDef(Metric.MAF, 0x01, 0x10, CanTarget.ENGINE, Tier.MEDIUM, 2, true) { d -> ab(d) / 100.0 },
        PidDef(Metric.VOLTAGE, 0x01, 0x42, CanTarget.ENGINE, Tier.MEDIUM, 2, true) { d -> ab(d) / 1000.0 },
        // Fuel system 1 status byte (A), raw: 1/4/8 open loop, 2 closed loop, 16 closed loop with fault.
        PidDef(Metric.FUEL_SYSTEM_STATUS, 0x01, 0x03, CanTarget.ENGINE, Tier.MEDIUM, 2, true) { d -> d[0].toDouble() },
        PidDef(Metric.COMMANDED_LAMBDA, 0x01, 0x44, CanTarget.ENGINE, Tier.MEDIUM, 2, true) { d -> ab(d) * 2.0 / 65536.0 },
        // Wideband O2 sensor 1: 0x24 (voltage type) preferred, 0x34 (current type) as alternative. Lambda = AB*2/65536.
        PidDef(Metric.WIDEBAND_LAMBDA, 0x01, 0x24, CanTarget.ENGINE, Tier.MEDIUM, 4, true) { d -> ab(d) * 2.0 / 65536.0 },
        PidDef(Metric.WIDEBAND_LAMBDA, 0x01, 0x34, CanTarget.ENGINE, Tier.MEDIUM, 4, true) { d -> ab(d) * 2.0 / 65536.0 },
        PidDef(Metric.TIMING_ADVANCE, 0x01, 0x0E, CanTarget.ENGINE, Tier.MEDIUM, 1, true) { d -> d[0] / 2.0 - 64.0 },
    )

    private fun tpmsPsi(d: IntArray): Double = (d[0] * 1373.0 / 1000.0) * 0.145037738

    /**
     * Mazda Mode 22 definitions. Opt-in, read-only, probe-and-validate: everything here is unvalidated until
     * confirmed on a real RX-8.
     */
    val extended: List<PidDef> = listOf(
        // TPMS module on 751/759. kPa = A * 1.373, converted to psi. Unvalidated.
        PidDef(Metric.TPMS_FL, 0x22, 0xC901, CanTarget.TPMS, Tier.SLOW, 1, false) { d -> tpmsPsi(d) },
        PidDef(Metric.TPMS_FR, 0x22, 0xC902, CanTarget.TPMS, Tier.SLOW, 1, false) { d -> tpmsPsi(d) },
        PidDef(Metric.TPMS_RL, 0x22, 0xC903, CanTarget.TPMS, Tier.SLOW, 1, false) { d -> tpmsPsi(d) },
        PidDef(Metric.TPMS_RR, 0x22, 0xC904, CanTarget.TPMS, Tier.SLOW, 1, false) { d -> tpmsPsi(d) },
        // Oil pressure switch: the PCM DID is NOT known. Placeholder only (UNKNOWN_DID is never polled).
        // Expected decode once found: non-zero = pressure OK (1.0), zero = LOW (0.0).
        PidDef(Metric.OIL_PRESSURE_OK, 0x22, UNKNOWN_DID, CanTarget.ENGINE, Tier.SLOW, 1, false) { d ->
            if (d[0] != 0) 1.0 else 0.0
        },
    )

    /** Scanner-only DIDs (no dashboard metric). */
    val scannerDids: List<ScannerDid> = listOf(
        // MAF sensor voltage, reported working by RX8Club users.
        ScannerDid(0x1177, CanTarget.ENGINE, "MAF voltage", "V", true) { d -> ab(d) / 3700.0 },
    )

    fun forMetric(m: Metric): PidDef? =
        standard.firstOrNull { it.metric == m } ?: extended.firstOrNull { it.metric == m }

    /** True when [def] can actually be sent (has a real DID). */
    fun isPollable(def: PidDef): Boolean = !(def.mode == 0x22 && def.pid == UNKNOWN_DID)
}
