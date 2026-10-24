package com.rx8.dash.core.poll

import com.rx8.dash.core.CanTarget
import com.rx8.dash.core.Metric
import com.rx8.dash.core.elm.Elm327Session
import com.rx8.dash.core.elm.FakeClock
import com.rx8.dash.core.elm.FakeElmIo
import com.rx8.dash.core.elm.SentRequest
import com.rx8.dash.core.fuel.FuelConfig
import com.rx8.dash.core.fuel.FuelModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollerTest {

    private class Rig(io: FakeElmIo, config: PollerConfig = PollerConfig()) {
        val clock = FakeClock(1_000_000L)
        val session = Elm327Session(io, clock)
        val poller = Poller(session, clock, config, FuelModel(FuelConfig()))
    }

    /** Collapses consecutive equal headers; contiguous grouping means no header appears twice. */
    private fun headerRuns(requests: List<SentRequest>): List<String> {
        val runs = ArrayList<String>()
        for (r in requests) if (runs.isEmpty() || runs.last() != r.header) runs.add(r.header)
        return runs
    }

    private fun assertAtshPairedWithAtcra(written: List<String>) {
        val rxFor = mapOf("7E0" to "7E8", "7DF" to "7E8", "751" to "759")
        for ((i, cmd) in written.withIndex()) {
            if (cmd.startsWith("ATSH")) {
                val tx = cmd.removePrefix("ATSH")
                assertTrue("ATSH at $i not followed by ATCRA", i + 1 < written.size)
                assertEquals("ATCRA${rxFor[tx]}", written[i + 1])
            }
            if (cmd.startsWith("ATCRA")) {
                assertTrue("ATCRA at $i without preceding ATSH", i > 0 && written[i - 1].startsWith("ATSH"))
            }
        }
    }

    @Test
    fun cycleGroupsTargetsContiguouslyAndRestoresEngineAfterTpms() = runTest {
        val io = FakeElmIo(responder = FakeElmIo.rx8Responder())
        val rig = Rig(io, PollerConfig(tpmsEnabled = true, extendedEnabled = true, slowIntervalMs = 1000))
        rig.session.initialize()
        rig.poller.prepare()

        repeat(30) { n ->
            val before = io.requests.size
            val snap = rig.poller.cycle()
            val cycleRequests = io.requests.subList(before, io.requests.size)
            val runs = headerRuns(cycleRequests)
            assertEquals("cycle $n alternated headers: $runs", runs.toSet().size, runs.size)
            assertEquals(runs.map { h -> rig.poller.plan.first { it.tx == h } }, rig.poller.plan)
            // Every cycle ends on the main (engine) target.
            assertEquals(CanTarget.ENGINE, rig.session.currentTarget)
            if (CanTarget.TPMS in rig.poller.plan) {
                assertEquals(listOf(CanTarget.TPMS, CanTarget.ENGINE), rig.poller.plan)
                assertTrue(cycleRequests.filter { it.header == "751" }.all { it.filter == "759" })
            }
            assertTrue(cycleRequests.filter { it.header == "7E0" }.all { it.filter == "7E8" })
            assertEquals(1726.0, snap[Metric.RPM]!!, 1e-9)
            rig.clock.now += 300
        }
        assertAtshPairedWithAtcra(io.written)
        // TPMS appeared, and the oil-pressure placeholder DID was never sent.
        assertTrue(io.requests.any { it.header == "751" && it.command == "22C901" })
        assertFalse(io.requests.any { it.command == "220000" })
    }

    @Test
    fun tpmsValuesAreDecodedAndLowConfidence() = runTest {
        val io = FakeElmIo(responder = FakeElmIo.rx8Responder())
        val rig = Rig(io, PollerConfig(tpmsEnabled = true))
        rig.session.initialize()
        rig.poller.prepare()
        val snap = rig.poller.cycle()
        assertEquals(0x90 * 1.373 * 0.145037738, snap[Metric.TPMS_FL]!!, 1e-9)
        assertTrue(Metric.TPMS_FL in snap.lowConfidence)
        assertTrue(Metric.TPMS_FL in snap.supported)
    }

    @Test
    fun headerReassertedWithAtcraAfterTimeout() = runTest {
        val io = FakeElmIo(responder = FakeElmIo.rx8Responder())
        val rig = Rig(io)
        rig.session.initialize()
        rig.poller.prepare()
        rig.poller.cycle()
        val atshBefore = io.written.count { it.startsWith("ATSH") }
        io.timeoutOnce.add("010D")
        rig.clock.now += 100
        rig.poller.cycle()
        val atshAfter = io.written.count { it.startsWith("ATSH") }
        assertEquals("cache invalidated by timeout must re-send ATSH once", atshBefore + 1, atshAfter)
        assertAtshPairedWithAtcra(io.written)
        assertEquals(listOf("7E0"), headerRuns(io.requests))
    }

    @Test
    fun cloneNeverGetsMultiPid() = runTest {
        val io = FakeElmIo(identity = "ELM327 v1.5", sti = "?", responder = FakeElmIo.rx8Responder())
        val rig = Rig(io)
        val info = rig.session.initialize()
        assertFalse(info.multiPidAllowed)
        rig.poller.prepare()
        repeat(10) {
            rig.poller.cycle()
            rig.clock.now += 600
        }
        assertFalse(rig.poller.multiPid)
        assertFalse(rig.session.adapterInfo!!.multiPidAllowed)
        assertTrue(io.requests.none { it.command.startsWith("01") && it.command.length > 4 })
    }

    @Test
    fun genuineStnPassingSelfTestGetsMultiPid() = runTest {
        val io = FakeElmIo(identity = "ELM327 v1.4b", sti = "STN1110 v4.2.1", responder = FakeElmIo.rx8Responder())
        val rig = Rig(io)
        rig.session.initialize()
        rig.poller.prepare()
        assertTrue(rig.poller.multiPid)
        assertTrue(rig.session.adapterInfo!!.multiPidAllowed)
    }

    @Test
    fun stnFailingSelfTestStaysSinglePid() = runTest {
        val base = FakeElmIo.rx8Responder()
        val io = FakeElmIo(identity = "ELM327 v1.4b", sti = "STN1110 v4.2.1") { h, c ->
            if (c == "010C0D") "410C1AF8" else base(h, c)
        }
        val rig = Rig(io)
        rig.session.initialize()
        rig.poller.prepare()
        assertFalse(rig.poller.multiPid)
    }

    @Test
    fun mode01FallsBackToFunctionalWhenPhysicalIsSilent() = runTest {
        val io = FakeElmIo(responder = FakeElmIo.rx8Responder(engineHeader = "7DF"))
        val rig = Rig(io)
        rig.session.initialize()
        assertFalse(rig.poller.verifyMode01Physical())
        assertEquals(CanTarget.FUNCTIONAL, rig.poller.mode01Target)
        rig.clock.now += 100
        val snap = rig.poller.cycle()
        assertEquals(listOf(CanTarget.FUNCTIONAL), rig.poller.plan)
        assertEquals(1726.0, snap[Metric.RPM]!!, 1e-9)
        // Only the probe went to 7E0; every Mode 01 poll after it is functional.
        val physicalMode01 = io.requests.filter { it.header == "7E0" && it.command.startsWith("01") }
        assertEquals(listOf("0100"), physicalMode01.map { it.command })
        assertTrue(io.requests.filter { it.header == "7DF" }.all { it.filter == "7E8" })
    }

    @Test
    fun physicalModeIsKeptWhenEngineAnswers() = runTest {
        val io = FakeElmIo(responder = FakeElmIo.rx8Responder())
        val rig = Rig(io)
        rig.session.initialize()
        assertTrue(rig.poller.verifyMode01Physical())
        assertEquals(CanTarget.ENGINE, rig.poller.mode01Target)
        assertTrue(io.requests.none { it.header == "7DF" })
    }

    @Test
    fun fuelMetricsAreAddedWhenMafIsPresent() = runTest {
        val io = FakeElmIo(responder = FakeElmIo.rx8Responder())
        val rig = Rig(io)
        rig.session.initialize()
        rig.poller.prepare()
        var snap = rig.poller.cycle()
        repeat(10) {
            rig.clock.now += 600
            snap = rig.poller.cycle()
        }
        assertTrue(Metric.FUEL_FLOW in snap.supported)
        val maf = snap[Metric.MAF]!!
        // Closed loop (PID 03 = 2): L/h = MAF * 3600 / (14.7 * 740), i.e. fuel mass converted to volume.
        assertEquals(maf * 3600.0 / 10878.0, snap[Metric.FUEL_FLOW]!!, 0.01)
        assertTrue(Metric.ECONOMY in snap.values)
    }
}
