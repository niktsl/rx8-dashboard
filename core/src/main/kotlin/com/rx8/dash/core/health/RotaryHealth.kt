package com.rx8.dash.core.health

import com.rx8.dash.core.Alert
import com.rx8.dash.core.AlertLevel
import com.rx8.dash.core.Metric
import com.rx8.dash.core.TelemetrySnapshot

enum class WarmupStage {
    /** Below 40 °C: idle or drive gently. */
    COLD,

    /** 40–82 °C: drive, but keep below the cold rpm limit. */
    WARMING,

    /** At or above 82 °C: full rpm available. */
    READY,
}

data class HealthConfig(
    val redlineRpm: Int = 9000,
    val coldRpmLimit: Int = 4000,
    val readyCoolantC: Double = 82.0,
    val minDriveCoolantC: Double = 40.0,
    val coolantWarnC: Double = 105.0,
    val coolantCritC: Double = 112.0,
    val lowVoltage: Double = 12.2,
    val highVoltage: Double = 15.0,
    val shortTripMinutes: Int = 10,
)

class RotaryHealthMonitor(val config: HealthConfig = HealthConfig()) {

    /** Unknown coolant is treated as COLD (conservative guidance). */
    fun stage(coolantC: Double?): WarmupStage = when {
        coolantC == null || coolantC.isNaN() -> WarmupStage.COLD
        coolantC >= config.readyCoolantC -> WarmupStage.READY
        coolantC >= config.minDriveCoolantC -> WarmupStage.WARMING
        else -> WarmupStage.COLD
    }

    fun evaluate(s: TelemetrySnapshot): List<Alert> {
        val alerts = ArrayList<Alert>()
        val rpm = s[Metric.RPM]
        val coolant = s[Metric.COOLANT]
        val voltage = s[Metric.VOLTAGE]
        val engineRunning = rpm != null && rpm >= RUNNING_RPM

        // High rpm before the engine is warm (only when coolant is actually known).
        if (coolant != null && rpm != null && rpm > config.coldRpmLimit) {
            when (stage(coolant)) {
                WarmupStage.COLD -> alerts.add(
                    Alert(
                        ID_COLD_RPM, AlertLevel.WARNING, "High rpm on a cold engine",
                        "Coolant ${fmt0(coolant)} °C. Keep it gentle and below ${config.coldRpmLimit} rpm until ${fmt0(config.readyCoolantC)} °C.",
                    ),
                )
                WarmupStage.WARMING -> alerts.add(
                    Alert(
                        ID_COLD_RPM, AlertLevel.CAUTION, "Engine still warming up",
                        "Coolant ${fmt0(coolant)} °C. Stay below ${config.coldRpmLimit} rpm until ${fmt0(config.readyCoolantC)} °C.",
                    ),
                )
                WarmupStage.READY -> Unit
            }
        }

        if (coolant != null) {
            if (coolant >= config.coolantCritC) {
                alerts.add(
                    Alert(
                        ID_COOLANT, AlertLevel.WARNING, "Coolant critical",
                        "Coolant ${fmt0(coolant)} °C. Pull over safely and let the engine cool; overheating damages rotor housings and seals.",
                    ),
                )
            } else if (coolant >= config.coolantWarnC) {
                alerts.add(
                    Alert(
                        ID_COOLANT, AlertLevel.CAUTION, "Coolant high",
                        "Coolant ${fmt0(coolant)} °C. Ease off and watch the temperature.",
                    ),
                )
            }
        }

        if (voltage != null) {
            if (voltage > config.highVoltage) {
                alerts.add(
                    Alert(
                        ID_VOLTAGE, AlertLevel.WARNING, "Voltage high",
                        "System voltage ${fmt1(voltage)} V. Possible charging regulator fault.",
                    ),
                )
            } else if (voltage < config.lowVoltage) {
                val msg = if (engineRunning) {
                    "System voltage ${fmt1(voltage)} V with the engine running. Check the alternator and battery."
                } else {
                    "Battery ${fmt1(voltage)} V. A weak battery makes hot and cold starts harder on a rotary."
                }
                alerts.add(Alert(ID_VOLTAGE, AlertLevel.CAUTION, "Voltage low", msg))
            }
        }

        if (rpm != null && rpm >= config.redlineRpm - NEAR_REDLINE_MARGIN_RPM &&
            (coolant == null || stage(coolant) == WarmupStage.READY)
        ) {
            alerts.add(Alert(ID_REDLINE, AlertLevel.INFO, "Shift", "${fmt0(rpm)} rpm, near the ${config.redlineRpm} rpm redline."))
        }

        // Oil-pressure switch: 1 = OK, 0 = LOW. Only when the metric is present; ignored with the engine stopped.
        val oil = s.values[Metric.OIL_PRESSURE_OK]
        if (oil != null && oil < 0.5 && (rpm == null || engineRunning)) {
            alerts.add(
                Alert(ID_OIL_PRESSURE, AlertLevel.WARNING, "Oil pressure LOW", "Stop the engine as soon as it is safe and check the oil level."),
            )
        }

        return alerts
    }

    companion object {
        const val ID_COLD_RPM = "cold_rpm"
        const val ID_COOLANT = "coolant"
        const val ID_VOLTAGE = "voltage"
        const val ID_REDLINE = "redline"
        const val ID_OIL_PRESSURE = "oil_pressure"

        const val NEAR_REDLINE_MARGIN_RPM: Int = 300
        const val RUNNING_RPM: Double = 400.0
    }
}

/** Short cold trips: a rotary flooding risk. Pure logic; persistence lives in :app. */
class StartLog(val config: HealthConfig = HealthConfig()) {

    /**
     * A trip that was shorter than [HealthConfig.shortTripMinutes] and never reached operating temperature.
     * Unknown coolant counts as not warmed up.
     */
    fun isShortColdTrip(durationMin: Double, maxCoolantC: Double?): Boolean {
        val short = durationMin < config.shortTripMinutes
        val neverWarm = maxCoolantC == null || maxCoolantC < config.readyCoolantC
        return short && neverWarm
    }

    /** null below 2 consecutive short cold trips, CAUTION at 2–3, WARNING at 4 or more. */
    fun floodRisk(recentShortColdTrips: Int): AlertLevel? = when {
        recentShortColdTrips >= FLOOD_WARNING_TRIPS -> AlertLevel.WARNING
        recentShortColdTrips >= FLOOD_CAUTION_TRIPS -> AlertLevel.CAUTION
        else -> null
    }

    companion object {
        const val FLOOD_CAUTION_TRIPS: Int = 2
        const val FLOOD_WARNING_TRIPS: Int = 4
    }
}

object Premix {
    const val STREET_ML_PER_LITRE: Double = 2.0
    const val SPORT_ML_PER_LITRE: Double = 4.0

    fun mlFor(litres: Double, mlPerLitre: Double): Double {
        if (litres.isNaN() || mlPerLitre.isNaN() || litres <= 0.0 || mlPerLitre <= 0.0) return 0.0
        return litres * mlPerLitre
    }
}

private fun fmt0(v: Double): String = Math.round(v).toString()

private fun fmt1(v: Double): String = (Math.round(v * 10.0) / 10.0).toString()
