package com.rx8.dash.core.fuel

data class FillUp(val odometerKm: Double, val litres: Double, val fullTank: Boolean)

/**
 * Derives the global fuel calibration factor from fill-up records.
 *
 * Between two consecutive full-tank fills, the litres actually pumped (every fill after the first full one, up to and
 * including the second full one, so partial top-ups in between count) are compared with the litres the app estimated
 * over the same stretch. The factor is total actual / total estimated across all complete segments, clamped to
 * [MIN_FACTOR]..[MAX_FACTOR]. It returns [DEFAULT_FACTOR] when there is not enough data.
 *
 * [appEstimatedLitres] is aligned with [fills]. Entry i is the uncalibrated (calibration = 1.0) app estimate for the
 * driving between fill i-1 and fill i, and entry 0 is ignored. A list one shorter than [fills] is also accepted, with
 * entry i covering fill i to fill i+1.
 */
object FuelCalibration {
    const val MIN_FACTOR: Double = 0.7
    const val MAX_FACTOR: Double = 1.5
    const val DEFAULT_FACTOR: Double = 1.0

    /** Below this many estimated litres in total, the ratio is too noisy to use. */
    const val MIN_ESTIMATED_LITRES: Double = 5.0

    fun fromFillUps(fills: List<FillUp>, appEstimatedLitres: List<Double>): Double {
        if (fills.size < 2) return DEFAULT_FACTOR
        val estimates: List<Double> = when (appEstimatedLitres.size) {
            fills.size -> appEstimatedLitres
            fills.size - 1 -> listOf(0.0) + appEstimatedLitres
            else -> return DEFAULT_FACTOR
        }

        // Keep fills and their estimates together while ordering by odometer.
        val ordered = fills.indices.sortedBy { fills[it].odometerKm }

        var totalActual = 0.0
        var totalEstimated = 0.0
        var segActual = 0.0
        var segEstimated = 0.0
        var seenFull = false

        for (idx in ordered) {
            val fill = fills[idx]
            val est = estimates[idx]
            if (!seenFull) {
                if (fill.fullTank) seenFull = true
                continue
            }
            if (fill.litres.isFinite() && fill.litres > 0.0) segActual += fill.litres
            if (est.isFinite() && est > 0.0) segEstimated += est
            if (fill.fullTank) {
                if (segActual > 0.0 && segEstimated > 0.0) {
                    totalActual += segActual
                    totalEstimated += segEstimated
                }
                segActual = 0.0
                segEstimated = 0.0
            }
        }

        if (totalEstimated < MIN_ESTIMATED_LITRES || totalActual <= 0.0) return DEFAULT_FACTOR
        return (totalActual / totalEstimated).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }
}
