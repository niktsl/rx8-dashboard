package com.rx8.dash.ui.components

import com.rx8.dash.core.Metric
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Locale-stable number formatting for gauges and lists. */
object Fmt {
    fun decimals(m: Metric): Int = when (m) {
        Metric.RPM, Metric.SPEED, Metric.COOLANT, Metric.INTAKE_AIR, Metric.OIL_TEMP_AUX,
        Metric.THROTTLE, Metric.LOAD, Metric.FUEL_SYSTEM_STATUS -> 0
        Metric.COMMANDED_LAMBDA, Metric.WIDEBAND_LAMBDA -> 2
        else -> 1
    }

    fun value(m: Metric, v: Double?): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "--"
        if (m == Metric.OIL_PRESSURE_OK) return if (v >= 0.5) "OK" else "LOW"
        if (m == Metric.FUEL_SYSTEM_STATUS) return fuelLoop(v.toInt())
        return num(v, decimals(m))
    }

    fun num(v: Double?, decimals: Int): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "--"
        return String.format(Locale.US, "%.${decimals.coerceIn(0, 4)}f", v)
    }

    fun int(v: Double?): String = if (v == null || v.isNaN() || v.isInfinite()) "--" else v.roundToLong().toString()

    fun fuelLoop(status: Int): String = when {
        (status and 0x02) != 0 -> "Closed"
        (status and 0x04) != 0 -> "Open (load)"
        (status and 0x08) != 0 -> "Open (fault)"
        (status and 0x01) != 0 -> "Open (cold)"
        (status and 0x10) != 0 -> "Closed (fault)"
        else -> "--"
    }

    fun duration(ms: Long): String {
        val totalSec = abs(ms) / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun dateTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    fun date(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    fun hex(v: Int, digits: Int): String = String.format(Locale.US, "%0${digits}X", v)

    fun bytes(b: IntArray?): String =
        b?.joinToString(" ") { String.format(Locale.US, "%02X", it and 0xFF) } ?: "no answer"
}
