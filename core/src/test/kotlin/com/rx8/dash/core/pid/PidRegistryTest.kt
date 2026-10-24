package com.rx8.dash.core.pid

import com.rx8.dash.core.CanTarget
import com.rx8.dash.core.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PidRegistryTest {

    private fun decode(m: Metric, vararg payload: Int): Double? =
        PidRegistry.forMetric(m)!!.decodeResponse(payload)

    @Test
    fun commands() {
        assertEquals("010C", PidRegistry.forMetric(Metric.RPM)!!.command())
        assertEquals("0142", PidRegistry.forMetric(Metric.VOLTAGE)!!.command())
        assertEquals("22C901", PidRegistry.forMetric(Metric.TPMS_FL)!!.command())
    }

    @Test
    fun standardFormulas() {
        assertEquals(1726.0, decode(Metric.RPM, 0x41, 0x0C, 0x1A, 0xF8)!!, 1e-9)
        assertEquals(50.0, decode(Metric.SPEED, 0x41, 0x0D, 0x32)!!, 1e-9)
        assertEquals(83.0, decode(Metric.COOLANT, 0x41, 0x05, 0x7B)!!, 1e-9)
        assertEquals(4.0, decode(Metric.MAF, 0x41, 0x10, 0x01, 0x90)!!, 1e-9)
        assertEquals(14.0, decode(Metric.VOLTAGE, 0x41, 0x42, 0x36, 0xB0)!!, 1e-9)
        assertEquals(1.0, decode(Metric.COMMANDED_LAMBDA, 0x41, 0x44, 0x80, 0x00)!!, 1e-9)
        assertEquals(0.0, decode(Metric.STFT, 0x41, 0x06, 0x80)!!, 1e-9)
        assertEquals(8.0, decode(Metric.TIMING_ADVANCE, 0x41, 0x0E, 0x90)!!, 1e-9)
        assertEquals(2.0, decode(Metric.FUEL_SYSTEM_STATUS, 0x41, 0x03, 0x02, 0x00)!!, 1e-9)
    }

    @Test
    fun wrongEchoOrShortPayloadIsRejected() {
        assertNull(decode(Metric.RPM, 0x41, 0x0D, 0x1A, 0xF8))
        assertNull(decode(Metric.RPM, 0x41, 0x0C, 0x1A))
        assertNull(decode(Metric.RPM, 0x7F, 0x01, 0x12, 0x00))
    }

    @Test
    fun tpmsDecodeAndTargets() {
        val def = PidRegistry.forMetric(Metric.TPMS_FL)!!
        assertEquals(CanTarget.TPMS, def.target)
        assertFalse(def.validated)
        val psi = def.decodeResponse(intArrayOf(0x62, 0xC9, 0x01, 0x90))!!
        assertEquals(0x90 * 1.373 * 0.145037738, psi, 1e-9)
    }

    @Test
    fun extendedAreUnvalidatedAndOilPressureIsNotPollable() {
        assertTrue(PidRegistry.extended.all { !it.validated })
        val oil = PidRegistry.forMetric(Metric.OIL_PRESSURE_OK)
        assertNotNull(oil)
        assertFalse(PidRegistry.isPollable(oil!!))
        val maf = PidRegistry.scannerDids.first { it.did == 0x1177 }
        assertTrue(maf.validated)
        assertEquals(CanTarget.ENGINE, maf.target)
    }

    @Test
    fun supportedPidBitmaps() {
        val s = SupportedPids.decode(
            mapOf(
                0x00 to intArrayOf(0xBE, 0x1F, 0xA0, 0x11),
                0x40 to intArrayOf(0x41, 0x40, 0x50, 0x00, 0x00, 0x00), // full payload form
            ),
        )
        for (pid in listOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x1C, 0x20, 0x42, 0x44)) {
            assertTrue("pid ${"%02X".format(pid)}", pid in s)
        }
        assertFalse(0x02 in s)
        assertFalse(0x24 in s)
        assertEquals(17, s.size)
    }
}
