package com.rx8.dash.core.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelModelTest {
    private val model = FuelModel(FuelConfig())
    private val eps = 1e-4

    @Test
    fun closedLoopMaf10GivesExpectedLitresPerHour() {
        val e = model.estimate(mafGs = 10.0, fuelStatus = 0x02, commandedLambda = null, rpm = 2500.0)
        assertEquals(FuelPath.CLOSED_LOOP, e.path)
        assertEquals(1.0, e.lambdaUsed, 0.0)
        // 10 * 3600 / 10878 = 3.30943
        assertEquals(3.3094, e.litresPerHour, eps)
        assertFalse(e.lowConfidence)
    }

    @Test
    fun densityIsApplied() {
        // 14.7 * 740 = 10878 g of air per litre of fuel at stoich.
        val e = model.estimate(10878.0 / 3600.0, 0x02, null, 2000.0)
        assertEquals(1.0, e.litresPerHour, 1e-9)
    }

    @Test
    fun closedLoopIgnoresCommandedLambda() {
        val e = model.estimate(10.0, 0x02, 0.85, 2500.0)
        assertEquals(FuelPath.CLOSED_LOOP, e.path)
        assertEquals(3.3094, e.litresPerHour, eps)
    }

    @Test
    fun openLoopUsesPid44WhenEnriched() {
        val e = model.estimate(20.0, 0x04, 0.85, 4000.0)
        assertEquals(FuelPath.OPEN_LOOP_PID44, e.path)
        assertEquals(0.85, e.lambdaUsed, 0.0)
        assertEquals(20.0 * 3600.0 / (10878.0 * 0.85), e.litresPerHour, 1e-9)
        assertEquals(7.7869, e.litresPerHour, eps)
        assertFalse(e.lowConfidence)
    }

    @Test
    fun openLoopWithStaticPid44FallsBackToStaging() {
        val e = model.estimate(20.0, 0x04, 1.0, 4000.0)
        assertEquals(FuelPath.OPEN_LOOP_STAGING, e.path)
        assertTrue(e.lowConfidence)
        assertEquals(20.0 * 3600.0 / 10878.0 * 1.12, e.litresPerHour, 1e-9)
    }

    @Test
    fun openLoopWithoutPid44UsesStaging() {
        for (bit in listOf(0x01, 0x04, 0x08)) {
            val e = model.estimate(10.0, bit, null, 3000.0)
            assertEquals(FuelPath.OPEN_LOOP_STAGING, e.path)
            assertEquals(3.3094 * 1.05, e.litresPerHour, eps)
            assertEquals(1.0 / 1.05, e.lambdaUsed, 1e-9)
        }
    }

    @Test
    fun pid44AtThresholdIsNotEnrichment() {
        val e = model.estimate(10.0, 0x04, 0.98, 3000.0)
        assertEquals(FuelPath.OPEN_LOOP_STAGING, e.path)
    }

    @Test
    fun stagingTiers() {
        assertEquals(1.05, model.stagingK(0.0), 0.0)
        assertEquals(1.05, model.stagingK(3749.0), 0.0)
        assertEquals(1.12, model.stagingK(3750.0), 0.0)
        assertEquals(1.12, model.stagingK(5499.0), 0.0)
        assertEquals(1.20, model.stagingK(5500.0), 0.0)
        assertEquals(1.20, model.stagingK(7249.0), 0.0)
        assertEquals(1.28, model.stagingK(7250.0), 0.0)
        assertEquals(1.28, model.stagingK(9000.0), 0.0)
        assertEquals(1.05, model.stagingK(null), 0.0)
    }

    @Test
    fun highRpmIsLowConfidence() {
        val e = model.estimate(100.0, 0x02, null, 6000.0)
        assertTrue(e.lowConfidence)
        val e2 = model.estimate(100.0, 0x02, null, 5500.0)
        assertFalse(e2.lowConfidence)
    }

    @Test
    fun calibrationScalesBothPaths() {
        val cal = FuelModel(FuelConfig(calibration = 1.1))
        assertEquals(3.3094 * 1.1, cal.estimate(10.0, 0x02, null, 2000.0).litresPerHour, eps)
        assertEquals(3.3094 * 1.05 * 1.1, cal.estimate(10.0, 0x01, null, 2000.0).litresPerHour, eps)
    }

    @Test
    fun missingMafIsNoMaf() {
        val e = model.estimate(null, 0x02, null, 2000.0)
        assertEquals(FuelPath.NO_MAF, e.path)
        assertEquals(0.0, e.litresPerHour, 0.0)
    }

    @Test
    fun economyNullBelow3Kmh() {
        assertNull(model.litresPer100Km(3.0, 2.9))
        assertNull(model.litresPer100Km(3.0, null))
        assertEquals(10.0, model.litresPer100Km(5.0, 50.0)!!, 1e-9)
        assertEquals(100.0, model.litresPer100Km(3.0, 3.0)!!, 1e-9)
    }
}
