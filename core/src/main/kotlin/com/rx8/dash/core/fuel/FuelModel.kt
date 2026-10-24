package com.rx8.dash.core.fuel

/**
 * Fuel-flow model configuration.
 *
 * MAF (PID 10) is air mass in g/s. Fuel mass = MAF / (stoich × λ) g/s; dividing by [densityGPerL] gives litres.
 * With the defaults 14.7 × 740 = 10878, so L/h = MAF × 3600 / (10878 × λ).
 *
 * [stagingK] maps rpm upper bounds (exclusive) to an enrichment multiplier k (≈ 1/λ) used when the PCM is in open loop
 * but does not report a usable commanded λ (PID 44). The last entry's k applies to every rpm above all bounds.
 */
data class FuelConfig(
    val densityGPerL: Double = DEFAULT_DENSITY_G_PER_L,
    val stoich: Double = DEFAULT_STOICH,
    val calibration: Double = 1.0,
    val sixPort: Boolean = true,
    val stagingK: List<Pair<Int, Double>> = DEFAULT_STAGING_K,
) {
    companion object {
        const val DEFAULT_DENSITY_G_PER_L: Double = 740.0
        const val DEFAULT_STOICH: Double = 14.7

        /** <3750 base, 3750–5500 SSV, 5500–7250 APV, >7250 VDI. */
        val DEFAULT_STAGING_K: List<Pair<Int, Double>> = listOf(
            3750 to 1.05,
            5500 to 1.12,
            7250 to 1.20,
            Int.MAX_VALUE to 1.28,
        )
    }
}

enum class FuelPath { CLOSED_LOOP, OPEN_LOOP_PID44, OPEN_LOOP_STAGING, NO_MAF }

data class FuelEstimate(
    val litresPerHour: Double,
    val lambdaUsed: Double,
    val path: FuelPath,
    val lowConfidence: Boolean,
)

class FuelModel(val config: FuelConfig = FuelConfig()) {

    /**
     * @param mafGs MAF in g/s (PID 10).
     * @param fuelStatus PID 03 byte A (fuel system 1 status).
     * @param commandedLambda PID 44 commanded equivalence ratio, or null when unsupported.
     * @param rpm engine speed, used for staging tiers and the confidence flag.
     */
    fun estimate(mafGs: Double?, fuelStatus: Int?, commandedLambda: Double?, rpm: Double?): FuelEstimate {
        val highRpm = rpm != null && rpm > LOW_CONFIDENCE_RPM
        if (mafGs == null || mafGs.isNaN() || mafGs.isInfinite() || mafGs < 0.0) {
            return FuelEstimate(0.0, 1.0, FuelPath.NO_MAF, lowConfidence = true)
        }

        val pid44Enriched = commandedLambda != null &&
            !commandedLambda.isNaN() &&
            commandedLambda >= MIN_PLAUSIBLE_LAMBDA &&
            commandedLambda < ENRICHMENT_THRESHOLD

        val status = fuelStatus ?: 0
        val closedLoop = (status and CLOSED_LOOP_BIT) != 0
        val openLoop = !closedLoop && (status and OPEN_LOOP_BITS) != 0

        return when {
            closedLoop -> FuelEstimate(flow(mafGs, 1.0), 1.0, FuelPath.CLOSED_LOOP, highRpm)
            openLoop && pid44Enriched -> {
                val lambda = commandedLambda!!
                FuelEstimate(flow(mafGs, lambda), lambda, FuelPath.OPEN_LOOP_PID44, highRpm)
            }
            openLoop -> {
                val k = stagingK(rpm)
                FuelEstimate(flow(mafGs, 1.0) * k, 1.0 / k, FuelPath.OPEN_LOOP_STAGING, lowConfidence = true)
            }
            // Status unknown / not reported (or closed loop with fault 0x10): trust an enriched PID 44, else stoich.
            pid44Enriched -> {
                val lambda = commandedLambda!!
                FuelEstimate(flow(mafGs, lambda), lambda, FuelPath.OPEN_LOOP_PID44, highRpm)
            }
            else -> FuelEstimate(flow(mafGs, 1.0), 1.0, FuelPath.CLOSED_LOOP, highRpm)
        }
    }

    /** L/h = MAF × 3600 / (stoich × density × λ) × calibration. */
    fun flow(mafGs: Double, lambda: Double): Double =
        mafGs * SECONDS_PER_HOUR / (config.stoich * config.densityGPerL * lambda) * config.calibration

    /** Enrichment multiplier for the rpm band; rpm null uses the lowest band. */
    fun stagingK(rpm: Double?): Double {
        val tiers = config.stagingK
        if (tiers.isEmpty()) return 1.0
        val r = rpm ?: 0.0
        for ((bound, k) in tiers) {
            if (r < bound.toDouble()) return k
        }
        return tiers.last().second
    }

    /** Instant economy; null below 3 km/h (show L/h instead). */
    fun litresPer100Km(lph: Double, speedKmh: Double?): Double? {
        if (speedKmh == null || speedKmh.isNaN() || speedKmh < MIN_ECONOMY_SPEED_KMH) return null
        if (lph.isNaN() || lph.isInfinite()) return null
        return lph / speedKmh * 100.0
    }

    companion object {
        const val CLOSED_LOOP_BIT: Int = 0x02
        const val OPEN_LOOP_BITS: Int = 0x01 or 0x04 or 0x08
        const val ENRICHMENT_THRESHOLD: Double = 0.98
        const val MIN_PLAUSIBLE_LAMBDA: Double = 0.5
        const val LOW_CONFIDENCE_RPM: Double = 5500.0
        const val MIN_ECONOMY_SPEED_KMH: Double = 3.0
        const val SECONDS_PER_HOUR: Double = 3600.0
    }
}
