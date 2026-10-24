package com.rx8.dash.core.health

import com.rx8.dash.core.AlertLevel
import com.rx8.dash.core.Metric
import com.rx8.dash.core.TelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaryHealthTest {
    private val monitor = RotaryHealthMonitor(HealthConfig())

    private fun snap(vararg values: Pair<Metric, Double>): TelemetrySnapshot {
        val map = mapOf(*values)
        return TelemetrySnapshot(1000L, map, map.keys)
    }

    @Test
    fun warmupStages() {
        assertEquals(WarmupStage.COLD, monitor.stage(null))
        assertEquals(WarmupStage.COLD, monitor.stage(20.0))
        assertEquals(WarmupStage.COLD, monitor.stage(39.9))
        assertEquals(WarmupStage.WARMING, monitor.stage(40.0))
        assertEquals(WarmupStage.WARMING, monitor.stage(81.9))
        assertEquals(WarmupStage.READY, monitor.stage(82.0))
        assertEquals(WarmupStage.READY, monitor.stage(95.0))
    }

    @Test
    fun coldHighRpmAlerts() {
        val cold = monitor.evaluate(snap(Metric.COOLANT to 30.0, Metric.RPM to 4500.0))
        assertEquals(AlertLevel.WARNING, cold.single { it.id == RotaryHealthMonitor.ID_COLD_RPM }.level)

        val warming = monitor.evaluate(snap(Metric.COOLANT to 60.0, Metric.RPM to 4500.0))
        assertEquals(AlertLevel.CAUTION, warming.single { it.id == RotaryHealthMonitor.ID_COLD_RPM }.level)

        val belowLimit = monitor.evaluate(snap(Metric.COOLANT to 60.0, Metric.RPM to 3900.0))
        assertTrue(belowLimit.none { it.id == RotaryHealthMonitor.ID_COLD_RPM })

        val warm = monitor.evaluate(snap(Metric.COOLANT to 90.0, Metric.RPM to 7000.0))
        assertTrue(warm.isEmpty())

        // No coolant data: no cold-rpm alert.
        assertTrue(monitor.evaluate(snap(Metric.RPM to 6000.0)).isEmpty())
    }

    @Test
    fun coolantThresholds() {
        assertTrue(monitor.evaluate(snap(Metric.COOLANT to 104.0)).isEmpty())
        assertEquals(AlertLevel.CAUTION, monitor.evaluate(snap(Metric.COOLANT to 105.0)).single().level)
        assertEquals(AlertLevel.WARNING, monitor.evaluate(snap(Metric.COOLANT to 112.0)).single().level)
    }

    @Test
    fun voltage() {
        assertTrue(monitor.evaluate(snap(Metric.VOLTAGE to 14.1, Metric.RPM to 900.0)).isEmpty())
        assertEquals(AlertLevel.CAUTION, monitor.evaluate(snap(Metric.VOLTAGE to 11.9, Metric.RPM to 900.0)).single().level)
        assertEquals(AlertLevel.WARNING, monitor.evaluate(snap(Metric.VOLTAGE to 15.5)).single().level)
    }

    @Test
    fun nearRedline() {
        val a = monitor.evaluate(snap(Metric.COOLANT to 90.0, Metric.RPM to 8800.0))
        assertEquals(RotaryHealthMonitor.ID_REDLINE, a.single().id)
        assertTrue(monitor.evaluate(snap(Metric.COOLANT to 90.0, Metric.RPM to 8600.0)).isEmpty())
        val custom = RotaryHealthMonitor(HealthConfig(redlineRpm = 7500))
        assertEquals(RotaryHealthMonitor.ID_REDLINE, custom.evaluate(snap(Metric.COOLANT to 90.0, Metric.RPM to 7300.0)).single().id)
    }

    @Test
    fun oilPressureOnlyWhenPresentAndRunning() {
        assertTrue(monitor.evaluate(snap(Metric.COOLANT to 90.0, Metric.RPM to 2000.0)).isEmpty())
        val low = monitor.evaluate(snap(Metric.COOLANT to 90.0, Metric.RPM to 2000.0, Metric.OIL_PRESSURE_OK to 0.0))
        assertEquals(RotaryHealthMonitor.ID_OIL_PRESSURE, low.single().id)
        assertTrue(monitor.evaluate(snap(Metric.COOLANT to 90.0, Metric.RPM to 2000.0, Metric.OIL_PRESSURE_OK to 1.0)).isEmpty())
        // Engine stopped: the switch reads LOW normally.
        assertTrue(monitor.evaluate(snap(Metric.COOLANT to 90.0, Metric.RPM to 0.0, Metric.OIL_PRESSURE_OK to 0.0)).isEmpty())
    }

    @Test
    fun startLogAndFloodRisk() {
        val log = StartLog()
        assertTrue(log.isShortColdTrip(4.0, 50.0))
        assertTrue(log.isShortColdTrip(4.0, null))
        assertFalse(log.isShortColdTrip(4.0, 85.0))
        assertFalse(log.isShortColdTrip(25.0, 50.0))
        assertNull(log.floodRisk(1))
        assertEquals(AlertLevel.CAUTION, log.floodRisk(2))
        assertEquals(AlertLevel.WARNING, log.floodRisk(4))
    }

    @Test
    fun premix() {
        assertEquals(100.0, Premix.mlFor(50.0, Premix.STREET_ML_PER_LITRE), 1e-9)
        assertEquals(200.0, Premix.mlFor(50.0, Premix.SPORT_ML_PER_LITRE), 1e-9)
        assertEquals(0.0, Premix.mlFor(-1.0, 2.0), 0.0)
    }
}
