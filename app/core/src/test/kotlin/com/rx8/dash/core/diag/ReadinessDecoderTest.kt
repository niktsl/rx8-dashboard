package com.rx8.dash.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessDecoderTest {

    @Test
    fun decodesMilCountAndSparkMonitors() {
        // A=0x83: MIL on, 3 DTCs. B=0x07: misfire/fuel/components supported and complete, spark ignition.
        // C=0x65: catalyst, evap, O2 sensor, O2 heater supported. D=0x04: evap incomplete.
        val r = ReadinessDecoder.decode(intArrayOf(0x41, 0x01, 0x83, 0x07, 0x65, 0x04))!!
        assertTrue(r.milOn)
        assertEquals(3, r.dtcCount)
        assertEquals(Pair(true, true), r.monitors["Misfire"])
        assertEquals(Pair(true, true), r.monitors["Fuel system"])
        assertEquals(Pair(true, true), r.monitors["Components"])
        assertEquals(Pair(true, true), r.monitors["Catalyst"])
        assertEquals(Pair(false, false), r.monitors["Heated catalyst"])
        assertEquals(Pair(true, false), r.monitors["Evaporative system"])
        assertEquals(Pair(false, false), r.monitors["Secondary air"])
        assertEquals(Pair(true, true), r.monitors["Oxygen sensor"])
        assertEquals(Pair(true, true), r.monitors["Oxygen sensor heater"])
        assertEquals(Pair(false, false), r.monitors["EGR/VVT"])
        assertEquals(11, r.monitors.size)
    }

    @Test
    fun milOffAndIncompleteContinuousMonitor() {
        // B=0x13: misfire supported but incomplete (bit 4), fuel system supported and complete.
        val r = ReadinessDecoder.decode(intArrayOf(0x41, 0x01, 0x00, 0x13, 0x00, 0x00))!!
        assertFalse(r.milOn)
        assertEquals(0, r.dtcCount)
        assertEquals(Pair(true, false), r.monitors["Misfire"])
        assertEquals(Pair(true, true), r.monitors["Fuel system"])
        assertEquals(Pair(false, false), r.monitors["Components"])
    }

    @Test
    fun tooShortIsNull() {
        assertNull(ReadinessDecoder.decode(intArrayOf(0x41, 0x01, 0x00)))
    }

    @Test
    fun vinDecode() {
        val vin = "JM1FE173360000000"
        val payload = intArrayOf(0x49, 0x02, 0x01) + IntArray(vin.length) { vin[it].code }
        assertEquals(vin, VinDecoder.decode(payload))
        assertNull(VinDecoder.decode(intArrayOf(0x49, 0x02, 0x01, 0x41)))
    }
}
