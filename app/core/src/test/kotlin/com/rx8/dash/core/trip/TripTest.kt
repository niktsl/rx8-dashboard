package com.rx8.dash.core.trip

import com.rx8.dash.core.Metric
import com.rx8.dash.core.TelemetrySnapshot
import com.rx8.dash.core.fuel.FuelConfig
import com.rx8.dash.core.fuel.FuelModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTest {

    private fun snap(t: Long, vararg values: Pair<Metric, Double>): TelemetrySnapshot {
        val map = mapOf(*values)
        return TelemetrySnapshot(t, map, map.keys)
    }

    @Test
    fun integratesDistanceTrapezoidally() {
        val acc = TripAccumulator(FuelModel(FuelConfig()))
        // 0 -> 72 km/h linearly over 10 s, sampled each second: distance = 0.5 * 20 m/s * 10 s = 100 m.
        for (i in 0..10) acc.add(snap(i * 1000L, Metric.SPEED to i * 7.2, Metric.COOLANT to 30.0 + i))
        val s = acc.summary()
        assertEquals(0.1, s.distanceKm, 1e-9)
        assertEquals(0L, s.startMs)
        assertEquals(10_000L, s.endMs)
        assertEquals(72.0, s.maxSpeedKmh, 1e-9)
        assertEquals(36.0, s.avgSpeedKmh, 1e-9)
        assertEquals(40.0, s.maxCoolantC!!, 1e-9)
        assertTrue(s.coldStart)
    }

    @Test
    fun integratesFuelFromMaf() {
        val acc = TripAccumulator(FuelModel(FuelConfig()))
        // Constant 100 km/h and MAF 10 g/s closed loop for one hour, sampled every second.
        for (i in 0..3600) {
            acc.add(snap(i * 1000L, Metric.SPEED to 100.0, Metric.MAF to 10.0, Metric.FUEL_SYSTEM_STATUS to 2.0, Metric.RPM to 3000.0))
        }
        val s = acc.summary()
        assertEquals(100.0, s.distanceKm, 1e-6)
        assertEquals(3.30943, s.fuelLitres, 1e-4)
        assertEquals(3.30943, s.avgLPer100!!, 1e-4)
        assertEquals(3000.0, s.maxRpm, 0.0)
    }

    @Test
    fun skipsGaps() {
        val acc = TripAccumulator(FuelModel(FuelConfig()))
        acc.add(snap(0L, Metric.SPEED to 36.0))
        acc.add(snap(60_000L, Metric.SPEED to 36.0)) // 60 s dropout: not integrated
        acc.add(snap(61_000L, Metric.SPEED to 36.0))
        val s = acc.summary()
        assertEquals(0.01, s.distanceKm, 1e-9)
        assertNull(s.avgLPer100)
    }

    @Test
    fun accelZeroToHundred() {
        val timer = AccelTimer()
        timer.add(snap(0L, Metric.SPEED to 0.0))
        timer.add(snap(500L, Metric.SPEED to 0.0))
        // Leaves standstill after 500 ms; +10 km/h every 500 ms.
        var t = 1000L
        var v = 10.0
        while (v <= 110.0) {
            timer.add(snap(t, Metric.SPEED to v))
            t += 500L
            v += 10.0
        }
        val runs = timer.results.value
        assertEquals(1, runs.size)
        assertEquals(AccelTimer.KIND_0_100, runs[0].kind)
        // Start at 500 ms (last standstill sample); 100 km/h reached at 5500 ms.
        assertEquals(5.0, runs[0].seconds, 1e-9)
    }

    @Test
    fun accelSixtyToOneThirtyInterpolated() {
        val timer = AccelTimer()
        // 50 -> 140 km/h at 10 km/h per second, sampled each second.
        var t = 0L
        var v = 50.0
        while (v <= 140.0) {
            timer.add(snap(t, Metric.SPEED to v))
            t += 1000L
            v += 10.0
        }
        val run = timer.results.value.single { it.kind == AccelTimer.KIND_60_130 }
        assertEquals(7.0, run.seconds, 1e-9)
    }

    @Test
    fun accelAbortsOnLift() {
        val timer = AccelTimer()
        timer.add(snap(0L, Metric.SPEED to 0.0))
        timer.add(snap(1000L, Metric.SPEED to 20.0))
        timer.add(snap(2000L, Metric.SPEED to 40.0))
        timer.add(snap(3000L, Metric.SPEED to 30.0)) // lift: run abandoned
        timer.add(snap(4000L, Metric.SPEED to 70.0))
        timer.add(snap(5000L, Metric.SPEED to 105.0))
        assertTrue(timer.results.value.none { it.kind == AccelTimer.KIND_0_100 })
    }
}
