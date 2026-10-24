package com.rx8.dash.core.sim

import com.rx8.dash.core.AdapterInfo
import com.rx8.dash.core.Clock
import com.rx8.dash.core.ConnectionState
import com.rx8.dash.core.ConnectionStatus
import com.rx8.dash.core.Metric
import com.rx8.dash.core.TelemetrySnapshot
import com.rx8.dash.core.TelemetrySource
import com.rx8.dash.core.fuel.FuelModel
import com.rx8.dash.core.fuel.FuelPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Demo-mode telemetry: key-on -> cold start (fast idle) -> gentle warm-up driving under 4000 rpm ->
 * warm drive loop with city stops, cruising and occasional high-rpm pulls. Simulated time advances a fixed
 * [TICK_MS] per tick, so the model is deterministic for a given seed; timestamps come from [clock].
 */
class Simulator(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val fuel: FuelModel,
    private val seed: Int = 8,
) : TelemetrySource {

    private val _telemetry = MutableStateFlow(TelemetrySnapshot.EMPTY)
    override val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    private val _status = MutableStateFlow(ConnectionStatus(ConnectionState.DISCONNECTED))
    override val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private var job: Job? = null

    override suspend fun start() {
        if (job?.isActive == true) return
        val model = Model(Random(seed))
        _status.value = ConnectionStatus(
            state = ConnectionState.CONNECTED,
            message = "Simulator",
            adapter = AdapterInfo("Simulator", null, isGenuineStn = false, multiPidAllowed = false),
            pollHz = 1000.0 / TICK_MS,
        )
        job = scope.launch {
            while (currentCoroutineContext().isActive) {
                model.step(TICK_MS / 1000.0)
                _telemetry.value = model.snapshot(clock.nowMs())
                delay(TICK_MS)
            }
        }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        _status.value = ConnectionStatus(ConnectionState.DISCONNECTED)
        _telemetry.value = TelemetrySnapshot.EMPTY
    }

    /** One leg of the drive script: hold/approach [speedKmh] for [seconds]; [aggressive] = high shift points. */
    private class Leg(val seconds: Double, val speedKmh: Double, val aggressive: Boolean = false)

    private inner class Model(private val rnd: Random) {
        private val warmup = listOf(
            Leg(4.0, 0.0),            // key on, engine off
            Leg(25.0, 0.0),           // cold idle
            Leg(30.0, 40.0), Leg(20.0, 0.0), Leg(40.0, 55.0), Leg(15.0, 30.0), Leg(30.0, 60.0), Leg(15.0, 0.0),
        )
        private val drive = listOf(
            Leg(25.0, 50.0), Leg(12.0, 0.0), Leg(20.0, 70.0, aggressive = true), Leg(30.0, 100.0),
            Leg(40.0, 110.0), Leg(15.0, 60.0), Leg(10.0, 130.0, aggressive = true), Leg(20.0, 80.0),
            Leg(20.0, 0.0), Leg(30.0, 45.0),
        )

        private val ratios = doubleArrayOf(3.760, 2.269, 1.645, 1.187, 1.000, 0.843)
        private val finalDrive = 4.444
        private val tyreCircM = 2.07

        private var t = 0.0
        private var legIndex = 0
        private var legT = 0.0
        private var inWarmup = true
        private var running = false

        private var speed = 0.0       // km/h
        private var rpm = 0.0
        private var gear = 1
        private var throttle = 0.0    // %
        private var coolant = 14.0
        private var iat = 18.0
        private var ltft = 2.3
        private var stft = 0.0
        private var lambdaCmd = 1.0
        private var lambdaMeas = 1.0
        private var fuelStatus = 1
        private var load = 0.0
        private var maf = 0.0
        private var volts = 12.5
        private var timing = 0.0

        private fun currentLeg(): Leg = if (inWarmup) warmup[legIndex] else drive[legIndex]

        fun step(dt: Double) {
            t += dt
            legT += dt
            val legs = if (inWarmup) warmup else drive
            if (legT >= legs[legIndex].seconds) {
                legT = 0.0
                legIndex++
                if (legIndex >= legs.size) {
                    legIndex = 0
                    // Leave the warm-up script only once coolant is ready; otherwise repeat gentle driving.
                    if (inWarmup && coolant >= 82.0) inWarmup = false else if (inWarmup) legIndex = 2
                }
            }
            val leg = currentLeg()
            if (!running && t >= 4.0) running = true

            // Longitudinal: approach the leg's target speed.
            val warm = coolant >= 82.0
            val accelLimit = if (leg.aggressive && warm) 4.5 else if (warm) 2.2 else 1.4 // m/s²
            val err = leg.speedKmh - speed
            val accel = when {
                !running -> 0.0
                err > 1.0 -> min(accelLimit, err / 3.6 * 0.6)
                err < -1.0 -> max(-3.5, err / 3.6 * 0.6)
                else -> 0.0
            }
            speed = (speed + accel * 3.6 * dt).coerceIn(0.0, 240.0)
            if (leg.speedKmh == 0.0 && speed < 2.0) speed = 0.0

            // Gearbox: upshift at a shift point that respects warm-up, downshift below ~2000 rpm.
            val shiftRpm = when {
                !warm -> 3300.0
                leg.aggressive -> 8300.0
                else -> 3800.0
            }
            if (speed < 3.0) {
                gear = 1
            } else {
                val wheelRpm = speed / 3.6 / tyreCircM * 60.0
                if (wheelRpm * ratios[gear - 1] * finalDrive > shiftRpm && gear < 6) gear++
                if (gear > 1 && wheelRpm * ratios[gear - 1] * finalDrive < 1800.0 && !(err > 1.0 && gear == 2)) gear--
            }

            // Engine speed.
            val idle = if (coolant < 40.0) 1500.0 else if (coolant < 70.0) 1100.0 else 800.0
            val targetRpm = when {
                !running -> 0.0
                speed < 3.0 -> idle + if (accel > 0) 900.0 else 0.0
                else -> max(idle, speed / 3.6 / tyreCircM * 60.0 * ratios[gear - 1] * finalDrive)
            }
            rpm += (targetRpm - rpm) * min(1.0, dt * 6.0)
            rpm = (rpm + if (running) rnd.nextDouble(-15.0, 15.0) else 0.0).coerceIn(0.0, 9000.0)
            if (!running) rpm = 0.0

            // Driver/engine load.
            val cruiseThrottle = 4.0 + speed * 0.12
            val wantThrottle = when {
                !running -> 0.0
                accel > 0.0 -> min(100.0, cruiseThrottle + accel / accelLimit * (if (leg.aggressive && warm) 90.0 else 40.0))
                accel < 0.0 -> 0.0
                speed < 3.0 -> 0.0
                else -> cruiseThrottle
            }
            throttle += (wantThrottle - throttle) * min(1.0, dt * 5.0)
            load = if (!running) 0.0 else (18.0 + throttle * 0.8).coerceIn(0.0, 100.0)

            // Air mass: ~210 g/s at 9000 rpm WOT, ~3.5 g/s at idle.
            maf = if (!running) 0.0 else 0.0233 * rpm * (0.15 + 0.85 * load / 100.0)

            // Fuelling: open loop while cold or under load (Renesis runs rich for apex-seal cooling).
            fuelStatus = when {
                !running -> 0
                coolant < 40.0 -> 1
                throttle > 70.0 || rpm > 5500.0 -> 4
                else -> 2
            }
            val targetLambda = when (fuelStatus) {
                4 -> if (rpm > 7250) 0.78 else if (rpm > 5500) 0.82 else 0.86
                1 -> 0.92
                else -> 1.0
            }
            lambdaCmd += (targetLambda - lambdaCmd) * min(1.0, dt * 4.0)
            lambdaMeas = (lambdaCmd + rnd.nextDouble(-0.02, 0.02)).coerceIn(0.6, 1.4)
            stft = if (fuelStatus == 2) (stft * 0.8 + rnd.nextDouble(-3.0, 3.0) * 0.2) else 0.0

            // Thermal model: coolant climbs with work, thermostat holds ~88 °C; heat soak on IAT at low speed.
            if (running) {
                val heat = 0.10 + rpm / 9000.0 * 0.9 + load / 100.0 * 0.6
                coolant += heat * dt * 0.9
                if (coolant > 88.0) coolant -= (coolant - 88.0) * min(1.0, dt * 0.5)
            } else {
                coolant -= (coolant - 14.0) * dt * 0.001
            }
            val iatTarget = 18.0 + if (speed < 20.0 && running) 14.0 else 4.0
            iat += (iatTarget - iat) * dt * 0.02

            volts = if (running) 14.1 + rnd.nextDouble(-0.1, 0.1) else 12.5 + rnd.nextDouble(-0.05, 0.05)
            timing = if (!running) 0.0 else (5.0 + rpm / 9000.0 * 25.0 - load / 100.0 * 8.0).coerceIn(-5.0, 40.0)
        }

        fun snapshot(nowMs: Long): TelemetrySnapshot {
            val values = LinkedHashMap<Metric, Double>()
            values[Metric.RPM] = rpm
            values[Metric.SPEED] = speed
            values[Metric.THROTTLE] = throttle
            values[Metric.COOLANT] = coolant
            values[Metric.INTAKE_AIR] = iat
            values[Metric.LOAD] = load
            values[Metric.STFT] = stft
            values[Metric.LTFT] = ltft
            values[Metric.MAF] = maf
            values[Metric.VOLTAGE] = volts
            values[Metric.FUEL_SYSTEM_STATUS] = fuelStatus.toDouble()
            values[Metric.COMMANDED_LAMBDA] = lambdaCmd
            values[Metric.WIDEBAND_LAMBDA] = lambdaMeas
            values[Metric.TIMING_ADVANCE] = timing

            val lowConfidence = HashSet<Metric>()
            val estimate = fuel.estimate(maf, fuelStatus, lambdaCmd, rpm)
            if (estimate.path != FuelPath.NO_MAF) {
                values[Metric.FUEL_FLOW] = estimate.litresPerHour
                val economy = fuel.litresPer100Km(estimate.litresPerHour, speed)
                if (economy != null) values[Metric.ECONOMY] = economy
                if (estimate.lowConfidence) {
                    lowConfidence.add(Metric.FUEL_FLOW)
                    lowConfidence.add(Metric.ECONOMY)
                }
            }
            return TelemetrySnapshot(nowMs, values, SUPPORTED, lowConfidence)
        }
    }

    companion object {
        const val TICK_MS = 100L

        val SUPPORTED: Set<Metric> = setOf(
            Metric.RPM, Metric.SPEED, Metric.THROTTLE, Metric.COOLANT, Metric.INTAKE_AIR, Metric.LOAD,
            Metric.STFT, Metric.LTFT, Metric.MAF, Metric.VOLTAGE, Metric.FUEL_SYSTEM_STATUS,
            Metric.COMMANDED_LAMBDA, Metric.WIDEBAND_LAMBDA, Metric.TIMING_ADVANCE,
            Metric.FUEL_FLOW, Metric.ECONOMY,
        )
    }
}
