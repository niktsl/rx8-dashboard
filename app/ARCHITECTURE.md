# RX-8 Dash — Architecture & cross-module contracts

The design is set by the plan in `/home/nick/.claude/plans/can-we-create-a-wild-mist.md`. Shared types live in
`core/src/main/kotlin/com/rx8/dash/core/Contracts.kt`. **Do not change public signatures listed here without updating
every user.** Nothing compiles locally (no JDK); CI is the only compiler, so write conservative, idiomatic Kotlin 2.1
against the pinned versions in `gradle/libs.versions.toml`.

## Modules
- `:core`: pure Kotlin/JVM (no Android imports). Protocol, polling, math, rules. JUnit4 tests in `core/src/test/kotlin`.
- `:app`: Android (Compose, Room, DataStore, services). Depends on `:core`.

## :core public API (package → file → signatures)

### `com.rx8.dash.core.elm`
- `object ElmParser`
  - `fun parse(raw: String): ElmResponse`
    - Handles echo lines, `SEARCHING...`, `BUS INIT`, blank lines, `NO DATA`, `?`, `STOPPED`, `BUS ERROR`, `CAN ERROR`, `DATA ERROR`, `BUFFER FULL`, `UNABLE TO CONNECT` and negative responses `7F xx yy`.
    - Handles ISO-TP multi-frame output with ATH0/CAF1 (the `0:`/`1:` line prefixes plus the leading byte-count line).
    - The payload keeps the full service response, starting with the response SID, e.g. `41 0C 1A F8` → `[0x41,0x0C,0x1A,0xF8]`.
- `class Elm327Session(io: ElmIo, clock: Clock = SystemClock)`
  - `suspend fun initialize(): AdapterInfo`
    - Sends `ATZ` (long timeout), then `ATE0 ATL0 ATS0 ATH0 ATAT1 ATSP6`, then `ATI` and `STI`.
    - Multi-PID is only allowed when the STN self-test passes. That test runs later in the poller.
  - `val currentTarget: CanTarget?` holds the cached state. `null` means unknown.
  - `suspend fun setTarget(target: CanTarget)` sends `ATSH`/`ATCRA` only when the target differs from the cache.
  - `suspend fun request(command: String, timeoutMs: Long = 1000): ElmResponse`
    - On a timeout, IO error, `?`, BUS/CAN error or `STOPPED`, it calls `invalidateState()`.
    - Timeouts and IO errors are rethrown as `ElmTimeoutException`/`ElmIoException`.
  - `fun invalidateState()`
  - `suspend fun clearDtcsSafely(): ElmResponse` runs `ATAT0`, `ATST FF`, `04` with a 10 s timeout, then `ATST 32` and `ATAT1`. The adapter state is restored even on failure.
- `class BackoffSchedule(initialDelayMs: Long = 3000, stepsMs: List<Long> = listOf(2000,5000,10000,30000,60000))`
  - `fun delayForAttempt(attempt: Int): Long`. Attempt 0 returns the initial delay; past the end of the list it stays at the last step.

### `com.rx8.dash.core.pid`
- `data class PidDef`
  - Fields: `metric: Metric`, `mode: Int`, `pid: Int` (Mode 22 uses a 16-bit DID), `target: CanTarget`, `tier: Tier`, `responseBytes: Int`, `validated: Boolean`, `decode: (IntArray) -> Double`.
  - `decode` receives the data bytes *after* the SID+PID echo.
  - `fun command(): String` returns e.g. `"010C"` or `"221177"`.
- `enum class Tier { FAST, MEDIUM, SLOW }`
- `object PidRegistry`
  - `val standard: List<PidDef>`: 0C, 0D, 11, 05, 0F, 04, 06, 07, 10, 42, 03, 44, 24 or 34 (wideband λ), 0E.
  - `val extended: List<PidDef>`: Mode 22 on ENGINE and TPMS on the TPMS target. All are `validated=false` except entries flagged known-good. The oil-pressure switch is an unvalidated placeholder DID.
  - `fun forMetric(m: Metric): PidDef?`
