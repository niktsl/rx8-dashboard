package com.rx8.dash.core.trip

import com.rx8.dash.core.Metric
import com.rx8.dash.core.TelemetrySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AccelRun(val kind: String, val seconds: Double, val atMs: Long)

/**
 * Measures 0–100 and 60–130 km/h runs from the speed PID.
 *
 * 0–100 starts at the last standstill sample (< 1 km/h) before the car moves. 60–130 starts at the interpolated
 * upward crossing of 60 km/h. Both end at the interpolated crossing of the target speed. A run is abandoned when
 * speed drops more than [ABORT_DROP_KMH] below its peak, when it exceeds its time limit, or when samples are more than
 * [MAX_SAMPLE_GAP_MS] apart. [results] lists completed runs newest first, keeping at most [MAX_RESULTS].
 */
class AccelTimer {
    private class Window(
        val kind: String,
        val startKmh: Double,
        val targetKmh: Double,
        val startAtPreviousSample: Boolean,
        val maxDurationMs: Long,
    ) {
        var startMs: Double? = null
        var peak: Double = 0.0
    }

    private val windows = listOf(
        Window(KIND_0_100, STANDSTILL_KMH, 100.0, startAtPreviousSample = true, maxDurationMs = 30_000L),
        Window(KIND_60_130, 60.0, 130.0, startAtPreviousSample = false, maxDurationMs = 40_000L),
    )

    private val _results = MutableStateFlow<List<AccelRun>>(emptyList())
    val results: StateFlow<List<AccelRun>> = _results.asStateFlow()

    private var prevMs: Long? = null
    private var prevSpeed: Double? = null

    @Synchronized
    fun add(s: TelemetrySnapshot) {
        val speed = s[Metric.SPEED]?.takeIf { it.isFinite() && it >= 0.0 } ?: return
        val t = s.timestampMs
        val pMs = prevMs
        val pV = prevSpeed
        if (pMs != null && t <= pMs) return

        if (pMs != null && pV != null) {
            if (t - pMs > MAX_SAMPLE_GAP_MS) {
                for (w in windows) w.startMs = null
            } else {
                for (w in windows) step(w, pMs, pV, t, speed)
            }
        }
        prevMs = t
        prevSpeed = speed
    }

    @Synchronized
    fun reset() {
        for (w in windows) w.startMs = null
        prevMs = null
        prevSpeed = null
    }

    private fun step(w: Window, t0: Long, v0: Double, t1: Long, v1: Double) {
        if (w.startMs == null) {
            if (v0 < w.startKmh && v1 >= w.startKmh) {
                w.startMs = if (w.startAtPreviousSample) t0.toDouble() else crossing(t0, v0, t1, v1, w.startKmh)
                w.peak = v1
            } else {
                return
            }
        } else {
            val start = w.startMs ?: return
            if (v1 < w.peak - ABORT_DROP_KMH || t1 - start > w.maxDurationMs) {
                w.startMs = null
                return
            }
            if (v1 > w.peak) w.peak = v1
        }

        val start = w.startMs ?: return
        if (v1 >= w.targetKmh && v0 < w.targetKmh) {
            val end = crossing(t0, v0, t1, v1, w.targetKmh)
            val seconds = (end - start) / 1000.0
            w.startMs = null
            if (seconds > 0.0) {
                val run = AccelRun(w.kind, seconds, t1)
                _results.value = (listOf(run) + _results.value).take(MAX_RESULTS)
            }
        }
    }

    /** Linear interpolation of the time at which speed crossed [target] between two samples. */
    private fun crossing(t0: Long, v0: Double, t1: Long, v1: Double, target: Double): Double {
        if (v1 == v0) return t1.toDouble()
        val frac = ((target - v0) / (v1 - v0)).coerceIn(0.0, 1.0)
        return t0 + frac * (t1 - t0)
    }

    companion object {
        const val KIND_0_100 = "0-100"
        const val KIND_60_130 = "60-130"
        const val STANDSTILL_KMH: Double = 1.0
        const val ABORT_DROP_KMH: Double = 3.0
        const val MAX_SAMPLE_GAP_MS: Long = 2000L
        const val MAX_RESULTS: Int = 50
    }
}
