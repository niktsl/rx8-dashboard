package com.rx8.dash.core.poll

import com.rx8.dash.core.CanTarget
import com.rx8.dash.core.Clock
import com.rx8.dash.core.ElmErrorKind
import com.rx8.dash.core.ElmResponse
import com.rx8.dash.core.ElmTimeoutException
import com.rx8.dash.core.Metric
import com.rx8.dash.core.TelemetrySnapshot
import com.rx8.dash.core.elm.Elm327Session
import com.rx8.dash.core.fuel.FuelModel
import com.rx8.dash.core.fuel.FuelPath
import com.rx8.dash.core.pid.PidDef
import com.rx8.dash.core.pid.PidRegistry
import com.rx8.dash.core.pid.SupportedPids
import com.rx8.dash.core.pid.Tier
import kotlin.math.abs
import kotlin.math.max

/**
 * Header-grouped scheduler. Within one [cycle] every request for a given [CanTarget] is issued
 * contiguously, so ATSH/ATCRA change at most once per group. Order per cycle:
 * TPMS batch (slow, opt-in) -> ENGINE Mode 22 batch (only when Mode 01 runs functional) -> main Mode 01 group.
 * The cycle therefore always ends on the main target, which restores ENGINE after a TPMS batch.
 *
 * Not thread-safe; driven from LiveTelemetrySource's single-threaded loop.
 */