- `object SupportedPids` with `fun decode(bitmapResponses: Map<Int, IntArray>): Set<Int>`, built from Mode 01 PIDs 00/20/40/60.

### `com.rx8.dash.core.poll`
- `data class PollerConfig`
  - `fastEveryCycle: Boolean = true`, `mediumIntervalMs: Long = 500`, `slowIntervalMs: Long = 8000`
  - `extendedEnabled: Boolean = false`, `tpmsEnabled: Boolean = false`
- `class Poller(session: Elm327Session, clock: Clock, config: PollerConfig, fuel: com.rx8.dash.core.fuel.FuelModel)`
  - `suspend fun prepare()`
    - Reads supported-PID bitmaps.
    - Runs `verifyMode01Physical()`: `0100` on ENGINE; on NoData it falls back to FUNCTIONAL for Mode 01.
    - Runs the multi-PID self-test only if the adapter is an STN.
  - `suspend fun cycle(): TelemetrySnapshot` runs one scheduling cycle with header grouping. Within a cycle, all requests for one target are contiguous.
  - `val plan: List<CanTarget>` records the targets used in the last cycle, in order, for tests.
- `class LiveTelemetrySource(ioFactory: suspend () -> ElmIo, clock: Clock, config: PollerConfig, backoff: BackoffSchedule, fuel: FuelModel, scope: CoroutineScope) : TelemetrySource, SessionAccess`
  - Runs the connect, initialize, prepare and poll loop on a single-threaded dispatcher (`Dispatchers.IO.limitedParallelism(1)`).
  - Reconnects using `backoff`.
  - Publishes `telemetry` and `status`, including the measured poll rate (`pollHz`).
- `interface SessionAccess`
  - `suspend fun <T> withSession(block: suspend (Elm327Session) -> T): T` pauses polling, runs the block exclusively, then resumes. Diagnostics use this.

### `com.rx8.dash.core.fuel`
- `data class FuelConfig`
  - `densityGPerL: Double = 740.0`, `stoich: Double = 14.7`, `calibration: Double = 1.0`, `sixPort: Boolean = true`
  - `stagingK: List<Pair<Int, Double>>`: rpm upper bounds to `k`. Default `<3750:1.05, <5500:1.12, <7250:1.20, else 1.28`.
- `data class FuelEstimate(val litresPerHour: Double, val lambdaUsed: Double, val path: FuelPath, val lowConfidence: Boolean)`
- `enum class FuelPath { CLOSED_LOOP, OPEN_LOOP_PID44, OPEN_LOOP_STAGING, NO_MAF }`
- `class FuelModel(config: FuelConfig)`
  - `fun estimate(mafGs: Double?, fuelStatus: Int?, commandedLambda: Double?, rpm: Double?): FuelEstimate`
    - L/h = MAF × 3600 / (stoich × density × λ) × calibration.
    - The staging path computes baseline(λ=1) × k.
    - `lowConfidence` is true when rpm > 5500 or the staging path is used.
  - `fun litresPer100Km(lph: Double, speedKmh: Double?): Double?` returns null below 3 km/h.
- `object FuelCalibration` with `fun fromFillUps(fills: List<FillUp>, appEstimatedLitres: List<Double>): Double`.
- `data class FillUp(val odometerKm: Double, val litres: Double, val fullTank: Boolean)`

### `com.rx8.dash.core.diag`
- `data class Dtc(val code: String, val description: String?)`, e.g. `"P0300"`.
- `object DtcDecoder`
  - `fun decode(payload: IntArray): List<Dtc>` takes the Mode 03/07/0A response including the SID. It handles the CAN count byte and ignores 0000 padding.
