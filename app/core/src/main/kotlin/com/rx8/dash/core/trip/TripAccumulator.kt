package com.rx8.dash.core.trip

import com.rx8.dash.core.Metric
import com.rx8.dash.core.TelemetrySnapshot
import com.rx8.dash.core.fuel.FuelModel

data class TripSummary(
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Double,
    val fuelLitres: Double,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val maxRpm: Double,
    val maxCoolantC: Double?,
    /** null when the trip is too short (< 0.1 km) or no fuel data was available. */
    val avgLPer100: Double?,
    val coldStart: Boolean,
)

/**
 * Integrates distance (trapezoidal on speed) and fuel (trapezoidal on L/h) between snapshots.
 * Gaps longer than [maxGapMs] (dropouts, reconnects) are not integrated across.
 * Fuel uses the snapshot's FUEL_FLOW when present, otherwise [fuel] is evaluated from MAF / PID 03 / PID 44 / rpm.
 */
class TripAccumulator(
    private val fuel: FuelModel,
    private val maxGapMs: Long = DEFAULT_MAX_GAP_MS,
    private val coldStartBelowC: Double = DEFAULT_COLD_START_C,
) {
    private var startMs: Long = 0L
    private var endMs: Long = 0L
    private var distanceKm = 0.0
    private var fuelLitres = 0.0
    private var integratedHours = 0.0
    private var maxSpeed = 0.0
    private var maxRpm = 0.0
    private var maxCoolant: Double? = null
    private var coldStart: Boolean? = null
    private var hasFuelData = false

    private var lastMs: Long? = null
    private var lastSpeed: Double? = null
    private var lastLph: Double? = null

    @Synchronized
    fun add(s: TelemetrySnapshot) {
        val t = s.timestampMs
        val prevMs = lastMs
        if (prevMs != null && t <= prevMs) return // out-of-order or duplicate

        if (prevMs == null) startMs = t
        endMs = t

        val speed = s[Metric.SPEED]?.takeIf { it.isFinite() && it >= 0.0 }
        val rpm = s[Metric.RPM]?.takeIf { it.isFinite() }
        val coolant = s[Metric.COOLANT]?.takeIf { it.isFinite() }
        val lph = currentLph(s)

        if (speed != null && speed > maxSpeed) maxSpeed = speed
        if (rpm != null && rpm > maxRpm) maxRpm = rpm
        if (coolant != null) {
            val mc = maxCoolant
            if (mc == null || coolant > mc) maxCoolant = coolant
            if (coldStart == null) coldStart = coolant < coldStartBelowC
        }

        if (prevMs != null) {
            val dtMs = t - prevMs
            if (dtMs <= maxGapMs) {
                val dtH = dtMs / MS_PER_HOUR
                integratedHours += dtH
                val v0 = lastSpeed
                if (v0 != null && speed != null) distanceKm += (v0 + speed) / 2.0 * dtH
                val f0 = lastLph
                if (f0 != null && lph != null) {
                    fuelLitres += (f0 + lph) / 2.0 * dtH
                    hasFuelData = true
                }
            }
        }

        lastMs = t
        lastSpeed = speed
        lastLph = lph
    }

    @Synchronized
    fun summary(): TripSummary {
        val avgSpeed = if (integratedHours > 0.0) distanceKm / integratedHours else 0.0
        val avgL100 = if (hasFuelData && distanceKm >= MIN_ECONOMY_DISTANCE_KM) fuelLitres / distanceKm * 100.0 else null
        return TripSummary(
            startMs = startMs,
            endMs = endMs,
            distanceKm = distanceKm,
            fuelLitres = fuelLitres,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeed,
            maxRpm = maxRpm,
            maxCoolantC = maxCoolant,
            avgLPer100 = avgL100,
            coldStart = coldStart ?: false,
        )
    }

    private fun currentLph(s: TelemetrySnapshot): Double? {
        s[Metric.FUEL_FLOW]?.let { if (it.isFinite() && it >= 0.0) return it }
        val maf = s[Metric.MAF] ?: return null
        val est = fuel.estimate(
            mafGs = maf,
            fuelStatus = s[Metric.FUEL_SYSTEM_STATUS]?.toInt(),
            commandedLambda = s[Metric.COMMANDED_LAMBDA],
            rpm = s[Metric.RPM],
        )
        return est.litresPerHour.takeIf { it.isFinite() && it >= 0.0 }
    }

    companion object {
        const val DEFAULT_MAX_GAP_MS: Long = 5000L
        const val DEFAULT_COLD_START_C: Double = 40.0
        const val MIN_ECONOMY_DISTANCE_KM: Double = 0.1
        private const val MS_PER_HOUR: Double = 3_600_000.0
    }
}
