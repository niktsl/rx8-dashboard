package com.rx8.dash.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDecoderTest {

    private fun bytes(vararg v: Int): IntArray = v

    @Test
    fun canPayloadWithCountByte() {
        val dtcs = DtcDecoder.decode(bytes(0x43, 0x02, 0x01, 0x33, 0x00, 0x00))
        assertEquals(listOf("P0133"), dtcs.map { it.code })
        assertNotNull(dtcs[0].description)
    }

    @Test
    fun canMultipleCodes() {
        val dtcs = DtcDecoder.decode(bytes(0x43, 0x03, 0x03, 0x00, 0x03, 0x01, 0x04, 0x20))
        assertEquals(listOf("P0300", "P0301", "P0420"), dtcs.map { it.code })
    }

    @Test
    fun canZeroCount() {
        assertTrue(DtcDecoder.decode(bytes(0x43, 0x00)).isEmpty())
    }

    @Test
    fun legacyPayloadWithoutCountByte() {
        val dtcs = DtcDecoder.decode(bytes(0x43, 0x01, 0x33, 0x03, 0x00, 0x00, 0x00))
        assertEquals(listOf("P0133", "P0300"), dtcs.map { it.code })
    }

    @Test
    fun pendingAndPermanentSids() {
        assertEquals(listOf("P0171"), DtcDecoder.decode(bytes(0x47, 0x01, 0x01, 0x71)).map { it.code })
        assertEquals(listOf("P2004"), DtcDecoder.decode(bytes(0x4A, 0x01, 0x20, 0x04)).map { it.code })
    }

    @Test
    fun letterAndDigitMapping() {
        assertEquals("P0133", DtcDecoder.codeFor(0x01, 0x33))
        assertEquals("P2008", DtcDecoder.codeFor(0x20, 0x08))
        assertEquals("C0123", DtcDecoder.codeFor(0x41, 0x23))
        assertEquals("B1234", DtcDecoder.codeFor(0x92, 0x34))
        assertEquals("U0100", DtcDecoder.codeFor(0xC1, 0x00))
        assertEquals("P3ABC", DtcDecoder.codeFor(0x3A, 0xBC))
    }

    @Test
    fun descriptions() {
        assertTrue(DtcDescriptions.describe("P0301")!!.contains("front rotor"))
        assertTrue(DtcDescriptions.describe("P0302")!!.contains("rear rotor"))
        assertNotNull(DtcDescriptions.describe("P0420"))
        assertNotNull(DtcDescriptions.describe("P0480"))
        assertNotNull(DtcDescriptions.describe("P2006"))
        assertNotNull(DtcDescriptions.describe("p0118"))
        // Unknown powertrain code falls back to a category; non-P unknown codes are null.
        assertTrue(DtcDescriptions.describe("P0399")!!.contains("Ignition"))
        assertNull(DtcDescriptions.describe("U3FFF"))
        // Variable cam timing codes do not apply to the RX-8 and are not in the table.
        assertFalse(DtcDescriptions.describe("P0011")!!.contains("Camshaft"))
    }
}
