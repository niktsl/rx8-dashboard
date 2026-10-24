package com.rx8.dash.core.fuel

import org.junit.Assert.assertEquals
import org.junit.Test

class FuelCalibrationTest {

    @Test
    fun defaultWhenInsufficientData() {
        assertEquals(1.0, FuelCalibration.fromFillUps(emptyList(), emptyList()), 0.0)
        assertEquals(1.0, FuelCalibration.fromFillUps(listOf(FillUp(1000.0, 40.0, true)), listOf(0.0)), 0.0)
        // Only one full fill, so no complete segment.
        val fills = listOf(FillUp(1000.0, 20.0, false), FillUp(1400.0, 40.0, true))
        assertEquals(1.0, FuelCalibration.fromFillUps(fills, listOf(0.0, 50.0)), 0.0)
    }

    @Test
    fun ratioBetweenConsecutiveFullFills() {
        val fills = listOf(
            FillUp(1000.0, 45.0, true),
            FillUp(1450.0, 55.0, true),
        )
        assertEquals(1.1, FuelCalibration.fromFillUps(fills, listOf(0.0, 50.0)), 1e-9)
        // Interval-aligned list (one shorter) is accepted too.
        assertEquals(1.1, FuelCalibration.fromFillUps(fills, listOf(50.0)), 1e-9)
    }

    @Test
    fun partialFillsCountTowardSegment() {
        val fills = listOf(
            FillUp(1000.0, 45.0, true),
            FillUp(1200.0, 20.0, false),
            FillUp(1450.0, 30.0, true),
        )
        // Actual 50 L vs estimated 25 + 25 = 50 L.
        assertEquals(1.0, FuelCalibration.fromFillUps(fills, listOf(0.0, 25.0, 25.0)), 1e-9)
    }

    @Test
    fun aggregatesSegmentsAndSortsByOdometer() {
        val fills = listOf(
            FillUp(1900.0, 36.0, true),
            FillUp(1000.0, 45.0, true),
            FillUp(1450.0, 54.0, true),
        )
        // Segments: 54/50 and 36/40, total 90/90.
        assertEquals(1.0, FuelCalibration.fromFillUps(fills, listOf(40.0, 0.0, 50.0)), 1e-9)
    }

    @Test
    fun clamped() {
        val fills = listOf(FillUp(0.0, 40.0, true), FillUp(400.0, 100.0, true))
        assertEquals(1.5, FuelCalibration.fromFillUps(fills, listOf(0.0, 20.0)), 0.0)
        val fills2 = listOf(FillUp(0.0, 40.0, true), FillUp(400.0, 10.0, true))
        assertEquals(0.7, FuelCalibration.fromFillUps(fills2, listOf(0.0, 40.0)), 0.0)
    }
}