- `object DtcDescriptions` with `fun describe(code: String): String?`. Covers generic P0xxx/P2xxx plus RX-8/Mazda codes: misfire P0300–P0302, P0420/P0421, coolant, MAF, O2, P0480/P0481 fans, P0101/P0102/P0103, P0118, P0011/P0012, P0455/P0456, P1170, P2004, P2006/P2008, P0505, etc.
- `data class ReadinessStatus(val milOn: Boolean, val dtcCount: Int, val monitors: Map<String, Pair<Boolean /*supported*/, Boolean /*complete*/>>)`
- `class Diagnostics(access: SessionAccess)`
  - `readStored()`, `readPending()`, `readPermanent()`: `List<Dtc>`
  - `readiness()`: `ReadinessStatus`
  - `vin()`: `String?` (Mode 09 PID 02, multi-frame)
  - `freezeFrame()`: `Map<Metric, Double>`
  - `clear(rpmNow: Double?)`: `ClearResult`. It refuses unless rpm is 0 or null-confirmed, then uses `session.clearDtcsSafely()`.
  - `scanExtended(dids: List<Int>, target: CanTarget)`: `Map<Int, IntArray?>`. Read-only Mode 22.
- `sealed interface ClearResult { Cleared; RefusedEngineRunning; Failed(reason) }`

### `com.rx8.dash.core.health`
- `enum class WarmupStage { COLD /*<40 °C: idle/gentle*/, WARMING /*40–82 °C: keep <4000 rpm*/, READY /*≥82 °C*/ }`
- `data class HealthConfig`
  - Defaults: `redlineRpm: Int = 9000`, `coldRpmLimit = 4000`, `readyCoolantC = 82.0`, `minDriveCoolantC = 40.0`
  - `coolantWarnC = 105.0`, `coolantCritC = 112.0`, `lowVoltage = 12.2`, `highVoltage = 15.0`, `shortTripMinutes = 10`
- `class RotaryHealthMonitor(config: HealthConfig)`
  - `fun stage(coolantC: Double?): WarmupStage`
  - `fun evaluate(s: TelemetrySnapshot): List<Alert>`
    - Covers high rpm when cold, coolant over-temperature, voltage, near-redline, and low oil pressure (only when the metric is present).
- `class StartLog`, pure logic: `fun isShortColdTrip(durationMin: Double, maxCoolantC: Double?): Boolean` and `fun floodRisk(recentShortColdTrips: Int): AlertLevel?`.
- `object Premix` with `fun mlFor(litres: Double, mlPerLitre: Double): Double`. Presets are 2.0 for street and 4.0 for sport.

### `com.rx8.dash.core.trip`
- `class TripAccumulator(fuel: FuelModel)`
  - `fun add(s: TelemetrySnapshot)` integrates distance from speed and time, and fuel from L/h.
  - `fun summary(): TripSummary`
- `data class TripSummary`
  - Fields: `startMs`, `endMs`, `distanceKm`, `fuelLitres`, `avgSpeedKmh`, `maxSpeedKmh`, `maxRpm`, `maxCoolantC`, `avgLPer100`, `coldStart: Boolean`.
- `class AccelTimer`
  - Measures 0–100 and 60–130 km/h. `fun add(s)`, `val results: StateFlow<List<AccelRun>>`.
- `data class AccelRun(val kind: String, val seconds: Double, val atMs: Long)`

### `com.rx8.dash.core.maint`
- `data class MaintenanceItem(val id: String, val title: String, val intervalKm: Int?, val intervalDays: Int?, val lastKm: Double?, val lastDateMs: Long?, val notes: String = "")`
- `object MaintenanceDefaults`
  - `val items: List<MaintenanceItem>`
  - Defaults: oil + filter every 5,000 km; ignition coils 40,000 km; spark plugs 30,000 km; coolant 2 years / 40,000 km; air filter 20,000 km; oil-level check 1,000 km; premix/OMP check 5,000 km.
- `enum class DueState { OK, SOON, DUE, OVERDUE }`
- `object MaintenanceEvaluator`
  - `fun evaluate(item, odometerKm: Double, nowMs: Long): Pair<DueState, String>`. The String is human-readable, e.g. "in 820 km".