class Poller(
    private val session: Elm327Session,
    private val clock: Clock,
    private val config: PollerConfig,
    private val fuel: FuelModel,
) {
    /** Target used for Mode 01: ENGINE (physical) unless [verifyMode01Physical] fell back to FUNCTIONAL. */
    var mode01Target: CanTarget = CanTarget.ENGINE
        private set

    /** Targets used by the last [cycle], in order (one entry per contiguous group). */
    var plan: List<CanTarget> = emptyList()
        private set

    /** Mode 01 PIDs reported by the bitmaps; empty when [bitmapKnown] is false. */
    var supportedPids: Set<Int> = emptySet()
        private set

    var bitmapKnown: Boolean = false
        private set

    /** True only for a genuine STN adapter that passed the self-test. */
    var multiPid: Boolean = false
        private set

    private val latest = LinkedHashMap<Metric, Double>()
    private val latestAtMs = HashMap<Metric, Long>()
    private val lastPolledMs = HashMap<PidDef, Long>()
    private val failures = HashMap<PidDef, Int>()
    private val disabled = HashSet<PidDef>()
    private val extendedSeen = HashSet<Metric>()
    private var lastSlowMs: Long? = null
    private var lastProbeMs: Long = 0L
    private var cycleCount: Long = 0L
    private var consecutiveTimeouts = 0

    // Per-cycle bookkeeping.
    private var cycleDataCount = 0
    private val cycleFailures = ArrayList<Pair<PidDef, Boolean>>()

    suspend fun prepare() {
        verifyMode01Physical()
        if (bitmapKnown) readExtraBitmaps()
        val info = session.adapterInfo
        multiPid = if (info != null && info.isGenuineStn) runMultiPidSelfTest() else false
        session.setMultiPidAllowed(multiPid)
    }

    /**
     * Sends 0100 on ENGINE (7E0/7E8). If that yields no answer, retries on FUNCTIONAL (7DF/7E8) and, if it
     * answers there, runs all Mode 01 traffic functionally. Mode 22 always stays physical on ENGINE.
     * @return true when physical addressing works.
     */
    suspend fun verifyMode01Physical(): Boolean {
        lastProbeMs = clock.nowMs()
        val physical = probe0100(CanTarget.ENGINE)
        if (physical != null) {
            mode01Target = CanTarget.ENGINE
            applyBitmap0(physical)
            return true
        }
        val functional = probe0100(CanTarget.FUNCTIONAL)
        if (functional != null) {
            mode01Target = CanTarget.FUNCTIONAL
            applyBitmap0(functional)
            return false
        }
        mode01Target = CanTarget.ENGINE
        bitmapKnown = false
        supportedPids = emptySet()
        return false
    }

    suspend fun cycle(): TelemetrySnapshot {
        cycleCount++
        cycleDataCount = 0
        cycleFailures.clear()
        val now = clock.nowMs()

        // No ECU answered at prepare time (e.g. ignition off): re-probe periodically instead of polling blind.
        if (!bitmapKnown && now - lastProbeMs >= REPROBE_INTERVAL_MS) {
            val used = ArrayList<CanTarget>()
            used.add(CanTarget.ENGINE)
            val physicalOk = verifyMode01Physical()
            if (!physicalOk) used.add(CanTarget.FUNCTIONAL)
            if (bitmapKnown) readExtraBitmaps()
            plan = used
            return snapshot()
        }

        val groups = buildGroups(now)
        val used = ArrayList<CanTarget>()
        for ((target, defs) in groups) {
            if (defs.isEmpty()) continue
            used.add(target)
            runGroup(target, defs)
        }
        plan = used

        // Restore the main target if the cycle ended elsewhere (only possible when the main group was empty).
        if (session.currentTarget != mode01Target && used.isNotEmpty() && used.last() != mode01Target) {
            session.setTarget(mode01Target)
        }

        // Only count "no data" against a PID when the ECU is otherwise answering (ignition-off safety).
        if (cycleDataCount > 0) {
            for ((def, immediate) in cycleFailures) registerFailure(def, immediate)
        }
        return snapshot()
    }

    // ---------------------------------------------------------------- scheduling

    private fun buildGroups(now: Long): LinkedHashMap<CanTarget, List<PidDef>> {
        val slowDue = lastSlowMs.let { it == null || now - it >= config.slowIntervalMs }
        val groups = LinkedHashMap<CanTarget, List<PidDef>>()

        val tpms = if (slowDue && config.tpmsEnabled) {
            PidRegistry.extended.filter { it.target == CanTarget.TPMS && isActive(it) }
        } else {
            emptyList()
        }
        val engineExt = if (slowDue && config.extendedEnabled) {
            PidRegistry.extended.filter { it.target == CanTarget.ENGINE && isActive(it) }
        } else {
            emptyList()
        }
        if (slowDue) lastSlowMs = now

        val pollFast = config.fastEveryCycle || cycleCount % 2L == 1L
        val active = activeStandard()
        val fast = if (pollFast) active.filter { it.tier == Tier.FAST } else emptyList()
        val mediumDue = active
            .filter { it.tier != Tier.FAST }
            .filter { def -> lastPolledMs[def].let { it == null || now - it >= config.mediumIntervalMs } }
            .sortedBy { lastPolledMs[it] ?: Long.MIN_VALUE }
        val medium = if (multiPid) mediumDue else mediumDue.take(MAX_MEDIUM_PER_CYCLE)

        if (tpms.isNotEmpty()) groups[CanTarget.TPMS] = tpms
        val main = ArrayList<PidDef>()
        main.addAll(fast)
        main.addAll(medium)
        if (mode01Target == CanTarget.ENGINE) {
            main.addAll(engineExt)
        } else if (engineExt.isNotEmpty()) {
            groups[CanTarget.ENGINE] = engineExt
        }
        groups[mode01Target] = main
        return groups
    }

    private fun activeStandard(): List<PidDef> {
        val result = ArrayList<PidDef>()
        val seen = HashSet<Metric>()
        for (def in PidRegistry.standard) {
            if (def in disabled) continue
            if (bitmapKnown && def.pid !in supportedPids) continue
            if (!seen.add(def.metric)) continue // e.g. wideband 0x24 preferred over 0x34
            result.add(def)
        }
        return result
    }

    private fun isActive(def: PidDef): Boolean = def !in disabled && PidRegistry.isPollable(def)

    private suspend fun runGroup(target: CanTarget, defs: List<PidDef>) {
        if (multiPid && target == mode01Target) {
            val mode01 = defs.filter { it.mode == 0x01 }
            for (chunk in mode01.chunked(MULTI_PID_CHUNK)) pollMulti(target, chunk)
            for (def in defs.filter { it.mode != 0x01 }) pollSingle(target, def)
        } else {
            for (def in defs) pollSingle(target, def)
        }
    }

    // ---------------------------------------------------------------- requests

    private suspend fun send(target: CanTarget, command: String, timeoutMs: Long): ElmResponse? {
        return try {
            // setTarget is a no-op while the cache is valid; after a timeout it re-sends ATSH+ATCRA.
            session.setTarget(target)
            val response = session.request(command, timeoutMs)
            consecutiveTimeouts = 0
            response
        } catch (e: ElmTimeoutException) {
            consecutiveTimeouts++
            if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                consecutiveTimeouts = 0
                throw e
            }
            null
        }
    }

    private suspend fun pollSingle(target: CanTarget, def: PidDef) {
        val timeout = if (def.mode == 0x22) EXTENDED_TIMEOUT_MS else REQUEST_TIMEOUT_MS
        val response = send(target, def.command(), timeout)
        lastPolledMs[def] = clock.nowMs()
        when (response) {
            is ElmResponse.Data -> {
                val value = def.decodeResponse(response.first)
                if (value != null) {
                    accept(def, value)
                } else {
                    cycleFailures.add(def to false)
                }
            }
            is ElmResponse.Error -> {
                val immediate = def.mode == 0x22 && response.kind == ElmErrorKind.NEGATIVE_RESPONSE
                cycleFailures.add(def to immediate)
            }
            ElmResponse.NoData, ElmResponse.Unsupported -> cycleFailures.add(def to false)
            null -> Unit // timeout: not the PID's fault
        }
    }

    private suspend fun pollMulti(target: CanTarget, defs: List<PidDef>) {
        if (defs.size == 1) {
            pollSingle(target, defs[0])
            return
        }
        val command = buildString {
            append("01")
            for (d in defs) append("%02X".format(d.pid))
        }
        val response = send(target, command, REQUEST_TIMEOUT_MS * 2)
        val now = clock.nowMs()
        for (d in defs) lastPolledMs[d] = now
        if (response !is ElmResponse.Data) return
        val values = decodeMulti(response.first, defs)
        for (d in defs) {
            val v = values[d]
            if (v != null) accept(d, v)
        }
    }

    private fun accept(def: PidDef, value: Double) {
        cycleDataCount++
        failures.remove(def)
        latest[def.metric] = value
        latestAtMs[def.metric] = clock.nowMs()
        if (def.mode == 0x22) extendedSeen.add(def.metric)
    }

    private fun registerFailure(def: PidDef, immediate: Boolean) {
        val n = (failures[def] ?: 0) + 1
        failures[def] = n
        if (immediate || n >= MAX_FAILURES) {
            disabled.add(def)
            failures.remove(def)
            val stillProvided = (activeStandard() + PidRegistry.extended.filter { isActive(it) })
                .any { it.metric == def.metric }
            if (!stillProvided) {
                latest.remove(def.metric)
                extendedSeen.remove(def.metric)
            }
        }
    }

    // ---------------------------------------------------------------- prepare helpers

    private suspend fun probe0100(target: CanTarget): IntArray? {
        val response = try {
            session.setTarget(target)
            session.request("0100", PROBE_TIMEOUT_MS)
        } catch (e: ElmTimeoutException) {
            null
        }
        if (response !is ElmResponse.Data) return null
        val p = response.first
        if (p.size < 6 || p[0] != 0x41 || p[1] != 0x00) return null
        return p
    }

    private fun applyBitmap0(payload: IntArray) {
        supportedPids = SupportedPids.decode(mapOf(0x00 to payload))
        bitmapKnown = true
    }

    private suspend fun readExtraBitmaps() {
        val responses = HashMap<Int, IntArray>()
        var base = 0x00
        while (base < 0x60 && (base + 0x20) in supportedPids) {
            val next = base + 0x20
            val response = send(mode01Target, "01%02X".format(next), PROBE_TIMEOUT_MS)
            if (response !is ElmResponse.Data) break
            val p = response.first
            if (p.size < 6 || p[0] != 0x41 || p[1] != next) break
            responses[next] = p
            supportedPids = supportedPids + SupportedPids.decode(responses)
            base = next
        }
    }

    /** Compares single-PID reads of 0C and 0D with one "010C0D" multi-PID read. */
    private suspend fun runMultiPidSelfTest(): Boolean {
        val rpmDef = PidRegistry.standard.first { it.pid == 0x0C }
        val speedDef = PidRegistry.standard.first { it.pid == 0x0D }
        val rpmResp = send(mode01Target, rpmDef.command(), REQUEST_TIMEOUT_MS) as? ElmResponse.Data ?: return false
        val speedResp = send(mode01Target, speedDef.command(), REQUEST_TIMEOUT_MS) as? ElmResponse.Data ?: return false
        val rpm = rpmDef.decodeResponse(rpmResp.first) ?: return false
        val speed = speedDef.decodeResponse(speedResp.first) ?: return false
        val multiResp = send(mode01Target, "010C0D", REQUEST_TIMEOUT_MS * 2) as? ElmResponse.Data ?: return false
        val values = decodeMulti(multiResp.first, listOf(rpmDef, speedDef))
        val mRpm = values[rpmDef] ?: return false
        val mSpeed = values[speedDef] ?: return false
        return abs(mRpm - rpm) <= max(300.0, rpm * 0.15) && abs(mSpeed - speed) <= 3.0
    }

    /** Walks "41 pidA dataA pidB dataB ..." using each PID's declared length. */
    private fun decodeMulti(payload: IntArray, defs: List<PidDef>): Map<PidDef, Double> {
        val result = HashMap<PidDef, Double>()
        if (payload.isEmpty() || payload[0] != 0x41) return result
        var i = 1
        while (i < payload.size) {
            val pid = payload[i]
            val def = defs.firstOrNull { it.pid == pid } ?: break
            val end = i + 1 + def.responseBytes
            if (end > payload.size) break
            val v = def.decode(payload.copyOfRange(i + 1, end))
            if (!v.isNaN() && !v.isInfinite()) result[def] = v
            i = end
        }
        return result
    }

    // ---------------------------------------------------------------- snapshot

    private fun supportedMetrics(): Set<Metric> {
        val set = LinkedHashSet<Metric>()
        for (d in activeStandard()) set.add(d.metric)
        set.addAll(extendedSeen)
        if (Metric.MAF in set) {
            set.add(Metric.FUEL_FLOW)
            set.add(Metric.ECONOMY)
        }
        return set
    }

    private fun snapshot(): TelemetrySnapshot {
        // Drop values that stopped updating (ECU silent, ignition off) so the dash never shows frozen data.
        val now = clock.nowMs()
        val slowStale = config.slowIntervalMs * 3 + STALE_MS
        val extendedMetrics = PidRegistry.extended.map { it.metric }.toSet()
        val iterator = latest.entries.iterator()
        while (iterator.hasNext()) {
            val metric = iterator.next().key
            val limit = if (metric in extendedMetrics) slowStale else STALE_MS
            val at = latestAtMs[metric] ?: Long.MIN_VALUE
            if (at == Long.MIN_VALUE || now - at > limit) {
                iterator.remove()
                latestAtMs.remove(metric)
            }
        }
        val values = LinkedHashMap<Metric, Double>(latest)
        val lowConfidence = HashSet<Metric>()
        for (d in PidRegistry.extended) {
            if (!d.validated && d.metric in values) lowConfidence.add(d.metric)
        }

        val maf = values[Metric.MAF]
        val lambda = if (bitmapKnown && 0x44 !in supportedPids) null else values[Metric.COMMANDED_LAMBDA]
        val estimate = fuel.estimate(
            maf,
            values[Metric.FUEL_SYSTEM_STATUS]?.toInt(),
            lambda,
            values[Metric.RPM],
        )
        if (maf != null && estimate.path != FuelPath.NO_MAF) {
            values[Metric.FUEL_FLOW] = estimate.litresPerHour
            val economy = fuel.litresPer100Km(estimate.litresPerHour, values[Metric.SPEED])
            if (economy != null) values[Metric.ECONOMY] = economy
            if (estimate.lowConfidence) {
                lowConfidence.add(Metric.FUEL_FLOW)
                lowConfidence.add(Metric.ECONOMY)
            }
        }
        return TelemetrySnapshot(
            timestampMs = clock.nowMs(),
            values = values,
            supported = supportedMetrics(),
            lowConfidence = lowConfidence,
        )
    }

    companion object {
        const val REQUEST_TIMEOUT_MS = 1000L
        const val EXTENDED_TIMEOUT_MS = 1500L
        const val PROBE_TIMEOUT_MS = 5000L
        const val REPROBE_INTERVAL_MS = 10_000L
        const val STALE_MS = 10_000L
        const val MAX_MEDIUM_PER_CYCLE = 3
        const val MULTI_PID_CHUNK = 6
        const val MAX_FAILURES = 3
        const val MAX_CONSECUTIVE_TIMEOUTS = 3
    }
}
