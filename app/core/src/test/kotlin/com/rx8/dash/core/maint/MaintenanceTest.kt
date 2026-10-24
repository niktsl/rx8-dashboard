package com.rx8.dash.core.maint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MaintenanceTest {
    private val day = 86_400_000L

    private fun oil(lastKm: Double?) = MaintenanceItem("oil_filter", "Oil", 5000, null, lastKm, null)

    @Test
    fun defaultsPresent() {
        val byId = MaintenanceDefaults.items.associateBy { it.id }
        assertEquals(5000, byId.getValue(MaintenanceDefaults.OIL_FILTER).intervalKm)
        assertEquals(40000, byId.getValue(MaintenanceDefaults.IGNITION_COILS).intervalKm)
        assertEquals(30000, byId.getValue(MaintenanceDefaults.SPARK_PLUGS).intervalKm)
        assertEquals(730, byId.getValue(MaintenanceDefaults.COOLANT).intervalDays)
        assertEquals(40000, byId.getValue(MaintenanceDefaults.COOLANT).intervalKm)
        assertEquals(20000, byId.getValue(MaintenanceDefaults.AIR_FILTER).intervalKm)
        assertEquals(1000, byId.getValue(MaintenanceDefaults.OIL_LEVEL).intervalKm)
        assertNotNull(byId[MaintenanceDefaults.PREMIX_OMP])
    }

    @Test
    fun kmStates() {
        assertEquals(Pair(DueState.OK, "in 2000 km"), MaintenanceEvaluator.evaluate(oil(10_000.0), 13_000.0, 0L))
        assertEquals(Pair(DueState.SOON, "in 400 km"), MaintenanceEvaluator.evaluate(oil(10_000.0), 14_600.0, 0L))
        assertEquals(DueState.SOON, MaintenanceEvaluator.evaluate(oil(10_000.0), 14_500.0, 0L).first)
        assertEquals(Pair(DueState.DUE, "due now"), MaintenanceEvaluator.evaluate(oil(10_000.0), 15_000.0, 0L))
        assertEquals(Pair(DueState.DUE, "200 km overdue"), MaintenanceEvaluator.evaluate(oil(10_000.0), 15_200.0, 0L))
        assertEquals(Pair(DueState.OVERDUE, "800 km overdue"), MaintenanceEvaluator.evaluate(oil(10_000.0), 15_800.0, 0L))
    }

    @Test
    fun soonWindowIsTenPercentForLongIntervals() {
        val coils = MaintenanceItem("c", "Coils", 40_000, null, 0.0, null)
        assertEquals(DueState.SOON, MaintenanceEvaluator.evaluate(coils, 37_000.0, 0L).first)
        assertEquals(DueState.OK, MaintenanceEvaluator.evaluate(coils, 35_000.0, 0L).first)
    }

    @Test
    fun notRecordedIsDue() {
        assertEquals(DueState.DUE, MaintenanceEvaluator.evaluate(oil(null), 1000.0, 0L).first)
    }

    @Test
    fun dateDimensionAndWorstWins() {
        val coolant = MaintenanceItem("coolant", "Coolant", 40_000, 730, 0.0, 0L)
        assertEquals(Pair(DueState.OK, "in 30000 km"), MaintenanceEvaluator.evaluate(coolant, 10_000.0, 100 * day))
        // 2 years minus 30 days: within the 73-day window.
        assertEquals(Pair(DueState.SOON, "in 30 days"), MaintenanceEvaluator.evaluate(coolant, 10_000.0, 700 * day))
        assertEquals(Pair(DueState.OVERDUE, "100 days overdue"), MaintenanceEvaluator.evaluate(coolant, 10_000.0, 830 * day))
    }

    @Test
    fun noIntervalIsOk() {
        val item = MaintenanceItem("x", "Custom", null, null, null, null)
        assertEquals(DueState.OK, MaintenanceEvaluator.evaluate(item, 0.0, 0L).first)
    }
}
