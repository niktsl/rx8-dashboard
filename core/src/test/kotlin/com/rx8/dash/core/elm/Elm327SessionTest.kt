package com.rx8.dash.core.elm

import com.rx8.dash.core.CanTarget
import com.rx8.dash.core.ElmResponse
import com.rx8.dash.core.ElmTimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Elm327SessionTest {

    private fun FakeElmIo.count(prefix: String) = written.count { it.startsWith(prefix) }

    @Test
    fun initializeSendsResetAndConfigAndDetectsClone() = runTest {
        val io = FakeElmIo(identity = "ELM327 v1.5", sti = "?")
        val session = Elm327Session(io, FakeClock())
        val info = session.initialize()
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP6", "ATI", "STI"), io.written)
        assertEquals("ELM327 v1.5", info.identity)
        assertNull(info.stnIdentity)
        assertFalse(info.isGenuineStn)
        assertFalse(info.multiPidAllowed)
        assertNull(session.currentTarget)
    }

    @Test
    fun initializeDetectsStn() = runTest {
        val io = FakeElmIo(identity = "ELM327 v1.4b", sti = "STN1110 v4.2.1")
        val info = Elm327Session(io, FakeClock()).initialize()
        assertTrue(info.isGenuineStn)
        assertEquals("STN1110 v4.2.1", info.stnIdentity)
        assertFalse("multi-PID needs the poller self-test first", info.multiPidAllowed)
    }

    @Test
    fun headerSentOnlyOnChange() = runTest {
        val io = FakeElmIo()
        val session = Elm327Session(io, FakeClock())
        session.setTarget(CanTarget.ENGINE)
        session.setTarget(CanTarget.ENGINE)
        session.setTarget(CanTarget.ENGINE)
        assertEquals(1, io.count("ATSH"))
        assertEquals(1, io.count("ATCRA"))
        assertEquals(listOf("ATSH7E0", "ATCRA7E8"), io.written)
        session.setTarget(CanTarget.TPMS)
        assertEquals(listOf("ATSH7E0", "ATCRA7E8", "ATSH751", "ATCRA759"), io.written)
        assertEquals(CanTarget.TPMS, session.currentTarget)
    }

    @Test
    fun timeoutInvalidatesCache() = runTest {
        val io = FakeElmIo(responder = FakeElmIo.rx8Responder())
        val session = Elm327Session(io, FakeClock())
        session.setTarget(CanTarget.ENGINE)
        io.timeoutOnce.add("010C")
        try {
            session.request("010C")
            fail("expected timeout")
        } catch (e: ElmTimeoutException) {
            // expected
        }
        assertNull(session.currentTarget)
        session.setTarget(CanTarget.ENGINE)
        assertEquals(2, io.count("ATSH"))
        val r = session.request("010C")
        assertTrue(r is ElmResponse.Data)
    }

    @Test
    fun unsupportedAndBusErrorsInvalidateCache() = runTest {
        val io = FakeElmIo(responder = { _, cmd -> if (cmd == "0101") "?" else "CAN ERROR" })
        val session = Elm327Session(io, FakeClock())
        session.setTarget(CanTarget.ENGINE)
        assertEquals(ElmResponse.Unsupported, session.request("0101"))
        assertNull(session.currentTarget)
        session.setTarget(CanTarget.ENGINE)
        session.request("0105")
        assertNull(session.currentTarget)
    }

    @Test
    fun negativeResponseKeepsCache() = runTest {
        val io = FakeElmIo(responder = { _, _ -> "7F2231" })
        val session = Elm327Session(io, FakeClock())
        session.setTarget(CanTarget.ENGINE)
        session.request("221177")
        assertEquals(CanTarget.ENGINE, session.currentTarget)
    }

    @Test
    fun writeServicesAreBlocked() = runTest {
        val io = FakeElmIo()
        val session = Elm327Session(io, FakeClock())
        for (cmd in listOf("04", "2E1177FF", "3101FF00", "1003", "2701", "1101", "14FFFFFF", "ATMA", "STSBR115200")) {
            try {
                session.request(cmd)
                fail("$cmd must be blocked")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertTrue(io.written.isEmpty())
    }

    @Test
    fun clearDtcsRestoresTimingEvenOnTimeout() = runTest {
        val io = FakeElmIo(responder = { _, _ -> "44" })
        val session = Elm327Session(io, FakeClock())
        io.timeoutOnce.add("04")
        try {
            session.clearDtcsSafely()
            fail("expected timeout")
        } catch (e: ElmTimeoutException) {
            // expected
        }
        assertEquals(
            listOf("ATSH7E0", "ATCRA7E8", "ATAT0", "ATSTFF", "04", "ATST32", "ATAT1"),
            io.written,
        )
        assertNull(session.currentTarget)
    }

    @Test
    fun clearDtcsHappyPath() = runTest {
        val io = FakeElmIo(responder = { _, cmd -> if (cmd == "04") "44" else "NO DATA" })
        val session = Elm327Session(io, FakeClock())
        session.setTarget(CanTarget.ENGINE)
        val r = session.clearDtcsSafely()
        assertTrue(r is ElmResponse.Data)
        assertEquals(listOf("ATSH7E0", "ATCRA7E8", "ATAT0", "ATSTFF", "04", "ATST32", "ATAT1"), io.written)
    }
}
