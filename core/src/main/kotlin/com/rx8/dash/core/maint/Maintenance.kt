package com.rx8.dash.core.maint

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

data class MaintenanceItem(
    val id: String,
    val title: String,
    val intervalKm: Int?,
    val intervalDays: Int?,
    val lastKm: Double?,
    val lastDateMs: Long?,
    val notes: String = "",
)

object MaintenanceDefaults {
    const val OIL_FILTER = "oil_filter"
    const val IGNITION_COILS = "ignition_coils"
    const val SPARK_PLUGS = "spark_plugs"
    const val COOLANT = "coolant"
    const val AIR_FILTER = "air_filter"
    const val OIL_LEVEL = "oil_level"
    const val PREMIX_OMP = "premix_omp"

    val items: List<MaintenanceItem> = listOf(
        MaintenanceItem(OIL_FILTER, "Engine oil + filter", 5_000, null, null, null, "Rotaries consume oil by design; use the recommended grade."),
        MaintenanceItem(IGNITION_COILS, "Ignition coils", 40_000, null, null, null, "Weak coils cause misfires and flooding."),
        MaintenanceItem(SPARK_PLUGS, "Spark plugs", 30_000, null, null, null, "Leading and trailing plugs."),
        MaintenanceItem(COOLANT, "Coolant", 40_000, 730, null, null, "Every 2 years or 40,000 km, whichever comes first."),
        MaintenanceItem(AIR_FILTER, "Air filter", 20_000, null, null, null),
        MaintenanceItem(OIL_LEVEL, "Oil level check", 1_000, null, null, null, "Check on level ground, engine warm, after a few minutes."),
        MaintenanceItem(PREMIX_OMP, "Premix / OMP check", 5_000, null, null, null, "Check the oil metering pump and premix routine."),
    )
}

enum class DueState { OK, SOON, DUE, OVERDUE }

/**
 * Evaluates one item against the odometer and clock. Kilometres and days are evaluated independently and the worse
 * result wins.
 *
 * For each dimension, the "soon" window is the larger of 10 % of the interval and 500 km / 14 days.
 * - remaining > window: OK
 * - 0 < remaining <= window: SOON
 * - overshoot < window: DUE
 * - otherwise: OVERDUE
 *
 * An item with an interval but no recorded service is DUE.
 */
object MaintenanceEvaluator {
    const val SOON_FRACTION: Double = 0.10
    const val SOON_MIN_KM: Double = 500.0
    const val SOON_MIN_DAYS: Double = 14.0
    private const val MS_PER_DAY: Double = 86_400_000.0

    fun evaluate(item: MaintenanceItem, odometerKm: Double, nowMs: Long): Pair<DueState, String> {
        val hasKm = item.intervalKm != null && item.intervalKm > 0
        val hasDays = item.intervalDays != null && item.intervalDays > 0
        if (!hasKm && !hasDays) return Pair(DueState.OK, "No interval set")

        val results = ArrayList<Pair<DueState, String>>(2)

        val intervalKm = item.intervalKm
        if (intervalKm != null && intervalKm > 0) {
            val lastKm = item.lastKm
            if (lastKm == null) {
                results.add(Pair(DueState.DUE, "Not recorded yet"))
            } else {
                val remaining = lastKm + intervalKm - odometerKm
                val window = max(intervalKm * SOON_FRACTION, SOON_MIN_KM)
                results.add(Pair(stateFor(remaining, window), describe(remaining, "km")))
            }
        }

        val intervalDays = item.intervalDays
        if (intervalDays != null && intervalDays > 0) {
            val lastDate = item.lastDateMs
            if (lastDate == null) {
                results.add(Pair(DueState.DUE, "Not recorded yet"))
            } else {
                val elapsedDays = (nowMs - lastDate) / MS_PER_DAY
                val remaining = intervalDays - elapsedDays
                val window = max(intervalDays * SOON_FRACTION, SOON_MIN_DAYS)
                results.add(Pair(stateFor(remaining, window), describe(remaining, "days")))
            }
        }

        // Worst state wins; on a tie, the first (km) message is kept.
        var worst = results[0]
        for (r in results) {
            if (r.first.ordinal > worst.first.ordinal) worst = r
        }
        return worst
    }

    private fun stateFor(remaining: Double, window: Double): DueState = when {
        remaining > window -> DueState.OK
        remaining > 0.0 -> DueState.SOON
        -remaining < window -> DueState.DUE
        else -> DueState.OVERDUE
    }

    private fun describe(remaining: Double, unit: String): String {
        val n = abs(remaining).roundToLong()
        return when {
            n == 0L -> "due now"
            remaining > 0.0 -> "in $n ${unitFor(n, unit)}"
            else -> "$n ${unitFor(n, unit)} overdue"
        }
    }

    private fun unitFor(n: Long, unit: String): String = if (unit == "days" && n == 1L) "day" else unit
}
