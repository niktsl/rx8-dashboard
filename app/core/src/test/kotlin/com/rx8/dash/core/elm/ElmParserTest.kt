package com.rx8.dash.core.elm

import com.rx8.dash.core.ElmErrorKind
import com.rx8.dash.core.ElmResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmParserTest {

    private fun data(r: ElmResponse): IntArray {
        assertTrue("expected Data but was $r", r is ElmResponse.Data)
        return (r as ElmResponse.Data).first
    }

    @Test
    fun singleFrameWithSpaces() {
        assertArrayEquals(intArrayOf(0x41, 0x0C, 0x1A, 0xF8), data(ElmParser.parse("41 0C 1A F8 \r\r>")))
    }

    @Test
    fun singleFrameWithoutSpaces() {
        assertArrayEquals(intArrayOf(0x41, 0x0D, 0x32), data(ElmParser.parse("410D32\r\r")))
    }

    @Test
    fun echoAndSearchingAreSkipped() {
        val r = ElmParser.parse("010C\rSEARCHING...\r41 0C 1A F8\r\r>")
        assertArrayEquals(intArrayOf(0x41, 0x0C, 0x1A, 0xF8), data(r))
    }

    @Test
    fun knownEchoIsDropped() {
        val r = ElmParser.parse("010D\r410D32\r", "010D")
        assertArrayEquals(intArrayOf(0x41, 0x0D, 0x32), data(r))
    }

    @Test
    fun busInitNoiseIsSkipped() {
        val r = ElmParser.parse("BUS INIT: ...OK\r41 05 7B\r")
        assertArrayEquals(intArrayOf(0x41, 0x05, 0x7B), data(r))
    }

    @Test
    fun multiFrameIsReassembledAndTrimmedToByteCount() {
        val raw = "014\r0: 49 02 01 31 44 34\r1: 47 50 30 30 52 35 35\r2: 42 31 32 33 34 35 36\r\r>"
        val p = data(ElmParser.parse(raw))
        assertEquals(20, p.size)
        assertEquals(0x49, p[0])
        assertEquals(0x02, p[1])
        val vin = p.copyOfRange(3, p.size).map { it.toChar() }.joinToString("")
        assertEquals("1D4GP00R55B123456", vin)
    }

    @Test
    fun multiFrameWithoutSpacesAndPadding() {
        val raw = "00A\r0:430400010302\r1:03010455555555\r"
        val p = data(ElmParser.parse(raw))
        assertArrayEquals(intArrayOf(0x43, 0x04, 0x00, 0x01, 0x03, 0x02, 0x03, 0x01, 0x04, 0x55), p)
    }

    @Test
    fun noData() {
        assertEquals(ElmResponse.NoData, ElmParser.parse("NO DATA\r\r>"))
        assertEquals(ElmResponse.NoData, ElmParser.parse("SEARCHING...\rNO DATA\r"))
        assertEquals(ElmResponse.NoData, ElmParser.parse("\r\r>"))
    }

    @Test
    fun questionMarkIsUnsupported() {
        assertEquals(ElmResponse.Unsupported, ElmParser.parse("?\r\r>"))
    }

    @Test
    fun errors() {
        assertEquals(ElmErrorKind.CAN_ERROR, (ElmParser.parse("CAN ERROR\r") as ElmResponse.Error).kind)
        assertEquals(ElmErrorKind.BUS_ERROR, (ElmParser.parse("BUS ERROR\r") as ElmResponse.Error).kind)
        assertEquals(ElmErrorKind.STOPPED, (ElmParser.parse("STOPPED\r") as ElmResponse.Error).kind)
        assertEquals(ElmErrorKind.BUFFER_FULL, (ElmParser.parse("BUFFER FULL\r") as ElmResponse.Error).kind)
        assertEquals(ElmErrorKind.DATA_ERROR, (ElmParser.parse("41 0C 1A <DATA ERROR\r") as ElmResponse.Error).kind)
        assertEquals(
            ElmErrorKind.UNABLE_TO_CONNECT,
            (ElmParser.parse("SEARCHING...\rUNABLE TO CONNECT\r") as ElmResponse.Error).kind,
        )
    }

    @Test
    fun negativeResponse() {
        val r = ElmParser.parse("7F 22 31\r")
        assertTrue(r is ElmResponse.Error)
        assertEquals(ElmErrorKind.NEGATIVE_RESPONSE, (r as ElmResponse.Error).kind)
    }

    @Test
    fun responsePendingIsSkippedWhenRealAnswerFollows() {
        val r = ElmParser.parse("7F 22 78\r62 11 77 0B B8\r")
        assertArrayEquals(intArrayOf(0x62, 0x11, 0x77, 0x0B, 0xB8), data(r))
    }

    @Test
    fun garbageIsOtherError() {
        val r = ElmParser.parse("41 0C 1Z\r")
        assertTrue(r is ElmResponse.Error)
        assertEquals(ElmErrorKind.OTHER, (r as ElmResponse.Error).kind)
    }
}