### `com.rx8.dash.core.sim`
- `class Simulator(clock: Clock, scope: CoroutineScope, fuel: FuelModel) : TelemetrySource`
  - A realistic drive loop: cold start → warm-up → drive with rev/speed cycles.
  - It reports all standard metrics as supported, including fuel flow and economy.

## :app packages (owners)
- `com.rx8.dash`: `App` (Application, owns `AppGraph`) and `MainActivity`.
- `com.rx8.dash.AppGraph`, manual DI, with these fields:
  - `settings: SettingsRepository`
  - `db: Rx8Database`
  - `tripRepository: TripRepository`
  - `maintenanceRepository: MaintenanceRepository`
  - `telemetryHub: TelemetryHub`
  - `mediaController: MediaPanelController`
  - `diagnostics(): Diagnostics?`, which returns non-null only while live
- `com.rx8.dash.bt`
  - `RfcommElmIo(device: BluetoothDevice) : ElmIo`. Its `connect()` must be called on IO; it tries a secure socket, then an insecure one, and calls `cancelDiscovery()` first.
  - `BluetoothDevices`, which lists bonded devices.
- `com.rx8.dash.service`
  - `ObdForegroundService` (connectedDevice type).
  - `TelemetryHub`, a singleton that holds the active `TelemetrySource` (live or simulator) and exposes merged `StateFlow`s plus `alerts: StateFlow<List<Alert>>`. Trip recording runs here too.
  - `ServiceStarter.tryStart(context): Boolean`. It catches `ForegroundServiceStartNotAllowedException` and posts a "tap to connect" `getActivity` notification.
  - `BootReceiver`.
  - `WakeReceiver`, registered at runtime for SCREEN_ON and USER_PRESENT.
- `com.rx8.dash.data`
  - Room `Rx8Database` (version 1, `exportSchema = true`).
  - Entities: `TripEntity`, `SampleEntity` (with raw `fuelStatus` and `cmdLambda` columns), `MaintenanceEntity`, `DtcHistoryEntity`, `FillUpEntity`, `StartLogEntity`.
  - DAOs.
  - `TripRepository`, `MaintenanceRepository`.
  - `SettingsRepository` (DataStore). It exposes `val settings: Flow<AppSettings>` plus `suspend fun update(transform: (AppSettings) -> AppSettings)`.
  - `AppSettings` data class with these fields:
    - Connection: `deviceAddress`, `demoMode`, `extendedPids`, `tpms`.
    - Car: `series`, `redlineRpm`.
    - Fuel: `fuelDensity`, `fuelCalibration`.
    - Display: `palette`, `gaugeSlots: List<Metric>`.
    - Connection timing: `initialConnectDelayMs`.
    - Knob: `keyMap: Map<Int /*keycode*/, KnobAction>`.
  - `enum KnobAction { NEXT, PREVIOUS, SELECT, BACK, NONE }`.
  - `BackupManager`, which exports and imports JSON/CSV to a SAF Uri.
- `com.rx8.dash.media`
  - `MediaListenerService` (NotificationListenerService), which publishes connection state.
  - `MediaPanelController`, which provides now-playing via `MediaSessionManager.getActiveSessions(component)`, transport controls, and the rebind watchdog.
- `com.rx8.dash.ui`
  - `theme/`.
  - `nav/`, using simple state-based tab navigation (no navigation-compose dependency).
  - `knob/`: `KnobInput`, a Modifier that combines `onRotaryScrollEvent` and `onPreviewKeyEvent` with the keymap and moves a focus index.
  - `components/`: TachometerGauge, ArcGauge, DigitalReadout, WarningLight, AlertBanner.
  - `screens/`: Onboarding, Dashboard, RotaryHealth, Trips, Diagnostics, Maintenance, Settings, DevicePicker.
  - `vm/`: one ViewModel per screen, created with `viewModelFactory { initializer { ... } }` using `(application as App).graph`.

## Rules
- No write, coding or flash commands, ever. Mode 04 only via `clearDtcsSafely` after two-step UI confirmation.
- Never call blocking IO on Main.
- No secrets in the repo.
